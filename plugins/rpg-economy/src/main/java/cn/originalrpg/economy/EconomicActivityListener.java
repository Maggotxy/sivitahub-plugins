package cn.originalrpg.economy;

import cn.originalrpg.api.model.ActivityRewardResult;
import cn.originalrpg.api.model.ActivityRewardRequest;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.CommissionEvidenceGrantResult;
import cn.originalrpg.api.model.DailyItemRewardResult;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.CombatRules;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.EquipmentProgressService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.raid.RaidFinishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Base64;

final class EconomicActivityListener implements Listener {
    private static final String ESSENCE_REWARD_ID = "survival-resonance-essence";
    private final Plugin plugin;
    private final ActivityEconomyService economy;
    private final ProfileService profiles;
    private final MessageService messages;
    private final EquipmentProgressService equipmentProgress;
    private final CommissionEvidenceTracker commissionEvidence;
    private final NamespacedKey mobLevel = NamespacedKey.fromString("rpgmobs:level");
    private final NamespacedKey elite = NamespacedKey.fromString("rpgmobs:elite_affix");
    private final NamespacedKey bloodMoon = NamespacedKey.fromString("rpgworld:blood_moon");
    private final NamespacedKey dungeonId = NamespacedKey.fromString("rpgdungeons:dungeon_id");
    private final NamespacedKey dungeonParticipants = NamespacedKey.fromString("rpgdungeons:participants");
    private final NamespacedKey forgeEssence = NamespacedKey.fromString("rpgitems:forge_essence");
    private final NamespacedKey materialOwner = NamespacedKey.fromString("rpgitems:material_owner");
    private final NamespacedKey vanillaOrigin = NamespacedKey.fromString("rpgeconomy:vanilla_origin");
    private final NamespacedKey essenceDate = NamespacedKey.fromString("rpgeconomy:essence_date");
    private final NamespacedKey essenceCount = NamespacedKey.fromString("rpgeconomy:essence_count");
    private final NamespacedKey raidRewardId;
    private final PlayerPlacedOreTracker placedOres;
    private final Map<UUID, ArrayDeque<ActivityRewardRequest>> pendingHunts = new HashMap<>();
    /**
     * Batches removed from {@link #pendingHunts} but not yet acknowledged on the server thread.
     * Keeping the immutable request list here closes the disable race between a completed JDBC
     * future and its queued main-thread completion callback. Every request already owns a stable
     * idempotency key, so shutdown may safely replay the batch even when the first write committed.
     */
    private final Map<UUID, List<ActivityRewardRequest>> inFlightHunts = new HashMap<>();
    private final ArrayDeque<UUID> readyPlayers = new ArrayDeque<>();
    private final Set<UUID> queuedPlayers = new HashSet<>();
    private final Set<UUID> writesInFlight = new HashSet<>();
    private final Set<UUID> profileLoadsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<UUID> cappedPlayers = new HashSet<>();
    private final Set<UUID> backpressureWarned = new HashSet<>();
    private final int maxBatchSize;
    private final int maxFlushesPerTick;
    private final int maxPendingPerPlayer;
    private final long shutdownTimeoutMillis;
    private BukkitTask flushTask;
    private LocalDate capDate = LocalDate.now();
    private boolean shuttingDown;

    EconomicActivityListener(
            Plugin plugin,
            ActivityEconomyService economy,
            ProfileService profiles,
            MessageService messages,
            EquipmentProgressService equipmentProgress,
            CommissionEvidenceTracker commissionEvidence
    ) {
        this.plugin = plugin;
        this.economy = economy;
        this.profiles = profiles;
        this.messages = messages;
        this.equipmentProgress = equipmentProgress;
        this.commissionEvidence = commissionEvidence;
        raidRewardId = new NamespacedKey(plugin, "raid_reward_id");
        placedOres = new PlayerPlacedOreTracker(plugin);
        maxBatchSize = Math.clamp(plugin.getConfig().getInt("hunting-write-buffer.max-batch-size", 64), 1, 128);
        maxFlushesPerTick = Math.clamp(
                plugin.getConfig().getInt("hunting-write-buffer.max-player-flushes-per-tick", 6), 1, 32);
        maxPendingPerPlayer = Math.clamp(
                plugin.getConfig().getInt("hunting-write-buffer.max-pending-per-player", 512), maxBatchSize, 4096);
        shutdownTimeoutMillis = Math.clamp(
                plugin.getConfig().getLong("hunting-write-buffer.shutdown-timeout-ms", 5000L), 1000L, 30_000L);
    }

