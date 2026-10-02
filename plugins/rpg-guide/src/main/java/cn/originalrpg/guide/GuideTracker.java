package cn.originalrpg.guide;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.event.DungeonCompletedEvent;
import cn.originalrpg.api.model.ActivityRewardResult;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.JourneyProgress;
import cn.originalrpg.api.model.JourneyStep;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.rules.PlayerJourneyFacts;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.SkillHudService;
import cn.originalrpg.api.service.DungeonRewardService;
import cn.originalrpg.api.ui.HudGlyphs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class GuideTracker implements Listener, JourneyService {
    static final List<JourneyStep> STEPS = List.of(
            JourneyStep.HUB,
            JourneyStep.CLASS,
            JourneyStep.ATTRIBUTE,
            JourneyStep.FORGE,
            JourneyStep.EXPLORE,
            JourneyStep.HUNT,
            JourneyStep.REVEAL,
            JourneyStep.DUNGEON);
    private static final JourneyProgress EMPTY_PROGRESS = new JourneyProgress(STEPS, Set.of());

    private final Plugin plugin;
    private final ProfileService profiles;
    private final ActivityEconomyService economy;
    private final DungeonRewardService dungeonRewards;
    private final MessageService messages;
    private final StarterKitService starterKit;
    private final NamespacedKey handbook;
    private final NamespacedKey handbookModel = new NamespacedKey("originalrpg", "traveler_compass");
    private final NamespacedKey mobLevel = NamespacedKey.fromString("rpgmobs:level");
    private final NamespacedKey itemId = NamespacedKey.fromString("rpgitems:item_id");
    private final NamespacedKey totalSlots = NamespacedKey.fromString("rpgitems:total_slots");
    private final NamespacedKey revealedSlots = NamespacedKey.fromString("rpgitems:revealed_slots");
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dungeonEvidenceChecked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> dungeonEvidenceMisses = new ConcurrentHashMap<>();
    private final Map<UUID, JourneyProgress> snapshots = new ConcurrentHashMap<>();
    private Consumer<Player> menuOpener = ignored -> { };
    private static final int MAXIMUM_DUNGEON_EVIDENCE_MISSES = 4;
    private static final long DUNGEON_EVIDENCE_RETRY_TICKS = 20L * 15L;

    GuideTracker(
            Plugin plugin, ProfileService profiles, ActivityEconomyService economy,
            DungeonRewardService dungeonRewards,
            MessageService messages, StarterKitService starterKit
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.economy = economy;
        this.dungeonRewards = java.util.Objects.requireNonNull(dungeonRewards, "dungeonRewards");
        this.messages = messages;
        this.starterKit = starterKit;
        handbook = new NamespacedKey(plugin, "handbook");
    }

    void setMenuOpener(Consumer<Player> menuOpener) {
        this.menuOpener = menuOpener;
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        authenticatedPlayers.add(player.getUniqueId());
        ensureHandbook(player);
        publish(player);
        evaluate(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player().getUniqueId());
    }

    private void clearSession(UUID playerId) {
        authenticatedPlayers.remove(playerId);
        dungeonEvidenceChecked.remove(playerId);
        dungeonEvidenceMisses.remove(playerId);
        snapshots.remove(playerId);
        pending.removeIf(value -> value.startsWith(playerId + ":"));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (isHandbook(item)) {
            event.setCancelled(true);
            menuOpener.accept(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isHandbook(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text(messages.text(
                    event.getPlayer(), "guide.onboarding.handbook-drop"), NamedTextColor.YELLOW));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlot() >= topSize && !event.isShiftClick()) return;
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? player.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offhand = event.getClick() == ClickType.SWAP_OFFHAND
                ? player.getInventory().getItemInOffHand() : null;
        if (isHandbook(event.getCurrentItem()) || isHandbook(event.getCursor())
                || isHandbook(hotbar) || isHandbook(offhand)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text(messages.text(
                    player, "guide.onboarding.handbook-container"), NamedTextColor.YELLOW));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isHandbook(event.getOldCursor())) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::isHandbook);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> ensureHandbook(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        if (Bukkit.isPrimaryThread()) {
            publish(player);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ready(player)) publish(player);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        NamespacedKey key = event.getAdvancement().getKey();
        Player player = event.getPlayer();
        String explorationWorld = plugin.getConfig().getString("onboarding.exploration-world", "world");
        if (GuideCompletionPolicy.isVanillaExplorationAdvancement(
                key.getNamespace(), key.getKey(), PlayerJourneyFacts.hasWildernessArrival(player),
                player.getWorld().getName(), explorationWorld,
                player.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL)) {
            complete(player, JourneyStep.EXPLORE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDungeonCompleted(DungeonCompletedEvent event) {
        for (UUID recipientId : event.recipientIds()) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient != null && recipient.isOnline()) complete(recipient, JourneyStep.DUNGEON);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        Integer dynamicLevel = mobLevel == null ? null : event.getEntity().getPersistentDataContainer().get(
                mobLevel, PersistentDataType.INTEGER);
        if (GuideCompletionPolicy.adaptiveMonsterKillCompletes(dynamicLevel)) {
            complete(killer, JourneyStep.HUNT);
        }
    }

    void evaluate(Player player) {
        requirePrimaryThread();
        publish(player);
        if (PlayerJourneyFacts.hasWildernessArrival(player)) {
            starterKit.tryGrantClaimTool(player);
        }
        // Starter-kit eligibility is versioned independently from the journey.
        // A returning account may have finished an older journey before a
        // first-forge kit existed, so only skip the completed-step scan here;
        // always load its profile and let StarterKitService reconcile the kit.
        boolean journeyComplete = completedCount(player) == STEPS.size();
        if (journeyComplete && !starterKit.needsReconciliation(player)) return;
        if (!journeyComplete) {
            for (JourneyStep step : STEPS) {
                if (observed(player, step) && !completed(player, step)) award(player, step);
            }
        }
        profiles.get(player.getUniqueId()).whenComplete((profile, error) -> sync(() -> {
            if (!ready(player) || error != null) return;
            if (profile.archetype() != Archetype.UNCHOSEN) complete(player, JourneyStep.CLASS);
            starterKit.tryGrant(player, profile.archetype());
            if (journeyComplete) return;
            int allocatedAttributePoints = profile.attributes().values().stream()
                    .mapToInt(value -> Math.max(0, value))
                    .sum();
            SkillHudService skills = OptionalServices.enabled(plugin, SkillHudService.class);
            int learnedSkills = skills == null ? 0 : skills.snapshot(player.getUniqueId())
                    .map(snapshot -> snapshot.learnedSkills().size()).orElse(0);
            if (GuideCompletionPolicy.starterBuildReady(allocatedAttributePoints, learnedSkills)) {
                complete(player, JourneyStep.ATTRIBUTE);
            }
            boolean forged = false;
            boolean revealed = false;
            for (ItemStack item : allEquipment(player)) {
                ItemMeta meta = item == null ? null : item.getItemMeta();
                if (meta == null || itemId == null || !meta.getPersistentDataContainer().has(itemId, PersistentDataType.STRING)) continue;
                forged = true;
                Integer total = totalSlots == null ? null
                        : meta.getPersistentDataContainer().get(totalSlots, PersistentDataType.INTEGER);
                int visible = revealedSlots == null ? 0 : meta.getPersistentDataContainer().getOrDefault(
                        revealedSlots, PersistentDataType.INTEGER, 0);
                if (EquipmentJourneyPolicy.slotOutcomeConfirmed(total, visible)) revealed = true;
            }
            if (forged) complete(player, JourneyStep.FORGE);
            if (revealed) complete(player, JourneyStep.REVEAL);
        }));
        queryDungeonEvidence(player);
    }

    private void queryDungeonEvidence(Player player) {
        if (completed(player, JourneyStep.DUNGEON)
                || !dungeonEvidenceChecked.add(player.getUniqueId())) return;
        dungeonRewards.hasAnyCompletion(player.getUniqueId()).whenComplete((completedDungeon, error) -> sync(() -> {
            if (!ready(player)) return;
            if (error != null) {
                dungeonEvidenceChecked.remove(player.getUniqueId());
                return;
            }
            if (Boolean.TRUE.equals(completedDungeon)) {
                dungeonEvidenceMisses.remove(player.getUniqueId());
                complete(player, JourneyStep.DUNGEON);
                return;
            }
            int misses = dungeonEvidenceMisses.merge(player.getUniqueId(), 1, Integer::sum);
            if (misses >= MAXIMUM_DUNGEON_EVIDENCE_MISSES) return;
            dungeonEvidenceChecked.remove(player.getUniqueId());
            try {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (ready(player)) queryDungeonEvidence(player);
                }, DUNGEON_EVIDENCE_RETRY_TICKS);
            } catch (IllegalStateException ignored) {
                // Plugin shutdown owns the next-login replay.
            }
        }));
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Plugin shutdown owns the next-login replay.
        }
    }

    @Override
    public JourneyProgress progress(UUID playerId) {
        return snapshots.getOrDefault(playerId, EMPTY_PROGRESS);
    }

    @Override
    public void refresh(Player player) {
        requirePrimaryThread();
        evaluate(player);
    }

    boolean completed(Player player, JourneyStep step) {
        NamespacedKey key = stepKey(step);
        return key != null && player.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    int completedCount(Player player) {
        return (int) STEPS.stream().filter(step -> completed(player, step)).count();
    }

    JourneyStep nextStep(Player player) {
        requirePrimaryThread();
        publish(player);
        return progress(player.getUniqueId()).nextStep().orElse(null);
    }

    boolean isReady(UUID playerId) {
        return snapshots.containsKey(playerId);
    }

    private void complete(Player player, JourneyStep step) {
        NamespacedKey observedKey = observedKey(step);
        if (observedKey == null || completed(player, step)) return;
        if (!observed(player, step)) {
            player.getPersistentDataContainer().set(observedKey, PersistentDataType.BYTE, (byte) 1);
            player.saveData();
        }
        award(player, step);
    }

    private void award(Player player, JourneyStep step) {
        String pendingId = player.getUniqueId() + ":" + step.id();
        if (completed(player, step) || !pending.add(pendingId)) return;
        BigDecimal gold = new BigDecimal(plugin.getConfig().getString("onboarding.step-gold", "100.00"));
        long merit = plugin.getConfig().getLong("onboarding.step-merit", 2L);
        economy.rewardExact(player.getUniqueId(), EconomyActivity.GUIDE, gold, merit,
                new BigDecimal(plugin.getConfig().getString("onboarding.daily-gold-cap", "1000.00")),
                plugin.getConfig().getLong("onboarding.daily-merit-cap", 20L),
                "guide:" + player.getUniqueId() + ":" + step.id(), "guide-step:" + step.id())
                .whenComplete((result, error) -> sync(() -> {
                    pending.remove(pendingId);
                    if (error != null) {
                        plugin.getLogger().warning("Guide reward will retry for player=" + player.getUniqueId()
                                + " step=" + step.id() + ": " + error.getMessage());
                        return;
                    }
                    if (result.status() != ActivityRewardResult.Status.SUCCESS
                            && result.status() != ActivityRewardResult.Status.DUPLICATE) {
                        plugin.getLogger().warning("Guide reward could not be issued yet for player="
                                + player.getUniqueId() + " step=" + step.id()
                                + " status=" + result.status());
                        return;
                    }
                    // Reward settlement is idempotent and may finish after a
                    // disconnect/fast reconnect. Leave the observed fact for
                    // the replacement login to replay; an old Player handle
                    // must never persist over the current session's PDC.
                    if (!ready(player)) return;
                    NamespacedKey completedKey = stepKey(step);
                    NamespacedKey observedKey = observedKey(step);
                    if (completedKey != null) {
                        player.getPersistentDataContainer().set(completedKey, PersistentDataType.BYTE, (byte) 1);
                        if (observedKey != null) player.getPersistentDataContainer().remove(observedKey);
                        player.saveData();
                        publish(player);
                    }
                    String reward = messages.text(player, "guide.onboarding.reward", Map.of(
                            "step", stepName(player, step),
                            "gold", result.goldGranted().toPlainString(),
                            "merit", result.meritGranted()
                    ));
                    player.sendMessage(Component.text(reward, NamedTextColor.GREEN));
                    player.showTitle(Title.title(
                            Component.text(messages.text(player, "guide.onboarding.title"), NamedTextColor.GOLD),
                            Component.text(stepName(player, step), NamedTextColor.AQUA),
                            Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
                    sendNextObjective(player);
                    VisualMode mode = VisualMode.forPlayer(player);
                    int particles = mode.particles(18);
                    if (particles > 0) player.spawnParticle(Particle.TOTEM_OF_UNDYING,
                            player.getLocation().add(0.0, 1.0, 0.0), particles,
                            0.65, 0.75, 0.65, 0.05);
                    float volume = mode.volume(0.75);
                    if (volume > 0.0F) player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                            SoundCategory.PLAYERS, volume, 1.25F);
                }));
    }

    private void sendNextObjective(Player player) {
        JourneyStep next = progress(player.getUniqueId()).nextStep().orElse(null);
        String command = "/rpghelp";
        String buttonKey = next == null
                ? "guide.onboarding.complete-button" : "guide.onboarding.next-button";
        String tooltipKey = next == null
                ? "guide.onboarding.complete-button-tooltip" : "guide.onboarding.next-button-tooltip";
        Component prefix = next == null
                ? Component.text(messages.text(player, "guide.onboarding.complete-next"), NamedTextColor.GREEN)
                : Component.text(messages.text(player, "guide.onboarding.next", Map.of(
                        "step", stepName(player, next),
                        "where", messages.text(player, "guide.onboarding.steps." + next.id() + ".where")
                )), NamedTextColor.YELLOW);
        if (ResourcePackUiPolicy.customVisuals(true, player)) {
            prefix = HudGlyphs.withIcon(HudGlyphs.OBJECTIVE, prefix);
        }
        Component button = Component.text(" " + messages.text(player, buttonKey), NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(Component.text(messages.text(player, tooltipKey), NamedTextColor.GRAY));
        player.sendMessage(prefix.append(button));
    }

    private void ensureHandbook(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack existing = contents[slot];
            if (isHandbook(existing)) {
                if (updateHandbook(player, existing, currentProgress(player))) {
                    player.getInventory().setItem(slot, existing);
                }
                return;
            }
        }
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(handbook, PersistentDataType.STRING, player.getUniqueId().toString());
        item.setItemMeta(meta);
        updateHandbook(player, item, currentProgress(player));
        int slot = Math.clamp(plugin.getConfig().getInt("onboarding.handbook-slot", 8), 0, 8);
        if (player.getInventory().getItem(slot) == null) player.getInventory().setItem(slot, item);
        else player.getInventory().addItem(item);
    }

    private boolean ready(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player
                && authenticatedPlayers.contains(player.getUniqueId());
    }

    private boolean updateHandbook(Player player, ItemStack item, JourneyProgress progress) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        ItemMeta previous = meta.clone();
        meta.setItemModel(ResourcePackUiPolicy.customVisuals(true, player) ? handbookModel : null);
        JourneyStep next = JourneyUiPolicy.next(progress.orderedSteps(), progress.completedSteps());
        String title = next == null
                ? messages.text(player, "guide.onboarding.handbook-name-complete")
                : messages.text(player, "guide.onboarding.handbook-name-next", Map.of(
                        "step", stepName(player, next)));
        meta.displayName(Component.text(title, next == null ? NamedTextColor.GREEN : NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        java.util.ArrayList<Component> lore = new java.util.ArrayList<>();
        if (next == null) {
            addLore(lore, messages.text(player, "guide.onboarding.handbook-complete"), NamedTextColor.GREEN);
            addLore(lore, messages.text(player, "guide.onboarding.handbook-freeplay"), NamedTextColor.GRAY);
        } else {
            String key = "guide.onboarding.steps." + next.id() + ".";
            addLore(lore, messages.text(player, "guide.onboarding.handbook-current", Map.of(
                    "step", stepName(player, next))), NamedTextColor.YELLOW);
            addLore(lore, localized(player, key + "where"), NamedTextColor.AQUA);
            addLore(lore, localized(player, key + "why"), NamedTextColor.GRAY);
        }
        addLore(lore, messages.text(player, "guide.onboarding.handbook-use"), NamedTextColor.WHITE);
        meta.lore(lore);
        if (meta.equals(previous)) return false;
        item.setItemMeta(meta);
        return true;
    }

    private void addLore(List<Component> lore, String text, NamedTextColor color) {
        for (String line : cn.originalrpg.api.ui.MenuLoreLayout.wrap(text)) {
            lore.add(Component.text(line, color)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }
    }

    private String localized(Player player, String key) {
        return messages.text(player, key);
    }

    private boolean isHandbook(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(handbook, PersistentDataType.STRING);
    }

    private List<ItemStack> allEquipment(Player player) {
        java.util.ArrayList<ItemStack> items = new java.util.ArrayList<>(java.util.Arrays.asList(player.getInventory().getStorageContents()));
        items.addAll(java.util.Arrays.asList(player.getInventory().getArmorContents()));
        items.add(player.getInventory().getItemInOffHand());
        return items;
    }

    private NamespacedKey stepKey(JourneyStep step) {
        return NamespacedKey.fromString("rpgguide:step_" + step.id());
    }

    private NamespacedKey observedKey(JourneyStep step) {
        return NamespacedKey.fromString("rpgguide:observed_" + step.id());
    }

    private boolean observed(Player player, JourneyStep step) {
        NamespacedKey key = observedKey(step);
        return key != null && player.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    String stepName(Player player, JourneyStep step) {
        return messages.text(player, "guide.onboarding.steps." + step.id() + ".name");
    }

    private void publish(Player player) {
        requirePrimaryThread();
        java.util.EnumSet<JourneyStep> completed = java.util.EnumSet.noneOf(JourneyStep.class);
        for (JourneyStep step : STEPS) if (completed(player, step)) completed.add(step);
        JourneyProgress progress = new JourneyProgress(STEPS, completed);
        snapshots.put(player.getUniqueId(), progress);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!isHandbook(item)) continue;
            if (updateHandbook(player, item, progress)) player.getInventory().setItem(slot, item);
        }
    }

    private JourneyProgress currentProgress(Player player) {
        java.util.EnumSet<JourneyStep> completed = java.util.EnumSet.noneOf(JourneyStep.class);
        for (JourneyStep step : STEPS) if (completed(player, step)) completed.add(step);
        return new JourneyProgress(STEPS, completed);
    }

    private void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Journey player data must be read on the primary server thread");
        }
    }
}