    void start() {
        long flushTicks = Math.clamp(plugin.getConfig().getLong("hunting-write-buffer.flush-ticks", 20L), 1L, 100L);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushReadyPlayers,
                flushTicks, flushTicks);
    }

    void shutdown() {
        shuttingDown = true;
        if (flushTask != null) flushTask.cancel();
        ArrayList<CompletableFuture<?>> finalWrites = new ArrayList<>();
        for (ShutdownBatch batch : shutdownPlan(pendingHunts, inFlightHunts, maxBatchSize)) {
            CompletableFuture<ActivityRewardResult> write;
            try {
                write = economy.rewardBatch(
                        batch.playerId(), EconomyActivity.HUNTING, batch.rewards(),
                        capGold(EconomyActivity.HUNTING), capMerit(EconomyActivity.HUNTING))
                        .toCompletableFuture();
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("Buffered hunting reward shutdown replay could not start for "
                        + batch.playerId() + ": " + exception.getMessage());
                continue;
            }
            write.whenComplete((result, error) -> {
                if (error != null || result == null || result.status() == ActivityRewardResult.Status.INVALID) {
                    plugin.getLogger().severe("Buffered hunting rewards were not persisted for "
                            + batch.playerId() + ": "
                            + (error == null ? "invalid result" : error.getMessage()));
                }
            });
            finalWrites.add(write);
        }
        pendingHunts.clear();
        inFlightHunts.clear();
        writesInFlight.clear();
        queuedPlayers.clear();
        readyPlayers.clear();
        if (finalWrites.isEmpty()) return;
        try {
            CompletableFuture.allOf(finalWrites.toArray(CompletableFuture[]::new))
                    .get(shutdownTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            plugin.getLogger().warning("Timed out while draining buffered hunting rewards: " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        String dungeon = dungeonId == null ? null
                : event.getEntity().getPersistentDataContainer().get(dungeonId, PersistentDataType.STRING);
        if (dungeon != null) {
            rewardDungeonParty(killer, event.getEntity(), dungeon);
            return;
        }
        Integer level = mobLevel == null ? null
                : event.getEntity().getPersistentDataContainer().get(mobLevel, PersistentDataType.INTEGER);
        if (level == null) return;
        UUID entityId = event.getEntity().getUniqueId();
        boolean isElite = elite != null
                && event.getEntity().getPersistentDataContainer().has(elite, PersistentDataType.STRING);
        boolean isBloodMoon = bloodMoon != null
                && event.getEntity().getPersistentDataContainer().has(bloodMoon, PersistentDataType.BYTE);
        UUID playerId = killer.getUniqueId();
        PlayerProfile profile = profiles.cached(playerId).orElse(null);
        if (profile == null) {
            preloadProfile(playerId);
            return;
        }
        if (CombatRules.allowsCustomDrops(level, profile.effectiveLevel())) {
            rewardHunt(killer, entityId, level, profile, isElite, isBloodMoon);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Advancement advancement = event.getAdvancement();
        String key = advancement.getKey().asString();
        if (key.startsWith("minecraft:recipes/")) return;
        Player player = event.getPlayer();
        grantCommissionEvidence(player, "advancement");
        progressEquipped(player, plugin.getConfig().getInt("vanilla-integration.equipment-xp.advancement", 25));
        BigDecimal gold = decimal("rewards.advancement.gold", "120.00");
        long merit = plugin.getConfig().getLong("rewards.advancement.merit", 1L);
        String identity = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
        reward(player, EconomyActivity.EXPLORATION, gold, merit,
                "adv:" + player.getUniqueId() + ":" + identity, "vanilla-advancement:" + key, true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getName().startsWith("dungeon_")) return;
        Material material = event.getBlock().getType();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (Tag.LOGS.isTagged(material) && tool.getType().name().endsWith("_AXE")) {
            progress(tool, player, plugin.getConfig().getInt("vanilla-integration.equipment-xp.log", 2));
        }
        boolean matureCrop = Tag.CROPS.isTagged(material)
                && event.getBlock().getBlockData() instanceof Ageable crop
                && crop.getAge() >= crop.getMaximumAge();
        if (matureCrop) {
            if (tool.getType().name().endsWith("_HOE")) {
                progress(tool, player, plugin.getConfig().getInt("vanilla-integration.equipment-xp.crop", 2));
            }
            rollEssence(player, "farming", plugin.getConfig().getDouble(
                    "vanilla-integration.essence.chance.farming", 0.012), 1,
                    blockEventIdentity(event));
        }
        boolean playerPlacedOre = VanillaIntegrationRules.isResonantOre(material.name())
                && placedOres.remove(event.getBlock());
        // Positive event XP rejects Silk Touch; the persistent chunk marker separately rejects placed-ore cycling.
        if (VanillaIntegrationRules.isNaturalResonantOre(material.name(), event.getExpToDrop(), playerPlacedOre)) {
            grantCommissionEvidence(player, "natural-mining");
            progressArmor(player, plugin.getConfig().getInt("vanilla-integration.equipment-xp.ore", 3));
            rollEssence(player, "mining", plugin.getConfig().getDouble(
                    "vanilla-integration.essence.chance.mining", 0.055), 1,
                    blockEventIdentity(event));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (VanillaIntegrationRules.isResonantOre(event.getBlockPlaced().getType().name())) {
            placedOres.mark(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        placedOres.move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        placedOres.move(event.getBlocks(), event.getDirection().getOppositeFace());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        placedOres.removeAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        placedOres.removeAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        Player player = event.getPlayer();
        grantCommissionEvidence(player, "fishing");
        BigDecimal gold = decimal("rewards.vanilla.fishing.gold", "14.00");
        long merit = plugin.getConfig().getLong("rewards.vanilla.fishing.merit", 0L);
        reward(player, EconomyActivity.GATHERING, gold, merit,
                "fish:" + caught.getUniqueId(), "vanilla-fishing:" + caught.getItemStack().getType(), false);
        progressArmor(player, plugin.getConfig().getInt("vanilla-integration.equipment-xp.fishing", 3));
        rollEssence(player, "fishing", plugin.getConfig().getDouble(
                "vanilla-integration.essence.chance.fishing", 0.08), 1,
                "fish:" + caught.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRaidFinish(RaidFinishEvent event) {
        String raidId = event.getRaid().getPersistentDataContainer().get(raidRewardId, PersistentDataType.STRING);
        if (raidId == null) {
            raidId = UUID.randomUUID().toString();
            event.getRaid().getPersistentDataContainer().set(raidRewardId, PersistentDataType.STRING, raidId);
        }
        for (Player player : event.getWinners()) {
            grantCommissionEvidence(player, "raid");
            reward(player, EconomyActivity.RAID,
                    decimal("rewards.vanilla.raid.gold", "600.00"),
                    plugin.getConfig().getLong("rewards.vanilla.raid.merit", 8L),
                    "raid:" + raidId + ":" + player.getUniqueId(),
                    "vanilla-raid-victory", true);
            progressEquipped(player, plugin.getConfig().getInt("vanilla-integration.equipment-xp.raid", 120));
            grantEssence(player, "raid", plugin.getConfig().getInt(
                    "vanilla-integration.essence.raid-amount", 2),
                    "raid:" + raidId + ":" + player.getUniqueId());
        }
    }

    private void grantCommissionEvidence(Player player, String source) {
        commissionEvidence.grant(player, source).whenComplete((result, error) ->
                sync(() -> {
                    if (error != null) {
                        plugin.getLogger().warning("Commission evidence grant failed closed for "
                                + player.getUniqueId() + ": " + error.getMessage());
                        return;
                    }
                    if (!currentSession(player) || result == null || result.granted() <= 0) return;
                    CommissionEvidenceGrantResult grant = result;
                    player.sendActionBar(Component.text(messages.text(player,
                            "economy.commission-evidence-found", Map.of(
                            "amount", grant.granted(), "used", grant.snapshot().earned(),
                            "cap", grant.snapshot().dailyCap(),
                            "source", messages.text(player,
                                    "economy.commission-evidence-sources." + source)
                    )), NamedTextColor.GREEN));
                }));
    }

    private void progress(ItemStack item, Player player, int amount) {
        if (equipmentProgress != null && amount > 0) equipmentProgress.grantUseExperience(player, item, amount);
    }

    private void progressArmor(Player player, int amount) {
        if (equipmentProgress == null || amount <= 0) return;
        for (ItemStack item : player.getInventory().getArmorContents()) progress(item, player, amount);
        progress(player.getInventory().getItemInOffHand(), player, amount);
    }

    private void progressEquipped(Player player, int amount) {
        if (equipmentProgress == null || amount <= 0) return;
        progress(player.getInventory().getItemInMainHand(), player, amount);
        progressArmor(player, amount);
    }

    private void rollEssence(
            Player player, String origin, double chance, int amount, String eventIdentity
    ) {
        if (ThreadLocalRandom.current().nextDouble() < Math.clamp(chance, 0.0, 1.0)) {
            grantEssence(player, origin, amount, eventIdentity);
        }
    }

    private void grantEssence(
            Player player, String origin, int requestedAmount, String eventIdentity
    ) {
        if (requestedAmount <= 0 || forgeEssence == null || materialOwner == null || vanillaOrigin == null
                || essenceDate == null || essenceCount == null
                || eventIdentity == null || eventIdentity.isBlank()) return;
        String today = LocalDate.now().toString();
        var playerData = player.getPersistentDataContainer();
        String storedDate = playerData.get(essenceDate, PersistentDataType.STRING);
        int legacyUsed = today.equals(storedDate)
                ? Math.max(0, playerData.getOrDefault(essenceCount, PersistentDataType.INTEGER, 0)) : 0;
        int cap = Math.clamp(
                plugin.getConfig().getInt("vanilla-integration.essence.daily-cap", 12), 1, 128);
        int migrationFloor = Math.clamp(legacyUsed, 0, 128);

        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "economy.vanilla-essence-name"), NamedTextColor.AQUA));
        meta.lore(List.of(
                Component.text(messages.text(player, "economy.vanilla-essence-origin", Map.of(
                        "origin", messages.text(player, "economy.vanilla-origins." + origin))), NamedTextColor.GRAY),
                Component.text(messages.text(player, "economy.vanilla-essence-use"), NamedTextColor.DARK_GRAY),
                Component.text(messages.text(player, "economy.item-bound"), NamedTextColor.RED)
        ));
        meta.setItemModel(new NamespacedKey("originalrpg", "hunt_crystal"));
        meta.getPersistentDataContainer().set(forgeEssence, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(materialOwner, PersistentDataType.STRING, player.getUniqueId().toString());
        meta.getPersistentDataContainer().set(vanillaOrigin, PersistentDataType.STRING, origin);
        item.setItemMeta(meta);
        String payload = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        String key = "essence:" + UUID.nameUUIDFromBytes((player.getUniqueId() + ":" + origin + ":"
                + eventIdentity).getBytes(StandardCharsets.UTF_8));
        economy.rewardDailyItem(
                player.getUniqueId(), ESSENCE_REWARD_ID, requestedAmount, cap, migrationFloor,
                payload, key, "vanilla-essence:" + origin
        ).whenComplete((result, error) -> sync(() -> completeEssenceGrant(player, result, error)));
    }

    private void completeEssenceGrant(
            Player player, DailyItemRewardResult result, Throwable error
    ) {
        if (error != null) {
            plugin.getLogger().warning("Survival resonance essence settlement failed for "
                    + player.getUniqueId() + ": " + error.getMessage());
            return;
        }
        if (result == null || result.status() == DailyItemRewardResult.Status.INVALID) {
            plugin.getLogger().severe("Survival resonance essence settlement returned an invalid result for "
                    + player.getUniqueId());
            return;
        }
        if (!currentSession(player)) return;
        // Legacy PDC remains only as a one-way migration/checkpoint hint. The
        // database quota and mailbox are already authoritative at this point.
        player.getPersistentDataContainer().set(
                essenceDate, PersistentDataType.STRING, result.rewardDate().toString());
        player.getPersistentDataContainer().set(
                essenceCount, PersistentDataType.INTEGER, result.used());
        player.saveData();
        if (result.status() != DailyItemRewardResult.Status.SUCCESS || result.granted() <= 0) return;
        player.sendActionBar(Component.text(messages.text(player, "economy.vanilla-essence-found", Map.of(
                "amount", result.granted(), "used", result.used(), "cap", result.dailyCap())),
                NamedTextColor.AQUA));
    }

    private static String blockEventIdentity(BlockBreakEvent event) {
        var block = event.getBlock();
        return "block:" + block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY()
                + ":" + block.getZ() + ":" + block.getWorld().getFullTime();
    }

    private void rewardHunt(
            Player player, UUID entityId, int level, PlayerProfile profile, boolean isElite, boolean isBloodMoon
    ) {
        BigDecimal base = decimal("rewards.hunting.base-gold", "6.00");
        BigDecimal perLevel = decimal("rewards.hunting.gold-per-level", "0.55");
        BigDecimal gold = base.add(perLevel.multiply(BigDecimal.valueOf(level)));
        if (isElite) gold = gold.multiply(BigDecimal.valueOf(plugin.getConfig().getDouble(
                "rewards.hunting.elite-multiplier", 4.0)));
        if (isBloodMoon) gold = gold.multiply(BigDecimal.valueOf(plugin.getConfig().getDouble(
                "rewards.hunting.blood-moon-multiplier", 1.5)));
        long merit = (isElite ? plugin.getConfig().getLong("rewards.hunting.elite-merit", 1L) : 0L)
                + (isBloodMoon ? plugin.getConfig().getLong("rewards.hunting.blood-moon-merit", 1L) : 0L);
        enqueueHunt(player, new ActivityRewardRequest(gold.setScale(2, RoundingMode.HALF_UP), merit,
                "hunt:" + entityId, "adaptive-hunt:level-" + level + ":player-" + profile.effectiveLevel()));
    }

    private void rewardDungeonParty(Player killer, LivingEntity boss, String id) {
        java.util.LinkedHashSet<Player> recipients = new java.util.LinkedHashSet<>();
        if (dungeonParticipants != null) {
            String encoded = boss.getPersistentDataContainer().get(dungeonParticipants, PersistentDataType.STRING);
            if (encoded != null && !encoded.isBlank()) {
                for (String value : encoded.split(",")) {
                    try {
                        Player member = Bukkit.getPlayer(UUID.fromString(value));
                        if (member != null && member.isOnline() && member.getWorld().equals(boss.getWorld())) {
                            recipients.add(member);
                        }
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("Invalid dungeon participant UUID on boss " + boss.getUniqueId());
                    }
                }
            }
        }
        recipients.add(killer);
        recipients.forEach(player -> rewardDungeon(player, boss, id));
    }

    private void rewardDungeon(Player player, LivingEntity boss, String id) {
        String path = "rewards.dungeons." + id;
        BigDecimal gold = decimal(path + ".gold", id.endsWith("-hidden") ? "2200.00" : "650.00");
        long merit = plugin.getConfig().getLong(path + ".merit", id.endsWith("-hidden") ? 28L : 8L);
        reward(player, EconomyActivity.DUNGEON, gold, merit,
                "dungeon:" + boss.getUniqueId() + ":" + player.getUniqueId(),
                "dungeon-completion:" + id, true);
    }

    private void reward(
            Player player, EconomyActivity activity, BigDecimal gold, long merit,
            String key, String reason, boolean chat
    ) {
        economy.reward(player.getUniqueId(), activity, gold, merit, capGold(activity), capMerit(activity), key, reason)
                .whenComplete((result, error) -> sync(() -> {
                    if (!currentSession(player) || error != null || result == null) return;
                    if (result.status() == ActivityRewardResult.Status.DAILY_LIMIT) {
                        if (chat) player.sendMessage(Component.text(
                                messages.text(player, "economy.activity-cap"), NamedTextColor.YELLOW));
                        return;
                    }
                    if (result.status() == ActivityRewardResult.Status.BALANCE_LIMIT) {
                        if (chat) player.sendMessage(Component.text(
                                messages.text(player, "economy.activity-balance-limit"), NamedTextColor.YELLOW));
                        return;
                    }
                    if (result.status() == ActivityRewardResult.Status.MERIT_LIMIT) {
                        if (chat) player.sendMessage(Component.text(
                                messages.text(player, "economy.activity-merit-limit"), NamedTextColor.YELLOW));
                        return;
                    }
                    if (result.goldGranted().signum() <= 0 && result.meritGranted() <= 0) return;
                    Component message = Component.text(messages.text(player, "economy.activity-gold", Map.of(
                                    "amount", result.goldGranted().toPlainString())), NamedTextColor.GOLD)
                            .append(Component.text(result.meritGranted() > 0
                                            ? messages.text(player, "economy.activity-merit", Map.of(
                                                    "amount", result.meritGranted())) : "", NamedTextColor.AQUA));
                    if (chat) player.sendMessage(message); else player.sendActionBar(message);
                }));
    }

    private void preloadProfile(UUID playerId) {
        if (!profileLoadsInFlight.add(playerId)) return;
        profiles.get(playerId).whenComplete((profile, error) -> {
            profileLoadsInFlight.remove(playerId);
            if (error != null) plugin.getLogger().warning(
                    "Profile preload after activity failed for " + playerId + ": " + error.getMessage());
        });
    }

    private void enqueueHunt(Player player, ActivityRewardRequest reward) {
        rolloverCapsIfNeeded();
        UUID playerId = player.getUniqueId();
        if (cappedPlayers.contains(playerId)) return;
        ArrayDeque<ActivityRewardRequest> pending = pendingHunts.computeIfAbsent(playerId,
                ignored -> new ArrayDeque<>());
        if (pending.size() >= maxPendingPerPlayer) {
            if (backpressureWarned.add(playerId)) {
                player.sendMessage(Component.text(messages.text(player, "economy.reward-backpressure"),
                        NamedTextColor.YELLOW));
                plugin.getLogger().warning("Hunting reward buffer reached its safety limit for " + playerId);
            }
            return;
        }
        pending.addLast(reward);
        markReady(playerId);
    }

    private void flushReadyPlayers() {
        rolloverCapsIfNeeded();
        int attempts = Math.min(readyPlayers.size(), maxFlushesPerTick);
        for (int index = 0; index < attempts; index++) {
            UUID playerId = readyPlayers.pollFirst();
            if (playerId == null) break;
            queuedPlayers.remove(playerId);
            if (writesInFlight.contains(playerId)) {
                markReady(playerId);
                continue;
            }
            flushPlayer(playerId);
        }
    }

    private CompletableFuture<?> flushPlayer(UUID playerId) {
        ArrayDeque<ActivityRewardRequest> pending = pendingHunts.get(playerId);
        if (pending == null || pending.isEmpty() || !writesInFlight.add(playerId)) return null;
        int count = Math.min(maxBatchSize, pending.size());
        ArrayList<ActivityRewardRequest> batch = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ActivityRewardRequest reward = pending.pollFirst();
            if (reward != null) batch.add(reward);
        }
        if (pending.isEmpty()) pendingHunts.remove(playerId);
        else markReady(playerId);

        List<ActivityRewardRequest> frozenBatch = List.copyOf(batch);
        inFlightHunts.put(playerId, frozenBatch);
        CompletableFuture<ActivityRewardResult> write;
        try {
            write = economy.rewardBatch(
                    playerId, EconomyActivity.HUNTING, frozenBatch,
                    capGold(EconomyActivity.HUNTING), capMerit(EconomyActivity.HUNTING)).toCompletableFuture();
        } catch (RuntimeException exception) {
            completeHuntWrite(playerId, frozenBatch, null, exception);
            return null;
        }
        write.whenComplete((result, error) -> {
            if (shuttingDown) {
                if (error != null) plugin.getLogger().severe("Buffered hunting rewards were not persisted for "
                        + playerId + ": " + error.getMessage());
                return;
            }
            sync(() -> completeHuntWrite(playerId, frozenBatch, result, error));
        });
        return write;
    }

    private void completeHuntWrite(
            UUID playerId, List<ActivityRewardRequest> batch, ActivityRewardResult result, Throwable error
    ) {
        writesInFlight.remove(playerId);
        inFlightHunts.remove(playerId, batch);
        if (error != null || result == null || result.status() == ActivityRewardResult.Status.INVALID) {
            ArrayDeque<ActivityRewardRequest> pending = pendingHunts.computeIfAbsent(playerId,
                    ignored -> new ArrayDeque<>());
            for (int index = batch.size() - 1; index >= 0; index--) pending.addFirst(batch.get(index));
            markReady(playerId);
            plugin.getLogger().warning("Buffered hunting reward write will retry for " + playerId + ": "
                    + (error == null ? "invalid result" : error.getMessage()));
            return;
        }
        if (result.status() == ActivityRewardResult.Status.DAILY_LIMIT) {
            cappedPlayers.add(playerId);
            pendingHunts.remove(playerId);
            queuedPlayers.remove(playerId);
            readyPlayers.removeIf(playerId::equals);
        }
        ArrayDeque<ActivityRewardRequest> remaining = pendingHunts.get(playerId);
        if (remaining != null && !remaining.isEmpty() && !cappedPlayers.contains(playerId)) markReady(playerId);
        if (remaining == null || remaining.size() < maxPendingPerPlayer / 2) backpressureWarned.remove(playerId);

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        if (result.status() == ActivityRewardResult.Status.DAILY_LIMIT) {
            player.sendActionBar(Component.text(messages.text(player, "economy.activity-cap"), NamedTextColor.YELLOW));
        } else if (result.status() == ActivityRewardResult.Status.BALANCE_LIMIT) {
            player.sendActionBar(Component.text(
                    messages.text(player, "economy.activity-balance-limit"), NamedTextColor.YELLOW));
        } else if (result.status() == ActivityRewardResult.Status.MERIT_LIMIT) {
            player.sendActionBar(Component.text(
                    messages.text(player, "economy.activity-merit-limit"), NamedTextColor.YELLOW));
        } else if (result.goldGranted().signum() > 0 || result.meritGranted() > 0) {
            Component message = Component.text(messages.text(player, "economy.activity-gold", Map.of(
                            "amount", result.goldGranted().toPlainString())), NamedTextColor.GOLD)
                    .append(Component.text(result.meritGranted() > 0
                                    ? messages.text(player, "economy.activity-merit", Map.of(
                                    "amount", result.meritGranted())) : "", NamedTextColor.AQUA));
            player.sendActionBar(message);
        }
    }

    private void markReady(UUID playerId) {
        if (queuedPlayers.add(playerId)) readyPlayers.addLast(playerId);
    }

    static List<ShutdownBatch> shutdownPlan(
            Map<UUID, ArrayDeque<ActivityRewardRequest>> pending,
            Map<UUID, List<ActivityRewardRequest>> inFlight,
            int maximumBatchSize
    ) {
        if (maximumBatchSize < 1) throw new IllegalArgumentException("maximumBatchSize must be positive");
        ArrayList<ShutdownBatch> plan = new ArrayList<>();
        inFlight.forEach((playerId, rewards) -> {
            if (playerId != null && rewards != null && !rewards.isEmpty()) {
                plan.add(new ShutdownBatch(playerId, List.copyOf(rewards)));
            }
        });
        pending.forEach((playerId, rewards) -> {
            if (playerId == null || rewards == null || rewards.isEmpty()) return;
            ArrayList<ActivityRewardRequest> batch = new ArrayList<>(maximumBatchSize);
            for (ActivityRewardRequest reward : rewards) {
                batch.add(reward);
                if (batch.size() == maximumBatchSize) {
                    plan.add(new ShutdownBatch(playerId, List.copyOf(batch)));
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) plan.add(new ShutdownBatch(playerId, List.copyOf(batch)));
        });
        return List.copyOf(plan);
    }

    private void rolloverCapsIfNeeded() {
        LocalDate today = LocalDate.now();
        if (today.equals(capDate)) return;
        capDate = today;
        cappedPlayers.clear();
    }

    private BigDecimal capGold(EconomyActivity activity) {
        return decimal("caps." + activity.name() + ".gold", "0.00");
    }

    private long capMerit(EconomyActivity activity) {
        return plugin.getConfig().getLong("caps." + activity.name() + ".merit", 0L);
    }

    private BigDecimal decimal(String path, String fallback) {
        return new BigDecimal(plugin.getConfig().getString(path, fallback));
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private void sync(Runnable action) {
        if (shuttingDown || !plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!shuttingDown && plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // The asynchronous database operation is already authoritative;
            // only transient feedback or the next in-process buffer tick is skipped.
        }
    }

    record ShutdownBatch(UUID playerId, List<ActivityRewardRequest> rewards) {
        ShutdownBatch {
            java.util.Objects.requireNonNull(playerId, "playerId");
            rewards = List.copyOf(rewards);
            if (rewards.isEmpty()) throw new IllegalArgumentException("Shutdown batch must not be empty");
        }
    }
}
