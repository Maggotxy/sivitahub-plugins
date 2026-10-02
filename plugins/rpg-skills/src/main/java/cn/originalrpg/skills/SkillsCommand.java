package cn.originalrpg.skills;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.SkillLearnResult;
import cn.originalrpg.api.model.SkillProgress;
import cn.originalrpg.api.model.SkillUpgradeResult;
import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.EquipmentTraitCatalog;
import cn.originalrpg.api.model.SkillCooldownSnapshot;
import cn.originalrpg.api.model.SkillHudSnapshot;
import cn.originalrpg.api.service.EquipmentService;
import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.SkillService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.SkillHudService;
import cn.originalrpg.api.service.SkillDamageService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.PartyService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.ui.HudGlyphs;
import cn.originalrpg.api.ui.MenuLoreLayout;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LogoutEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class SkillsCommand implements CommandExecutor, TabCompleter, Listener, SkillHudService {
    private static final SkillCooldownSnapshot EMPTY_SKILL_SLOT =
            SkillCooldownSnapshot.empty();
    private final Plugin plugin;
    private final ProfileService profiles;
    private final SkillService skills;
    private final EquipmentService equipment;
    private final ClassBranchService branches;
    private final MessageService messages;
    private final SkillSequenceController sequences;
    private final SkillVisuals visuals;
    private final SkillMechanics mechanics;
    private final SkillDamageService skillDamage;
    private final NamespacedKey dungeonBoss = NamespacedKey.fromString("rpgdungeons:boss");
    private final NamespacedKey skillSlotsKey;
    private final NamespacedKey selectedSlotKey;
    private final NamespacedKey manaKey;
    private final NamespacedKey cooldownsKey;
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> mana = new ConcurrentHashMap<>();
    private final Map<UUID, HudState> hud = new ConcurrentHashMap<>();
    private final Map<UUID, String> hudSignatures = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hudSentAt = new ConcurrentHashMap<>();
    private final Map<UUID, BattleTranceState> battleTrances = new ConcurrentHashMap<>();
    private final AttackerBonusTag attackerBonusTag = new AttackerBonusTag();
    private final Map<UUID, DefenseState> defenseStates = new ConcurrentHashMap<>();
    private final Map<UUID, LineLinkState> lineLinks = new ConcurrentHashMap<>();
    private final Map<UUID, StandardState> standardStates = new ConcurrentHashMap<>();
    private final Map<UUID, FateSealState> fateSeals = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> learnedSkillIds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> learnedRefreshTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Player> learningPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Player> castingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Player> treeLoadingPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Player> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PendingSkillInput> pendingSkillInputs = new ConcurrentHashMap<>();

    SkillsCommand(
            Plugin plugin, ProfileService profiles, SkillService skills,
            ClassBranchService branches, EquipmentService equipment, MessageService messages,
            SkillDamageService skillDamage
    ) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.skills = skills;
        this.equipment = equipment;
        this.branches = branches;
        this.messages = messages;
        this.skillDamage = skillDamage;
        this.sequences = new SkillSequenceController(plugin);
        this.visuals = new SkillVisuals(plugin, sequences);
        this.mechanics = new SkillMechanics(skillDamage, visuals, sequences);
        this.skillSlotsKey = new NamespacedKey(plugin, "skill_slots");
        this.selectedSlotKey = new NamespacedKey(plugin, "selected_skill_slot");
        this.manaKey = new NamespacedKey(plugin, "mana");
        this.cooldownsKey = new NamespacedKey(plugin, "cooldowns");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (!currentSession(player)) return true;
        if (args.length == 0) {
            showTree(player, 0);
            return true;
        }
        // Older optional test clients sent this handshake. Keep it as a
        // silent no-op so those installations do not receive command-usage
        // spam, while the production control path remains entirely server-side.
        if (args[0].equalsIgnoreCase("clientready")) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("hud")) {
            showOverview(player);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cast")) {
            cast(player, args[1].toLowerCase(Locale.ROOT));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("castslot")) {
            try {
                castSlot(player, Integer.parseInt(args[1]) - 1);
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text(messages.text(player, "skill-command.slot-invalid"),
                        NamedTextColor.RED));
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("slot")) {
            try {
                selectSlot(player, Integer.parseInt(args[1]) - 1, true);
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text(messages.text(player, "skill-command.slot-invalid"),
                        NamedTextColor.RED));
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("upgrade")) {
            requestUpgrade(player, args[1].toLowerCase(Locale.ROOT), null, null, -1);
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("learn")) {
            return false;
        }
        String skillId = args[1].toLowerCase(Locale.ROOT);
        requestLearn(player, skillId, null, -1);
        return true;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SkillMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!currentSession(player)) {
            player.closeInventory();
            return;
        }
        String action = menu.actionAt(event.getRawSlot());
        if (action == null) return;
        if (action.startsWith("page:")) {
            showTree(player, Integer.parseInt(action.substring("page:".length())));
            return;
        }
        if (action.startsWith("learn ")) {
            String skillId = action.substring("learn ".length());
            requestLearn(player, skillId, menu, event.getRawSlot());
            return;
        }
        if (action.startsWith("bind ")) {
            bindFromMenu(player, action.substring("bind ".length()), event);
            return;
        }
        if (action.startsWith("skill ")) {
            String[] parts = action.split(" ", 3);
            if (parts.length != 3) return;
            int expectedLevel;
            try {
                expectedLevel = Integer.parseInt(parts[2]);
            } catch (NumberFormatException exception) {
                return;
            }
            if (event.getHotbarButton() >= 0 && event.getHotbarButton() < 4) {
                bindSkill(player, parts[1], event.getHotbarButton(), true);
                refreshTree(player, menu);
            } else if (event.isRightClick()) {
                requestUpgrade(player, parts[1], expectedLevel, menu, event.getRawSlot());
            } else {
                bindSkill(player, parts[1], selectedSlot(player.getUniqueId()), true);
                refreshTree(player, menu);
            }
            return;
        }
        player.closeInventory();
        sync(() -> {
            if (!currentSession(player)) return;
            if (action.startsWith("command:")) player.performCommand(action.substring("command:".length()));
            else player.performCommand("skills " + action);
        });
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SkillMenu) event.setCancelled(true);
    }

    /**
     * Pure-server combat control. F normally swaps hands, but while an RPG
     * weapon is held a single press casts and a double press cycles the skill.
     * Unlike Q, this event is cancellable before any item entity can exist.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSkillControl(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        // Shift+F remains a vanilla hand-swap chord. Only an unmodified F press
        // owns the RPG input, so crouching never casts or changes skill state.
        if (!SkillControlPolicy.ownsSwapHands(
                currentSession(player), player.isSneaking(),
                isRpgWeapon(player.getInventory().getItemInMainHand()))) return;
        event.setCancelled(true);
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        long windowMillis = Math.clamp(plugin.getConfig().getLong(
                "controls.double-press-window-millis", 260L), 180L, 400L);
        PendingSkillInput previous = pendingSkillInputs.remove(playerId);
        if (previous != null) {
            previous.task().cancel();
            if (previous.player() == player
                    && SkillInputPolicy.isDoublePress(previous.startedAtMillis(), now, windowMillis)) {
                cycleSkill(player);
                return;
            }
            // A severely delayed tick must not swallow the earlier single F.
            if (previous.player() == player) castSlot(player, selectedSlot(playerId));
        }
        long token = ThreadLocalRandom.current().nextLong();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingSkillInput active = pendingSkillInputs.get(playerId);
            if (active == null || active.token() != token
                    || active.player() != player || !pendingSkillInputs.remove(playerId, active)
                    || !currentSession(player)) return;
            castSlot(player, selectedSlot(playerId));
        }, SkillInputPolicy.delayTicks(windowMillis));
        pendingSkillInputs.put(playerId, new PendingSkillInput(player, now, token, task));
    }

    private boolean isRpgWeapon(ItemStack item) {
        return equipment.inspect(item)
                .map(snapshot -> snapshot.equipmentType().equalsIgnoreCase("WEAPON"))
                .orElse(false);
    }

    private void bindFromMenu(Player player, String skillId, InventoryClickEvent event) {
        int target = event.getHotbarButton() >= 0 && event.getHotbarButton() < 4
                ? event.getHotbarButton() : selectedSlot(player.getUniqueId());
        bindSkill(player, skillId, target, true);
        if (event.getView().getTopInventory().getHolder() instanceof SkillMenu menu) refreshTree(player, menu);
    }

    private void cast(Player player, String id) {
        cast(player, id, null);
    }

    private void cast(Player player, String id, SkillMenu sourceMenu) {
        ConfigurationSection skill = plugin.getConfig().getConfigurationSection("skills." + id);
        if (skill == null) {
            player.sendMessage(Component.text(messages.text(player, "skill-command.missing"), NamedTextColor.RED));
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!beginOperation(castingPlayers, player)) {
            player.sendActionBar(Component.text(messages.text(player, "skill-command.cast-pending"),
                    NamedTextColor.YELLOW));
            return;
        }
        skills.skill(playerId, id).thenCombine(profiles.get(playerId), SkillAndProfile::new)
                .whenComplete((result, error) -> sync(() -> {
            try {
                if (!currentSession(player)) return;
                if (error != null) {
                    player.sendMessage(Component.text(
                            messages.text(player, "skill-command.load-error"), NamedTextColor.RED));
                    return;
                }
                EquipmentTraits traits = equipmentTraits(player);
                boolean runeActive = traits.activeSkillRunes().contains(id);
                boolean learned = result.skill().isPresent();
                EquipmentBorrowingRules.CastMode castMode = EquipmentBorrowingRules.mode(learned, runeActive);
                if (!learned && !runeActive) {
                    String key = traits.dormantSkillRunes().contains(id)
                            ? "skill-command.borrowed-locked" : "skill-command.not-learned";
                    player.sendMessage(Component.text(messages.text(player, key), NamedTextColor.RED));
                    return;
                }
                SkillProgress progress = result.skill().orElseGet(
                        () -> SkillUseRules.borrowedProgress(id));
                if (result.skill().isPresent()) rememberLearned(playerId, id);
                castLearned(player, id, skill, progress, result.profile(), traits, castMode);
            } finally {
                castingPlayers.remove(playerId, player);
                // Refresh only after the asynchronous profile/skill lookup and the
                // primary-thread cast have both completed. A fixed tick delay can
                // race the database and display stale mana/cooldown values.
                if (player.isOnline() && sourceMenu != null && isViewing(player, sourceMenu)) {
                    refreshTree(player, sourceMenu);
                }
            }
        }));
    }

    private void castLearned(
            Player player, String id, ConfigurationSection skill, SkillProgress progress, PlayerProfile profile,
            EquipmentTraits traits, EquipmentBorrowingRules.CastMode castMode
    ) {
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<EquipmentSnapshot> heldSnapshot = equipment.inspect(held);
        if (equipment.isCustomEquipment(held) && heldSnapshot.isEmpty()) {
            player.sendActionBar(Component.text(messages.text(player, "combat.signature-invalid"), NamedTextColor.RED));
            return;
        }
        if (!SkillUseRules.canCastWithHeldEquipment(heldSnapshot,
                player.hasPermission("rpg.skills.test-bypass"))) {
            player.sendActionBar(Component.text(messages.text(player,
                    "skill-command.weapon-required"), NamedTextColor.YELLOW));
            return;
        }
        if (heldSnapshot.flatMap(EquipmentSnapshot::boundOwner)
                .filter(owner -> !owner.equals(player.getUniqueId())).isPresent()) {
            player.sendActionBar(Component.text(messages.text(player, "combat.owner-mismatch"), NamedTextColor.RED));
            return;
        }
        long now = System.currentTimeMillis();
        long availableAt = cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .getOrDefault(id, 0L);
        if (availableAt > now) {
            player.sendActionBar(Component.text(messages.text(player, "skill-command.cooldown", Map.of(
                    "seconds", (availableAt - now + 999) / 1000)), NamedTextColor.YELLOW));
            showHudFor(player, id, 2500L);
            return;
        }
        int manaCost = EquipmentBorrowingRules.manaCost(
                skill.getInt("mana-cost", skill.getInt("energy-cost", 10)), castMode,
                plugin.getConfig().getDouble("equipment-effects.borrowed-skill.mana-multiplier", 1.5));
        int cap = manaCap(profile);
        int current = Math.min(cap, mana.getOrDefault(player.getUniqueId(), cap));
        if (current < manaCost) {
            player.sendActionBar(Component.text(messages.text(player, "skill-command.mana-insufficient", Map.of(
                    "current", current, "cap", cap)), NamedTextColor.RED));
            return;
        }
        mana.put(player.getUniqueId(), current - manaCost);
        if (!applySkill(player, id, progress, profile, traits, castMode)) {
            mana.put(player.getUniqueId(), current);
            return;
        }
        long leveledBaseCooldown = SkillLevelRules.cooldownMillis(
                skill.getLong("cooldown-seconds", 10L), progress.level(),
                skillCooldownReduction(), skillCooldownFloor());
        long cooldownMillis = EquipmentBorrowingRules.cooldownFromBaseMillis(
                leveledBaseCooldown, castMode,
                plugin.getConfig().getDouble("equipment-effects.borrowed-skill.cooldown-multiplier", 1.5),
                plugin.getConfig().getDouble("equipment-effects.mastered-skill.cooldown-multiplier", 0.85),
                traits.skillHaste());
        cooldowns.get(player.getUniqueId()).put(id, System.currentTimeMillis() + cooldownMillis);
        persistSessionState(player);
        if (castMode == EquipmentBorrowingRules.CastMode.LEARNED) {
            sendCastFeedback(player, id, current - manaCost, cap, cooldownMillis);
        }
        showHudFor(player, id, Math.clamp(plugin.getConfig().getLong(
                "hud.visible-millis", 5000L), 1500L, 15000L));
    }

    private boolean applySkill(
            Player player, String id, SkillProgress progress, PlayerProfile profile,
            EquipmentTraits traits, EquipmentBorrowingRules.CastMode castMode
    ) {
        boolean borrowed = castMode == EquipmentBorrowingRules.CastMode.BORROWED;
        boolean mastered = castMode == EquipmentBorrowingRules.CastMode.MASTERED;
        double powerMultiplier = EquipmentBorrowingRules.powerMultiplier(
                castMode,
                plugin.getConfig().getDouble("equipment-effects.borrowed-skill.power-multiplier", 0.65),
                plugin.getConfig().getDouble("equipment-effects.mastered-skill.power-multiplier", 1.15));
        double durationMultiplier = EquipmentBorrowingRules.durationTicks(
                1000, castMode,
                plugin.getConfig().getDouble("equipment-effects.borrowed-skill.duration-multiplier", 0.65),
                plugin.getConfig().getDouble("equipment-effects.mastered-skill.duration-multiplier", 1.15)) / 1000.0;
        switch (id) {
            case "heal" -> {
                SkillMutationRules.HealPlan plan = SkillMutationRules.heal(progress.variant());
                double equipmentMultiplier = healingMultiplier(profile, traits);
                if (plan.damaging()) {
                    org.bukkit.entity.Monster target = player.getWorld().getNearbyEntitiesByType(
                                    org.bukkit.entity.Monster.class, player.getLocation(), plan.radius()).stream()
                            .min(java.util.Comparator.comparingDouble(monster -> monster.getLocation()
                                    .distanceSquared(player.getLocation())))
                            .orElse(null);
                    if (target == null) {
                        player.sendMessage(Component.text(messages.text(player, "skill-command.no-damage-target"), NamedTextColor.YELLOW));
                        return false;
                    }
                    double damage = plan.power() * equipmentMultiplier * powerMultiplier;
                    skillDamage(player, target, damage, id);
                    visuals.path(player, player.getEyeLocation(), target.getEyeLocation(),
                            org.bukkit.Particle.DAMAGE_INDICATOR,
                            org.bukkit.Color.fromRGB(205, 55, 105), 18);
                    visuals.impact(player, target.getLocation().add(0, 1, 0),
                            org.bukkit.Particle.DAMAGE_INDICATOR, 8);
                } else {
                    Player target = nearbyAllies(player, plan.radius()).stream()
                            .min(java.util.Comparator.comparingDouble(other -> other.getHealth()
                                    / Math.max(1.0, other.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())))
                            .orElse(player);
                    double amount = plan.power() * equipmentMultiplier * powerMultiplier;
                    heal(target, amount);
                    visuals.path(player, player.getEyeLocation(), target.getEyeLocation(),
                            org.bukkit.Particle.HEART,
                            org.bukkit.Color.fromRGB(82, 235, 170), 18);
                    target.sendActionBar(Component.text(messages.text(target, "skill-command.healed", Map.of(
                            "amount", String.format(Locale.ROOT, "%.1f", amount))), NamedTextColor.GREEN));
                }
            }
            case "renewal" -> {
                double pulse = 3.0 * healingMultiplier(profile, traits);
                mechanics.renewalBeacon(player, () -> nearbyAllies(player, 10.0),
                        pulse * 0.55 * powerMultiplier);
            }
            case "sanctuary" -> {
                java.util.Set<Player> allies = nearbyAllies(player, 10.0);
                double pulse = 5.0 * healingMultiplier(profile, traits);
                for (Player ally : allies) {
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 200, 1));
                }
                mechanics.sanctuary(player, () -> nearbyAllies(player, 10.0),
                        pulse * 0.42 * powerMultiplier);
            }
            case "fate-seal" -> {
                java.util.Set<Player> allies = nearbyAllies(player, 12.0);
                long expiresAt = System.currentTimeMillis() + 12_000L;
                allies.forEach(ally -> {
                    fateSeals.put(ally.getUniqueId(), new FateSealState(player.getUniqueId(), expiresAt));
                    visuals.ringAt(player, ally.getLocation(), 0.8,
                            org.bukkit.Particle.ENCHANT,
                            org.bukkit.Color.fromRGB(105, 220, 210), 12);
                });
                // One seal intercepts one meaningful hit; immediate trajectory
                // reversal gives the cast value even before a seal is consumed.
                mechanics.reverseProjectiles(player, 12.0);
            }
            case "war-cry" -> {
                SkillMutationRules.WarCryPlan plan = SkillMutationRules.warCry(progress.variant());
                // An equipment-borrowed war cry is personal. This preserves the
                // Warrior's team identity while still creating a real cross-class tool.
                java.util.Set<Player> allies = plan.partyWide() && !borrowed
                        ? nearbyAllies(player, 12.0) : new java.util.HashSet<>(Set.of(player));
                int duration = Math.max(1, (int) Math.round(plan.durationTicks() * durationMultiplier));
                for (Player ally : allies) {
                    ally.addPotionEffect(new PotionEffect(
                            PotionEffectType.STRENGTH, duration, plan.strengthAmplifier()));
                    if (plan.hasteAmplifier() >= 0 && !borrowed) ally.addPotionEffect(new PotionEffect(
                            PotionEffectType.HASTE, duration, plan.hasteAmplifier()));
                }
                if (plan.resistanceAmplifier() >= 0) player.addPotionEffect(new PotionEffect(
                        PotionEffectType.RESISTANCE, duration, plan.resistanceAmplifier()));
                if (plan.slownessTicks() > 0) player.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS, plan.slownessTicks(), 0));
                if (plan.weaknessTicks() > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (currentSession(player)) {
                        player.addPotionEffect(new PotionEffect(
                                PotionEffectType.WEAKNESS, plan.weaknessTicks(), 0));
                    }
                }, duration);
                mechanics.shockwave(player, 7.0, 0.85, 3.0 * powerMultiplier,
                        id, org.bukkit.Particle.CRIT, org.bukkit.Color.fromRGB(230, 72, 52));
            }
            case "shield-wall" -> {
                org.bukkit.util.Vector facing = player.getEyeLocation().getDirection().setY(0.0);
                if (facing.lengthSquared() < 0.01) facing = new org.bukkit.util.Vector(0.0, 0.0, 1.0);
                defenseStates.put(player.getUniqueId(), new DefenseState(
                        SkillDefenseRules.Mode.SHIELD_WALL, System.currentTimeMillis() + 9_000L,
                        player.getLocation().clone(), facing.normalize()));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 0));
                mechanics.frontalWall(player, 5.5, 0.9);
            }
            case "fortress" -> {
                defenseStates.put(player.getUniqueId(), new DefenseState(
                        SkillDefenseRules.Mode.FORTRESS, System.currentTimeMillis() + 11_000L,
                        player.getLocation().clone(), new org.bukkit.util.Vector()));
                player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 220, 2));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 120, 1));
                mechanics.pullAndTaunt(player, 9.0);
            }
            case "blood-rage" -> {
                double healthCost = Math.max(0.0,
                        plugin.getConfig().getDouble("skill-effects.blood-rage.health-cost", 4.0));
                if (!SkillUseRules.canPayHealthCost(player.getHealth(), healthCost)) {
                    player.sendMessage(Component.text(messages.text(player, "skill-command.health-insufficient", Map.of(
                            "skill", skillName(player, id),
                            "required", String.format(Locale.ROOT, "%.1f", healthCost + 1.0))), NamedTextColor.RED));
                    return false;
                }
                // This is a skill cost, not incoming damage: armor, resistance and
                // custom Defense must not reduce it or turn low-health casts free.
                player.setHealth(SkillUseRules.healthAfterCost(player.getHealth(), healthCost));
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 180, 2));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 180, 1));
                mechanics.shockwave(player, 4.5, 0.48, 5.0 * powerMultiplier,
                        id, org.bukkit.Particle.DAMAGE_INDICATOR,
                        org.bukkit.Color.fromRGB(235, 45, 45));
            }
            case "shadow-step" -> {
                SkillMutationRules.ShadowStepPlan plan = SkillMutationRules.shadowStep(
                        progress.variant(), borrowed, mastered,
                        plugin.getConfig().getInt("equipment-effects.borrowed-skill.duration-ticks", 60),
                        plugin.getConfig().getInt("equipment-effects.mastered-shadow-step.duration-ticks", 140));
                LivingEntity target = aimedPvpOrNearestMonster(player, plan.radius());
                if (target != null) {
                    double damage = plan.markedStrike() ? plan.damage() : Math.max(5.0, plan.damage() * 0.65);
                    mechanics.shadowStep(player, target, damage * powerMultiplier, id);
                    if (plan.markedStrike()) debuff(target, PotionEffectType.WEAKNESS, plan.markTicks(), 0);
                } else if (plan.invisibilityTicks() > 0) {
                    player.addPotionEffect(new PotionEffect(
                            PotionEffectType.INVISIBILITY, plan.invisibilityTicks(), 0));
                } else {
                    return noTarget(player);
                }
            }
            case "venom-blade" -> {
                LivingEntity target = aimedPvpOrNearestMonster(player, 6.0);
                if (target == null) return noTarget(player);
                int stacks = mechanics.venomBlade(player, target, 6.0 * powerMultiplier, id);
                debuff(target, PotionEffectType.POISON, 50 + stacks * 10, 0);
                visuals.path(player, player.getEyeLocation(), target.getEyeLocation(),
                        org.bukkit.Particle.WITCH, org.bukkit.Color.fromRGB(112, 225, 62), 16);
            }
            case "smoke-bomb" -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 160, 0));
                mechanics.smokeBomb(player, 8.0);
            }
            case "toxic-burst" -> {
                List<org.bukkit.entity.Monster> targets = List.copyOf(player.getWorld()
                        .getNearbyEntitiesByType(org.bukkit.entity.Monster.class, player.getLocation(), 7.0));
                if (targets.isEmpty()) return noTarget(player);
                mechanics.toxicBurst(player, 7.0, 4.5 * powerMultiplier, id);
            }
            case "fireball" -> {
                SkillMutationRules.FireballPlan plan = SkillMutationRules.fireball(progress.variant());
                if (plan.projectile()) {
                    SmallFireball fireball = player.launchProjectile(SmallFireball.class);
                    fireball.setIsIncendiary(false);
                    fireball.setYield(0.0F);
                    tagSkillProjectile(fireball, id, powerMultiplier);
                    mechanics.trailProjectile(player, fireball, org.bukkit.Particle.FLAME,
                            org.bukkit.Color.fromRGB(255, 102, 35), 50);
                } else {
                    List<org.bukkit.entity.Monster> targets = player.getWorld().getNearbyEntitiesByType(
                                    org.bukkit.entity.Monster.class, player.getLocation(), plan.radius()).stream()
                            .sorted(java.util.Comparator.comparingDouble(monster -> monster.getLocation()
                                    .distanceSquared(player.getLocation())))
                            .limit(plan.maximumTargets()).toList();
                    if (targets.isEmpty()) return noTarget(player);
                    for (org.bukkit.entity.Monster target : targets) {
                        skillDamage(player, target, plan.damage() * powerMultiplier, id);
                        debuff(target, PotionEffectType.SLOWNESS, plan.slownessTicks(), 0);
                        visuals.impact(player, target.getLocation().add(0, 1, 0),
                                org.bukkit.Particle.ENCHANT, 4);
                    }
                }
            }
            case "frost-nova" -> {
                List<LivingEntity> targets = nearbyControlTargets(player, 7.0);
                if (targets.isEmpty()) return noTarget(player);
                targets.forEach(monster -> {
                    skillDamage(player, monster, 6.0, id);
                });
                mechanics.freeze(player, targets, monster -> debuffDuration(monster, 70));
                visuals.frostNova(player, targets, 7.0);
            }
            case "meteor" -> {
                mechanics.meteor(player, 18.0 * powerMultiplier, id);
            }
            case "mana-surge" -> {
                int cap = manaCap(profile);
                mana.merge(player.getUniqueId(), 40, (current, restored) -> Math.min(cap, current + restored));
                Map<String, Long> active = cooldowns.get(player.getUniqueId());
                if (active != null) {
                    long now = System.currentTimeMillis();
                    active.replaceAll((skillId, readyAt) -> readyAt <= now ? readyAt
                            : now + Math.round((readyAt - now) * 0.80));
                }
                mechanics.reverseProjectiles(player, 7.0);
                mechanics.shockwave(player, 5.5, 0.3, 0.0, id,
                        org.bukkit.Particle.ENCHANT, org.bukkit.Color.fromRGB(92, 154, 255));
            }
            case "power-shot" -> {
                SkillMutationRules.PowerShotPlan plan = SkillMutationRules.powerShot(progress.variant());
                org.bukkit.util.Vector direction = player.getEyeLocation().getDirection().normalize();
                double center = (plan.arrows() - 1) / 2.0;
                for (int index = 0; index < plan.arrows(); index++) {
                    Arrow arrow = player.launchProjectile(Arrow.class);
                    double spread = (index - center) * plan.spreadRadians();
                    if (plan.arrows() > 1 || spread != 0.0) {
                        arrow.setVelocity(direction.clone().rotateAroundY(spread).multiply(2.8));
                    }
                    arrow.setDamage(arrow.getDamage() * plan.damageMultiplier());
                    arrow.setCritical(plan.critical());
                    tagSkillProjectile(arrow, id, powerMultiplier);
                    mechanics.trailProjectile(player, arrow, org.bukkit.Particle.CRIT,
                            org.bukkit.Color.fromRGB(241, 210, 92), 45);
                }
            }
            case "volley" -> {
                mechanics.volleyRain(player, 1.35 * powerMultiplier, id);
            }
            case "piercing-shot" -> {
                mechanics.piercingRay(player, 34.0, 0.8, 4,
                        14.0 * powerMultiplier, id, org.bukkit.Color.fromRGB(96, 232, 205));
            }
            case "beast-call" -> {
                mechanics.beastSpirits(player, 12.0 * durationMultiplier,
                        4.0 * powerMultiplier, id, 2);
            }
            case "improvisation" -> {
                SkillMutationRules.ImprovisationPlan plan = SkillMutationRules.improvisation(progress.variant());
                int roll = ThreadLocalRandom.current().nextInt(4);
                if (roll == 0) mechanics.shockwave(player, 6.0, 0.75,
                        8.0 * powerMultiplier, id, org.bukkit.Particle.CRIT,
                        org.bukkit.Color.fromRGB(245, 198, 91));
                else if (roll == 1) mechanics.healingPulses(player, () -> Set.of(player),
                        3.0, 3, 8L, 3.0 * powerMultiplier,
                        org.bukkit.Color.fromRGB(110, 235, 172));
                else if (roll == 2) mechanics.reverseProjectiles(player, 10.0);
                else mechanics.charge(player, 6.0 * powerMultiplier, id,
                            org.bukkit.Color.fromRGB(204, 146, 255));
                if (plan.drawback()) {
                    PotionEffectType drawback = ThreadLocalRandom.current().nextBoolean()
                            ? PotionEffectType.WEAKNESS : PotionEffectType.SLOWNESS;
                    player.addPotionEffect(new PotionEffect(drawback, plan.drawbackTicks(), 0));
                }
            }
            case "second-wind" -> {
                heal(player, 6.0);
                player.getActivePotionEffects().stream()
                        .filter(effect -> Set.of(PotionEffectType.POISON, PotionEffectType.WITHER,
                                PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS,
                                PotionEffectType.BLINDNESS, PotionEffectType.DARKNESS)
                                .contains(effect.getType()))
                        .map(PotionEffect::getType).toList().forEach(player::removePotionEffect);
                mechanics.shockwave(player, 4.0, 0.35, 0.0, id,
                        org.bukkit.Particle.GUST, org.bukkit.Color.fromRGB(132, 225, 195));
            }
            case "battle-trance" -> {
                battleTrances.put(player.getUniqueId(), new BattleTranceState(
                        System.currentTimeMillis() + 11_000L, 0L, 0));
                mechanics.shockwave(player, 5.0, 0.45, 4.0 * powerMultiplier, id,
                        org.bukkit.Particle.CRIT, org.bukkit.Color.fromRGB(245, 112, 76));
            }
            case "arcane-edge" -> {
                mechanics.piercingRay(player, 9.0, 1.0, 3,
                        14.0 * powerMultiplier, id, org.bukkit.Color.fromRGB(164, 100, 245));
            }
            case "life-thread" -> {
                double amount = 3.0 * healingMultiplier(profile, traits) * powerMultiplier;
                mechanics.lifeThread(player, () -> nearbyAllies(player, 12.0), amount);
            }
            case "omen-reversal" -> {
                LivingEntity target = aimedPvpOrNearestMonster(player, 12.0);
                if (target == null) return noTarget(player);
                skillDamage(player, target, 16.0 * powerMultiplier, id);
                debuff(target, PotionEffectType.WEAKNESS, 200, 1);
                mechanics.reverseProjectiles(player, 12.0);
                visuals.path(player, target.getEyeLocation(), player.getEyeLocation(),
                        org.bukkit.Particle.REVERSE_PORTAL,
                        org.bukkit.Color.fromRGB(90, 225, 205), 22);
            }
            case "soul-resonance" -> {
                double amount = 4.0 * healingMultiplier(profile, traits) * powerMultiplier;
                mechanics.soulResonance(player, () -> nearbyAllies(player, 14.0),
                        amount, 3.0 * powerMultiplier, id);
            }
            case "rallying-standard" -> {
                org.bukkit.Location standard = player.getLocation().clone();
                long expiresAt = System.currentTimeMillis() + 6_000L;
                nearbyAllies(player, 8.0).stream()
                        .filter(ally -> ally.getLocation().distanceSquared(standard) <= 64.0)
                        .forEach(ally -> {
                            standardStates.put(ally.getUniqueId(), new StandardState(
                                    player.getUniqueId(), expiresAt, standard, 8.0));
                            ally.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 120, 0));
                        });
                mechanics.rallyingStandard(player, () -> nearbyAllies(player, 8.0), 8.0, 6);
            }
            case "ruin-charge" -> {
                double healthCost = 6.0;
                if (!SkillUseRules.canPayHealthCost(player.getHealth(), healthCost)) {
                    player.sendMessage(Component.text(messages.text(player, "skill-command.health-insufficient", Map.of(
                            "skill", skillName(player, id),
                            "required", String.format(Locale.ROOT, "%.1f", healthCost + 1.0))), NamedTextColor.RED));
                    return false;
                }
                player.setHealth(SkillUseRules.healthAfterCost(player.getHealth(), healthCost));
                mechanics.charge(player, 16.0 * powerMultiplier, id,
                        org.bukkit.Color.fromRGB(232, 72, 48));
            }
            case "unbroken-line" -> {
                long expiresAt = System.currentTimeMillis() + 13_000L;
                for (Player ally : nearbyAllies(player, 14.0)) {
                    lineLinks.put(ally.getUniqueId(), new LineLinkState(
                            player.getUniqueId(), expiresAt));
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 0));
                }
                mechanics.frontalWall(player, 8.0, 1.25);
            }
            case "void-cut" -> {
                LivingEntity target = aimedPvpOrNearestMonster(player, 10.0);
                if (target == null) return noTarget(player);
                mechanics.shadowStep(player, target, 18.0 * powerMultiplier, id);
                debuff(target, PotionEffectType.WEAKNESS, 140, 1);
            }
            case "plague-bloom" -> {
                List<org.bukkit.entity.Monster> targets = List.copyOf(player.getWorld()
                        .getNearbyEntitiesByType(org.bukkit.entity.Monster.class, player.getLocation(), 9.0));
                if (targets.isEmpty()) return noTarget(player);
                mechanics.plagueBloom(player, player.getLocation().clone(), 9.0, 7,
                        3.0 * powerMultiplier, id);
            }
            case "vanished-horizon" -> {
                LivingEntity aimedPlayer = aimedPvpOpponent(player, 12.0);
                List<? extends LivingEntity> targets = aimedPlayer == null
                        ? player.getWorld().getNearbyEntitiesByType(
                                        org.bukkit.entity.Monster.class, player.getLocation(), 12.0).stream()
                                .sorted(java.util.Comparator.comparingDouble(monster -> monster.getLocation()
                                        .distanceSquared(player.getLocation())))
                                .limit(3).toList()
                        : List.of(aimedPlayer);
                if (targets.isEmpty()) return noTarget(player);
                mechanics.blinkChain(player, targets, 20.0 * powerMultiplier, id);
            }
            case "ash-domain" -> {
                List<org.bukkit.entity.Monster> targets = List.copyOf(player.getWorld()
                        .getNearbyEntitiesByType(org.bukkit.entity.Monster.class, player.getLocation(), 10.0));
                if (targets.isEmpty()) return noTarget(player);
                mechanics.damageZone(player, player.getLocation().clone(), 10.0, 6, 16L,
                        4.0 * powerMultiplier, id, org.bukkit.Particle.ASH,
                        org.bukkit.Color.fromRGB(215, 92, 45), true);
            }
            case "time-lock" -> {
                List<LivingEntity> targets = nearbyControlTargets(player, 10.0);
                if (targets.isEmpty()) return noTarget(player);
                targets.forEach(monster -> skillDamage(player, monster, 8.0 * powerMultiplier, id));
                mechanics.timeLock(player, targets, monster -> debuffDuration(monster, 80), 10.0);
            }
            case "axiomatic-break" -> {
                List<org.bukkit.entity.Monster> targets = player.getWorld().getNearbyEntitiesByType(
                                org.bukkit.entity.Monster.class, player.getLocation(), 14.0).stream()
                        .sorted(java.util.Comparator.comparingDouble(monster -> monster.getLocation()
                                .distanceSquared(player.getLocation())))
                        .limit(3).toList();
                if (targets.isEmpty()) return noTarget(player);
                targets.forEach(monster -> {
                    skillDamage(player, monster, 24.0 * powerMultiplier, id);
                    visuals.path(player, player.getEyeLocation(), monster.getEyeLocation(),
                            org.bukkit.Particle.END_ROD,
                            org.bukkit.Color.fromRGB(196, 132, 255), 24);
                });
                mechanics.shockwave(player, 3.0, 0.25, 0.0, id,
                        org.bukkit.Particle.REVERSE_PORTAL,
                        org.bukkit.Color.fromRGB(178, 106, 245));
                int cap = manaCap(profile);
                mana.merge(player.getUniqueId(), 30, (current, restored) -> Math.min(cap, current + restored));
            }
            case "gale-step" -> {
                org.bukkit.Location origin = player.getLocation().clone().add(0.0, 0.8, 0.0);
                org.bukkit.util.Vector launch = player.getEyeLocation().getDirection().normalize()
                        .multiply(1.15).setY(0.55);
                player.setVelocity(launch);
                visuals.path(player, origin, origin.clone().add(launch.clone().multiply(5.0)),
                        org.bukkit.Particle.GUST, org.bukkit.Color.fromRGB(135, 232, 218), 22);
                org.bukkit.util.Vector direction = player.getEyeLocation().getDirection().normalize();
                for (int index = -1; index <= 1; index++) {
                    Arrow arrow = player.launchProjectile(Arrow.class);
                    arrow.setVelocity(direction.clone().rotateAroundY(index * 0.04).multiply(3.0));
                    arrow.setDamage(arrow.getDamage() * 1.8);
                    tagSkillProjectile(arrow, id, powerMultiplier);
                    mechanics.trailProjectile(player, arrow, org.bukkit.Particle.GUST,
                            org.bukkit.Color.fromRGB(115, 225, 208), 38);
                }
            }
            case "pack-hunt" -> {
                player.getWorld().getNearbyEntitiesByType(org.bukkit.entity.Monster.class, player.getLocation(), 14.0)
                        .stream().limit(8).forEach(monster -> visuals.ringAt(player,
                                monster.getLocation(), 0.9, org.bukkit.Particle.SOUL,
                                org.bukkit.Color.fromRGB(92, 214, 167), 12));
                mechanics.beastSpirits(player, 14.0 * durationMultiplier,
                        5.0 * powerMultiplier, id, 3);
                for (Player ally : nearbyAllies(player, 12.0)) {
                    ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 240, 1));
                }
            }
            case "horizon-arrow" -> {
                Arrow arrow = player.launchProjectile(Arrow.class);
                arrow.setVelocity(arrow.getVelocity().multiply(1.45));
                arrow.setDamage(arrow.getDamage() * 5.0);
                arrow.setPierceLevel(5);
                arrow.setCritical(true);
                tagSkillProjectile(arrow, id, powerMultiplier);
                mechanics.trailProjectile(player, arrow, org.bukkit.Particle.END_ROD,
                        org.bukkit.Color.fromRGB(100, 236, 215), 70);
            }
            case "adaptive-arsenal" -> {
                Material held = player.getInventory().getItemInMainHand().getType();
                if (held.name().endsWith("_SWORD") || held.name().endsWith("_AXE")) {
                    mechanics.charge(player, 12.0 * powerMultiplier, id,
                            org.bukkit.Color.fromRGB(242, 166, 76));
                } else if (held == Material.BOW || held == Material.CROSSBOW || held == Material.TRIDENT) {
                    mechanics.piercingRay(player, 28.0, 0.7, 3,
                            11.0 * powerMultiplier, id,
                            org.bukkit.Color.fromRGB(115, 215, 190));
                } else {
                    mechanics.shockwave(player, 6.0, 0.8, 7.0 * powerMultiplier, id,
                            org.bukkit.Particle.ENCHANT,
                            org.bukkit.Color.fromRGB(192, 132, 238));
                }
            }
            case "rune-reversal" -> {
                LivingEntity target = aimedPvpOrNearestMonster(player, 9.0);
                if (target == null) return noTarget(player);
                skillDamage(player, target, 18.0 * powerMultiplier, id);
                player.getActivePotionEffects().stream()
                        .filter(effect -> Set.of(PotionEffectType.POISON, PotionEffectType.WITHER,
                                PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS)
                                .contains(effect.getType()))
                        .findFirst().ifPresent(effect -> {
                            player.removePotionEffect(effect.getType());
                            target.addPotionEffect(new PotionEffect(effect.getType(),
                                    Math.min(effect.getDuration(), 160), effect.getAmplifier()));
                        });
                mechanics.reverseProjectiles(player, 9.0);
                visuals.path(player, player.getEyeLocation(), target.getEyeLocation(),
                        org.bukkit.Particle.REVERSE_PORTAL,
                        org.bukkit.Color.fromRGB(125, 225, 195), 20);
                int cap = manaCap(profile);
                mana.merge(player.getUniqueId(), 20, (current, restored) -> Math.min(cap, current + restored));
            }
            case "open-form" -> {
                mechanics.shockwave(player, 8.0, 1.1, 12.0 * powerMultiplier, id,
                        org.bukkit.Particle.ENCHANT,
                        org.bukkit.Color.fromRGB(225, 178, 94));
                mechanics.reverseProjectiles(player, 8.0);
            }
            default -> {
                player.sendMessage(Component.text(messages.text(player, "skill-command.unconfigured"), NamedTextColor.RED));
                return false;
            }
        }
        String visualId = SkillMutationRules.visualId(id, progress.variant());
        if (borrowed) visualId += "-borrowed";
        else if (mastered) visualId += "-mastered";
        visuals.play(player, visualId);
        if (borrowed) {
            int strainTicks = EquipmentBorrowingRules.strainTicks(plugin.getConfig().getInt(
                    "equipment-effects.borrowed-skill.strain-ticks", 60));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, strainTicks, 0));
            visuals.ringAt(player, player.getLocation(), 1.2, org.bukkit.Particle.WITCH,
                    org.bukkit.Color.fromRGB(190, 95, 220), 16);
            player.sendActionBar(Component.text(messages.text(player, "skill-command.borrowed", Map.of(
                    "skill", skillName(player, id), "seconds", (strainTicks + 19) / 20)),
                    NamedTextColor.LIGHT_PURPLE));
        } else if (mastered) {
            player.sendActionBar(Component.text(messages.text(player, "skill-command.mastered", Map.of(
                    "skill", skillName(player, id))), NamedTextColor.GOLD));
        }
        return true;
    }

    private org.bukkit.entity.Monster nearestMonster(Player player, double radius) {
        return player.getWorld().getNearbyEntitiesByType(
                        org.bukkit.entity.Monster.class, player.getLocation(), radius).stream()
                .min(java.util.Comparator.comparingDouble(monster -> monster.getLocation()
                        .distanceSquared(player.getLocation())))
                .orElse(null);
    }

    /** PVP requires an actual crosshair hit; PVE keeps forgiving nearest-monster targeting. */
    private LivingEntity aimedPvpOrNearestMonster(Player player, double radius) {
        LivingEntity aimed = aimedPvpOpponent(player, radius);
        return aimed == null ? nearestMonster(player, radius) : aimed;
    }

    private Player aimedPvpOpponent(Player player, double radius) {
        if (!worldPvpEnabled(player)) return null;
        PartyService parties = partyService();
        org.bukkit.util.RayTraceResult result = player.getWorld().rayTrace(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), radius,
                org.bukkit.FluidCollisionMode.NEVER, true, 0.65,
                entity -> entity instanceof Player candidate
                        && !candidate.equals(player)
                        && !candidate.isDead()
                        && !SkillAllyRules.canReceive(
                                player.getUniqueId(), candidate.getUniqueId(), parties));
        return result != null && result.getHitEntity() instanceof Player hit ? hit : null;
    }

    /**
     * 攻方增益（rallying-standard 光环 + battle-trance 连击层数）的状态推进。
     *
     * <p>G-105 修复要点：本监听器**不再**写 {@code event.setDamage}。原先它挂在 HIGH，
     * 在总线之前就把 {@code base * 1.12 * (1 + stacks * 0.06)} 塞回同一事件，随后
     * rpg-combat {@code ExecuteListener.onDamage}（HIGHEST）又对这笔伤害乘
     * {@code attackMultiplier} 与 {@code targetMultiplier}，同一个增益被结算两次。
     *
     * <p>现在这里只做两件事：推进连击层数、播放命中表现。增益本身由
     * {@link #settleAttackerBonus(EntityDamageByEntityEvent)} 在 HIGHEST 单点写入，
     * 顺序确定为「先减伤（G-001）→ 再乘攻击倍率 → 最后乘攻方增益」。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBattleTranceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity target)) return;
        StandardState standard = standardStates.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        boolean standardActive = false;
        if (standard != null) {
            if (standard.expiresAt() <= now || !player.getWorld().equals(standard.origin().getWorld())
                    || player.getLocation().distanceSquared(standard.origin()) > standard.radius() * standard.radius()) {
                standardStates.remove(player.getUniqueId(), standard);
            } else {
                standardActive = true;
                visuals.impact(player, target.getLocation().add(0.0, target.getHeight() * 0.55, 0.0),
                        org.bukkit.Particle.WAX_ON, 2);
            }
        }
        BattleTranceState state = battleTrances.get(player.getUniqueId());
        int stacks = 0;
        if (state != null) {
            if (state.expiresAt() <= now) {
                battleTrances.remove(player.getUniqueId(), state);
            } else {
                stacks = AttackerBonusRules.nextStacks(now, state.lastHitAt(), state.stacks());
                battleTrances.put(player.getUniqueId(), new BattleTranceState(state.expiresAt(), now, stacks));
                visuals.impact(player, target.getLocation().add(0.0, target.getHeight() * 0.55, 0.0),
                        org.bukkit.Particle.CRIT, 2 + stacks);
                if (stacks == AttackerBonusRules.TRANCE_MAX_STACKS) {
                    org.bukkit.util.Vector launch = target.getLocation().toVector()
                            .subtract(player.getLocation().toVector());
                    if (launch.lengthSquared() < 0.01) launch = new org.bukkit.util.Vector(0.0, 0.0, 1.0);
                    double force = isManagedBoss(target) ? 0.24 : target instanceof Player ? 0.40 : 0.70;
                    target.setVelocity(launch.normalize().multiply(force)
                            .setY(isManagedBoss(target) ? 0.10 : target instanceof Player ? 0.20 : 0.35));
                }
            }
        }
    }

    /**
     * 攻方增益的唯一写入口（G-105）：在总线结算完减伤与攻击倍率之后，把
     * {@link AttackerBonusRules#bonus} 乘一次。幂等守卫保证同一事件只生效一次。
     *
     * <p>挂在 HIGHEST 而不是 HIGH，是为了让增益作用于**已被减伤与倍率处理过**的伤害。
     * 乘法可交换，所以即便 {@code ExecuteListener.onDamage} 与本方法同为 HIGHEST、
     * 排布顺序不确定，乘积仍一致；G-105 的病根不是顺序，而是同一个 bonus 被结算了两次。
     * 这里只结算一次，并由 {@link AttackerBonusTag} 拦下同一 tick 的重复派发。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void settleAttackerBonus(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (standardStates.get(player.getUniqueId()) == null && battleTrances.get(player.getUniqueId()) == null) return;
        settleAttackerBonus(event, standardBonusActive(player), currentTranceStacks(player));
    }

    private boolean standardBonusActive(Player player) {
        StandardState standard = standardStates.get(player.getUniqueId());
        if (standard == null) return false;
        long now = System.currentTimeMillis();
        if (standard.expiresAt() <= now || !player.getWorld().equals(standard.origin().getWorld())
                || player.getLocation().distanceSquared(standard.origin()) > standard.radius() * standard.radius()) {
            standardStates.remove(player.getUniqueId(), standard);
            return false;
        }
        return true;
    }

    private int currentTranceStacks(Player player) {
        BattleTranceState state = battleTrances.get(player.getUniqueId());
        if (state == null) return 0;
        if (state.expiresAt() <= System.currentTimeMillis()) {
            battleTrances.remove(player.getUniqueId(), state);
            return 0;
        }
        return state.stacks();
    }

    /**
     * 测试缝：按 gameplay 的同一形状装载攻方增益状态（光环归属者 + 连击层数）。
     * 只改状态，不改伤害；行为仍由 {@link #settleAttackerBonus(EntityDamageByEntityEvent)} 决定。
     */
    void armAttackerBonusForTest(UUID playerId, UUID standardOwner, long standardExpiresAt,
                                 org.bukkit.Location standardOrigin, long tranceExpiresAt, int stacks) {
        if (standardOwner != null) {
            standardStates.put(playerId, new StandardState(standardOwner, standardExpiresAt, standardOrigin, 8.0));
        }
        if (tranceExpiresAt > 0L) {
            battleTrances.put(playerId, new BattleTranceState(tranceExpiresAt, 0L, stacks));
        }
    }

    private void settleAttackerBonus(EntityDamageByEntityEvent event, boolean standardActive, int tranceStacks) {
        double bonus = AttackerBonusRules.bonus(standardActive, tranceStacks);
        if (bonus <= 1.0) return;
        if (attackerBonusTag.alreadyApplied(event, plugin.getServer().getCurrentTick())) return;
        attackerBonusTag.mark(event, plugin.getServer().getCurrentTick());
        event.setDamage(AttackerBonusRules.apply(event.getDamage(), bonus));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWarriorDefense(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;
        LivingEntity source = damageSource(event);
        boolean pvp = source instanceof Player;
        long now = System.currentTimeMillis();
        double original = event.getDamage();
        double reduction = 0.0;
        SkillDefenseRules.Mode visualMode = null;

        FateSealState seal = fateSeals.get(target.getUniqueId());
        if (seal != null) {
            if (seal.expiresAt() <= now) {
                fateSeals.remove(target.getUniqueId(), seal);
            } else if (fateSeals.remove(target.getUniqueId(), seal)) {
                reduction = pvp ? 0.25 : 0.50;
                if (event.getDamager() instanceof Projectile projectile) {
                    projectile.setVelocity(projectile.getVelocity().multiply(-1.05));
                    projectile.setShooter(target);
                }
                visuals.ringAt(target, target.getLocation(), 1.15,
                        org.bukkit.Particle.REVERSE_PORTAL,
                        org.bukkit.Color.fromRGB(105, 220, 210), 18);
            }
        }

        DefenseState defense = defenseStates.get(target.getUniqueId());
        if (defense != null) {
            if (defense.expiresAt() <= now) {
                defenseStates.remove(target.getUniqueId(), defense);
            } else if (defense.mode() == SkillDefenseRules.Mode.FORTRESS) {
                if (target.getWorld().equals(defense.origin().getWorld())
                        && target.getLocation().distanceSquared(defense.origin()) <= 4.0 * 4.0) {
                    reduction = SkillDefenseRules.reduction(defense.mode(), pvp);
                    visualMode = defense.mode();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (target.isOnline()) target.setVelocity(new org.bukkit.util.Vector());
                    });
                }
            } else if (source != null && source.getWorld().equals(target.getWorld())) {
                org.bukkit.util.Vector toSource = source.getLocation().toVector()
                        .subtract(target.getLocation().toVector()).setY(0.0);
                if (toSource.lengthSquared() > 0.01
                        && toSource.normalize().dot(defense.facing()) > 0.15) {
                    reduction = SkillDefenseRules.reduction(defense.mode(), pvp);
                    visualMode = defense.mode();
                    if (event.getDamager() instanceof Projectile projectile) {
                        projectile.setVelocity(projectile.getVelocity().multiply(-0.85));
                        projectile.setShooter(target);
                    } else {
                        org.bukkit.util.Vector push = source.getLocation().toVector()
                                .subtract(target.getLocation().toVector());
                        if (push.lengthSquared() > 0.01) {
                            double force = isManagedBoss(source) ? 0.16 : pvp ? 0.25 : 0.55;
                            source.setVelocity(push.normalize().multiply(force)
                                    .setY(isManagedBoss(source) ? 0.06 : 0.18));
                        }
                    }
                }
            }
        }

        LineLinkState link = lineLinks.get(target.getUniqueId());
        if (link != null) {
            Player guardian = Bukkit.getPlayer(link.guardian());
            if (link.expiresAt() <= now || guardian == null || !guardian.isOnline()
                    || !guardian.getWorld().equals(target.getWorld())
                    || guardian.getLocation().distanceSquared(target.getLocation()) > 14.0 * 14.0) {
                lineLinks.remove(target.getUniqueId(), link);
            } else {
                double lineReduction = SkillDefenseRules.reduction(
                        SkillDefenseRules.Mode.UNBROKEN_LINE, pvp);
                reduction = Math.max(reduction, lineReduction);
                if (!guardian.equals(target)) {
                    double shared = Math.min(Math.max(0.0, guardian.getHealth() - 1.0),
                            original * lineReduction * 0.5);
                    if (shared > 0.0) guardian.setHealth(guardian.getHealth() - shared);
                    visuals.path(guardian, guardian.getEyeLocation(), target.getEyeLocation(),
                            org.bukkit.Particle.WAX_ON,
                            org.bukkit.Color.fromRGB(225, 155, 75), 12);
                }
                if (visualMode == null) visualMode = SkillDefenseRules.Mode.UNBROKEN_LINE;
            }
        }

        if (reduction <= 0.0) return;
        event.setDamage(original * (1.0 - reduction));
        visuals.impact(target, target.getLocation().add(0.0, 1.0, 0.0),
                visualMode == SkillDefenseRules.Mode.SHIELD_WALL
                        ? org.bukkit.Particle.WAX_OFF : org.bukkit.Particle.WAX_ON, 4);
    }

    private LivingEntity damageSource(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity living) return living;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter) return shooter;
        return null;
    }

    private java.util.Set<Player> nearbyAllies(Player caster, double radius) {
        PartyService parties = partyService();
        java.util.Set<Player> result = new java.util.HashSet<>();
        result.add(caster);
        caster.getWorld().getNearbyPlayers(caster.getLocation(), radius).stream()
                .filter(candidate -> SkillAllyRules.canReceive(
                        caster.getUniqueId(), candidate.getUniqueId(), parties))
                .forEach(result::add);
        return result;
    }

    private List<LivingEntity> nearbyControlTargets(Player caster, double radius) {
        PartyService parties = partyService();
        List<LivingEntity> result = new ArrayList<>(caster.getWorld().getNearbyEntitiesByType(
                org.bukkit.entity.Monster.class, caster.getLocation(), radius));
        if (worldPvpEnabled(caster) && plugin.getConfig().getBoolean(
                "skill-effects.pvp-controls-enabled", true)) {
            caster.getWorld().getNearbyPlayers(caster.getLocation(), radius).stream()
                    .filter(candidate -> !candidate.equals(caster))
                    .filter(candidate -> !SkillAllyRules.canReceive(
                            caster.getUniqueId(), candidate.getUniqueId(), parties))
                    .forEach(result::add);
        }
        return result.stream().filter(LivingEntity::isValid)
                .filter(caster::hasLineOfSight)
                .sorted(Comparator.comparingDouble(target -> target.getLocation()
                        .distanceSquared(caster.getLocation())))
                .limit(16).toList();
    }

    private PartyService partyService() {
        return OptionalServices.enabled(plugin, PartyService.class);
    }

    /** Paper 26.2 still exposes the world rule only through the legacy name. */
    @SuppressWarnings("deprecation")
    private boolean worldPvpEnabled(Player caster) {
        return caster.getWorld().getPVP();
    }

    private boolean noTarget(Player player) {
        player.sendMessage(Component.text(messages.text(player, "skill-command.no-damage-target"), NamedTextColor.YELLOW));
        return false;
    }

    private void debuff(LivingEntity target, PotionEffectType type, int durationTicks, int amplifier) {
        SkillCrowdControlRules.EffectPlan plan = SkillCrowdControlRules.plan(
                isManagedBoss(target), target instanceof Player, durationTicks, amplifier);
        target.addPotionEffect(new PotionEffect(type, plan.durationTicks(), plan.amplifier()));
    }

    private int debuffDuration(LivingEntity target, int durationTicks) {
        return SkillCrowdControlRules.plan(
                isManagedBoss(target), target instanceof Player, durationTicks, 0).durationTicks();
    }

    private boolean isManagedBoss(LivingEntity target) {
        return target instanceof org.bukkit.entity.Boss
                || dungeonBoss != null && target.getPersistentDataContainer()
                        .has(dungeonBoss, PersistentDataType.BYTE);
    }

    private void skillDamage(Player caster, LivingEntity target, double amount, String skillId) {
        skillDamage.damage(caster, target, amount, skillId);
    }

    private void tagSkillProjectile(Projectile projectile, String skillId) {
        tagSkillProjectile(projectile, skillId, 1.0);
    }

    private void tagSkillProjectile(Projectile projectile, String skillId, double powerMultiplier) {
        skillDamage.tagProjectile(projectile, skillId, powerMultiplier);
    }

    private double healingMultiplier(PlayerProfile profile, EquipmentTraits traits) {
        return SkillUseRules.healingMultiplier(
                profile.attribute(CoreAttribute.INTELLIGENCE),
                plugin.getConfig().getDouble("skill-effects.healing.per-intelligence", 0.01),
                plugin.getConfig().getDouble("skill-effects.healing.maximum-attribute-multiplier", 2.0),
                traits.healing(),
                traits.healingBoost(),
                plugin.getConfig().getDouble("equipment-effects.healing-boost.multiplier", 1.35));
    }

    private static void heal(Player target, double amount) {
        org.bukkit.attribute.AttributeInstance maximum = target.getAttribute(
                org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (maximum == null || target.isDead() || amount <= 0.0) return;
        target.setHealth(Math.min(maximum.getValue(), target.getHealth() + amount));
    }

    private EquipmentTraits equipmentTraits(Player player) {
        // On-demand only: exactly main hand, off hand, and four armor slots.
        // No rune owns a scheduler or scans loose inventory/entity contents;
        // EquipmentService also caches authenticated immutable snapshots.
        java.util.Set<String> activeSkillRunes = new java.util.HashSet<>();
        java.util.Set<String> dormantSkillRunes = new java.util.HashSet<>();
        boolean healingBoost = false;
        int skillHaste = 0;
        int healing = 0;
        java.util.ArrayList<ItemStack> equipped = new java.util.ArrayList<>();
        equipped.add(player.getInventory().getItemInMainHand());
        equipped.add(player.getInventory().getItemInOffHand());
        equipped.addAll(java.util.Arrays.asList(player.getInventory().getArmorContents()));
        for (ItemStack item : equipped) {
            java.util.Optional<EquipmentSnapshot> inspected = equipment.inspect(item);
            if (inspected.isEmpty()) continue;
            EquipmentSnapshot snapshot = inspected.get();
            if (snapshot.boundOwner().isPresent() && !snapshot.boundOwner().get().equals(player.getUniqueId())) continue;
            String trait = snapshot.rareTrait().orElse("");
            boolean activeTrait = EquipmentTraitCatalog.activeFor(snapshot, player.getUniqueId());
            EquipmentTraitCatalog.borrowedSkill(trait).ifPresent(skillId -> {
                if (activeTrait) activeSkillRunes.add(skillId);
                // A fully revealed market item may advertise its rune, but it
                // cannot grant a cast until normal equipment use binds it.
                else if (snapshot.totalSlots() > 0
                        && snapshot.revealedSlots() == snapshot.totalSlots()) dormantSkillRunes.add(skillId);
            });
            healingBoost |= activeTrait && trait.equals("healing-boost");
            skillHaste += snapshot.stat("skill-haste");
            healing += snapshot.stat("healing");
        }
        return new EquipmentTraits(
                Set.copyOf(activeSkillRunes), Set.copyOf(dormantSkillRunes), healingBoost,
                Math.clamp(skillHaste, -30, 60), Math.clamp(healing, -30, 100));
    }

    void regenerateMana() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!currentSession(player)) continue;
            PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
            if (profile == null) continue;
            int cap = manaCap(profile);
            Integer before = mana.get(player.getUniqueId());
            int restored = mana.compute(player.getUniqueId(), (ignored, current) ->
                    Math.min(cap, current == null ? cap : current + 4));
            if (before == null || before != restored) player.getPersistentDataContainer().set(
                    manaKey, PersistentDataType.INTEGER, restored);
        }
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        UUID playerId = player.getUniqueId();
        authenticatedPlayers.put(playerId, player);
        loadLearnedSkills(player);
    }

    private void loadLearnedSkills(Player player) {
        UUID playerId = player.getUniqueId();
        skills.skills(playerId).thenCombine(profiles.get(playerId), LearnedAndProfile::new)
                .whenComplete((loaded, error) -> sync(() -> {
            if (!currentSession(player)) return;
            if (error != null) {
                plugin.getLogger().warning("Could not warm skill HUD cache for " + playerId + ": "
                        + error.getMessage());
                return;
            }
            restoreSessionState(player, loaded.profile());
            cacheLearned(playerId, loaded.skills().keySet());
            sendControlLegend(player);
        }));
    }

    private void cacheLearned(UUID playerId, java.util.Collection<String> ids) {
        Set<String> copy = ConcurrentHashMap.newKeySet();
        copy.addAll(ids);
        learnedSkillIds.put(playerId, copy);
        learnedRefreshTimes.put(playerId, System.currentTimeMillis());
    }

    private void rememberLearned(UUID playerId, String skillId) {
        learnedSkillIds.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(skillId);
        learnedRefreshTimes.put(playerId, System.currentTimeMillis());
    }

    @Override
    public List<SkillCooldownSnapshot> slots(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !currentSession(player)) return List.of();
        SkillHudSnapshot current = snapshot(playerId).orElse(null);
        if (current == null) return List.of();
        return resolveSlots(player, current.learnedSkills());
    }

    @Override
    public int selectedSlot(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !currentSession(player)) return 0;
        Integer stored = player.getPersistentDataContainer().get(selectedSlotKey, PersistentDataType.INTEGER);
        return Math.clamp(stored == null ? 0 : stored, 0, 3);
    }

    private List<SkillCooldownSnapshot> resolveSlots(
            Player player, List<SkillCooldownSnapshot> available
    ) {
        Map<String, SkillCooldownSnapshot> byId = available.stream().collect(
                java.util.stream.Collectors.toMap(
                        SkillCooldownSnapshot::skillId, value -> value, (left, right) -> left,
                        java.util.LinkedHashMap::new));
        List<String> ids = readSlotIds(player);
        Set<String> used = new java.util.HashSet<>();
        boolean changed = false;
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            if (id.isBlank() || !byId.containsKey(id) || !used.add(id)) {
                if (!id.isBlank()) changed = true;
                ids.set(index, "");
            }
        }
        for (String id : byId.keySet()) {
            if (used.contains(id)) continue;
            int empty = ids.indexOf("");
            if (empty < 0) break;
            ids.set(empty, id);
            used.add(id);
            changed = true;
        }
        if (changed) writeSlotIds(player, ids);
        // Keep the physical 1-4 positions. Compacting empty entries made a
        // skill bound to slot 4 appear in slot 1 and reset the selected slot.
        List<SkillCooldownSnapshot> result = new ArrayList<>(4);
        for (String id : ids) {
            SkillCooldownSnapshot entry = byId.get(id);
            result.add(entry == null ? EMPTY_SKILL_SLOT : entry);
        }
        int selected = selectedSlot(player.getUniqueId());
        if (result.get(selected).emptySlot()) {
            int firstBound = java.util.stream.IntStream.range(0, result.size())
                    .filter(index -> !result.get(index).emptySlot())
                    .findFirst().orElse(0);
            player.getPersistentDataContainer().set(selectedSlotKey,
                    PersistentDataType.INTEGER, firstBound);
        }
        return List.copyOf(result);
    }

    private List<String> readSlotIds(Player player) {
        String serialized = player.getPersistentDataContainer().get(skillSlotsKey, PersistentDataType.STRING);
        String[] split = serialized == null ? new String[0] : serialized.split(",", -1);
        List<String> result = new ArrayList<>(4);
        for (int index = 0; index < 4; index++) {
            result.add(index < split.length ? split[index].trim().toLowerCase(Locale.ROOT) : "");
        }
        return result;
    }

    private void writeSlotIds(Player player, List<String> ids) {
        player.getPersistentDataContainer().set(skillSlotsKey, PersistentDataType.STRING,
                java.util.stream.IntStream.range(0, 4)
                        .mapToObj(index -> index < ids.size() ? ids.get(index) : "")
                        .collect(java.util.stream.Collectors.joining(",")));
    }

    private void bindSkill(Player player, String skillId, int slot, boolean feedback) {
        if (slot < 0 || slot >= 4) {
            if (feedback) player.sendMessage(Component.text(messages.text(player,
                    "skill-command.slot-invalid"), NamedTextColor.RED));
            return;
        }
        List<SkillCooldownSnapshot> available = snapshot(player.getUniqueId())
                .map(SkillHudSnapshot::learnedSkills).orElseGet(List::of);
        if (available.stream().noneMatch(entry -> entry.skillId().equals(skillId))) {
            if (feedback) player.sendMessage(Component.text(messages.text(player,
                    "skill-command.not-learned"), NamedTextColor.RED));
            return;
        }
        List<String> ids = readSlotIds(player);
        for (int index = 0; index < ids.size(); index++) {
            if (ids.get(index).equals(skillId)) ids.set(index, "");
        }
        ids.set(slot, skillId);
        writeSlotIds(player, ids);
        player.getPersistentDataContainer().set(selectedSlotKey, PersistentDataType.INTEGER, slot);
        hudSignatures.remove(player.getUniqueId());
        if (feedback) sendControlLegend(player);
    }

    private void selectSlot(Player player, int slot, boolean feedback) {
        List<SkillCooldownSnapshot> bound = slots(player.getUniqueId());
        if (slot < 0 || slot >= bound.size() || bound.get(slot).emptySlot()) {
            if (feedback) player.sendActionBar(Component.text(messages.text(player,
                    "skill-command.slot-empty", Map.of("slot", Math.clamp(slot + 1, 1, 4))),
                    NamedTextColor.YELLOW));
            return;
        }
        player.getPersistentDataContainer().set(selectedSlotKey, PersistentDataType.INTEGER, slot);
        hudSignatures.remove(player.getUniqueId());
        if (feedback) player.sendActionBar(Component.text(messages.text(player,
                "skill-command.selected", Map.of(
                        "slot", slot + 1,
                        "skill", skillName(player, bound.get(slot).skillId()))), NamedTextColor.GOLD));
    }

    private void cycleSkill(Player player) {
        List<SkillCooldownSnapshot> bound = slots(player.getUniqueId());
        if (bound.isEmpty() || bound.stream().allMatch(SkillCooldownSnapshot::emptySlot)) {
            player.sendActionBar(Component.text(messages.text(player,
                    "skill-command.controls-no-skills"), NamedTextColor.YELLOW));
            return;
        }
        int current = selectedSlot(player.getUniqueId());
        for (int offset = 1; offset <= 4; offset++) {
            int candidate = (current + offset) % 4;
            if (candidate < bound.size() && !bound.get(candidate).emptySlot()) {
                player.getPersistentDataContainer().set(selectedSlotKey,
                        PersistentDataType.INTEGER, candidate);
                hudSignatures.remove(player.getUniqueId());
                sendControlLegend(player);
                return;
            }
        }
    }

    /** Cast one configured RPG slot without coupling it to a vanilla hotbar slot. */
    private void castSlot(Player player, int slot) {
        List<SkillCooldownSnapshot> bound = slots(player.getUniqueId());
        if (slot < 0 || slot >= bound.size() || bound.get(slot).emptySlot()) {
            player.sendActionBar(Component.text(messages.text(player,
                    "skill-command.slot-empty", Map.of("slot", Math.clamp(slot + 1, 1, 4))),
                    NamedTextColor.YELLOW));
            return;
        }
        player.getPersistentDataContainer().set(selectedSlotKey, PersistentDataType.INTEGER, slot);
        hudSignatures.remove(player.getUniqueId());
        cast(player, bound.get(slot).skillId());
    }

    private String selectedSkill(Player player) {
        List<SkillCooldownSnapshot> bound = slots(player.getUniqueId());
        if (bound.isEmpty()) return null;
        int selected = Math.clamp(selectedSlot(player.getUniqueId()), 0, bound.size() - 1);
        SkillCooldownSnapshot selectedSkill = bound.get(selected);
        return selectedSkill.emptySlot() ? null : selectedSkill.skillId();
    }

    @Override
    public Optional<SkillHudSnapshot> snapshot(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !currentSession(player)) return Optional.empty();
        Set<String> learned = learnedSkillIds.get(playerId);
        PlayerProfile profile = profiles.cached(playerId).orElse(null);
        if (learned == null || profile == null) return Optional.empty();
        int cap = manaCap(profile);
        int current = Math.clamp(mana.getOrDefault(playerId, cap), 0, cap);
        Map<String, Long> playerCooldowns = cooldowns.getOrDefault(playerId, Map.of());
        Set<String> activeRunes = equipmentTraits(player).activeSkillRunes();
        List<SkillCooldownSnapshot> entries = configuredSkillIds().stream()
                .filter(id -> learned.contains(id) || activeRunes.contains(id))
                // An administrator test profile intentionally owns every
                // authored skill. Keep the persistent HUD scoped to the class
                // currently under test instead of showing the first four
                // entries from another class.
                .filter(id -> activeRunes.contains(id) || parseArchetype(plugin.getConfig().getString(
                        "skills." + id + ".class", "UNCHOSEN")) == profile.archetype())
                .map(id -> new SkillCooldownSnapshot(id, Math.max(0L, playerCooldowns.getOrDefault(id, 0L))))
                .toList();
        return Optional.of(new SkillHudSnapshot(entries, current, cap,
                learnedRefreshTimes.getOrDefault(playerId, 0L)));
    }

    @Override
    public void showOverview(Player player) {
        if (!currentSession(player)) return;
        if (!learnedSkillIds.containsKey(player.getUniqueId())) {
            UUID playerId = player.getUniqueId();
            skills.skills(playerId).whenComplete((loaded, error) -> sync(() -> {
                if (!currentSession(player)) return;
                if (error != null) {
                    player.sendMessage(Component.text(messages.text(player, "skill-command.load-error"),
                            NamedTextColor.RED));
                    return;
                }
                cacheLearned(playerId, loaded.keySet());
                showOverview(player);
            }));
            return;
        }
        if (betterHudActive(player)) {
            sendControlLegend(player);
            return;
        }
        showHudFor(player, "", Math.clamp(plugin.getConfig().getLong(
                "hud.overview-visible-millis", 8000L), 2500L, 15000L));
    }

    private void sendControlLegend(Player player) {
        List<SkillCooldownSnapshot> bound = slots(player.getUniqueId());
        int selected = selectedSlot(player.getUniqueId());
        String current = selected < bound.size() && !bound.get(selected).emptySlot()
                ? skillName(player, bound.get(selected).skillId())
                : messages.text(player, "skill-command.controls-empty");
        player.sendActionBar(Component.text(messages.text(player,
                "skill-command.controls-legend", Map.of(
                        "slot", selected + 1,
                        "skill", current)), NamedTextColor.AQUA));
    }

    private void sendCastFeedback(Player player, String skillId, int currentMana, int cap, long cooldownMillis) {
        player.sendActionBar(Component.text(messages.text(player,
                "skill-command.cast-feedback", Map.of(
                        "skill", skillName(player, skillId),
                        "mana", Math.max(0, currentMana),
                        "cap", cap,
                        "seconds", Math.max(1L, (cooldownMillis + 999L) / 1000L))),
                NamedTextColor.AQUA));
    }

    private List<String> configuredSkillIds() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("skills");
        if (section == null) return List.of();
        return List.copyOf(section.getKeys(false));
    }

    void updateHud() {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, HudState> entry : Map.copyOf(hud).entrySet()) {
            if (entry.getValue().visibleUntil() <= now) {
                hud.remove(entry.getKey(), entry.getValue());
                hudSignatures.remove(entry.getKey());
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                hud.remove(entry.getKey());
                hudSignatures.remove(entry.getKey());
                continue;
            }
            sendHud(player, entry.getValue().focusSkillId(), now);
        }
        if (plugin.getConfig().getBoolean("hud.persistent", true)) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (!currentSession(player)) continue;
                HudState focused = hud.get(player.getUniqueId());
                sendHud(player, focused == null ? "" : focused.focusSkillId(), now);
            }
        }
    }

    private void showHudFor(Player player, String focusSkillId, long durationMillis) {
        if (!plugin.getConfig().getBoolean("hud.enabled", true)) return;
        // BetterHud already owns the four large icon tiles and cooldown digits.
        // Sending the legacy text strip at the same time puts a long ActionBar
        // sentence across those tiles. Keep it only as the no-pack fallback.
        if (betterHudActive(player)) {
            hud.remove(player.getUniqueId());
            return;
        }
        long now = System.currentTimeMillis();
        hud.put(player.getUniqueId(), new HudState(focusSkillId == null ? "" : focusSkillId, now + durationMillis));
        hudSignatures.remove(player.getUniqueId());
        sendHud(player, focusSkillId, now);
    }

    private boolean betterHudActive(Player player) {
        return plugin.getConfig().getBoolean("hud.use-resource-glyphs", false)
                && plugin.getServer().getPluginManager().isPluginEnabled("BetterHud")
                && player.getResourcePackStatus()
                == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    private void sendHud(Player player, String focusSkillId, long now) {
        if (betterHudActive(player)) {
            hud.remove(player.getUniqueId());
            hudSignatures.remove(player.getUniqueId());
            return;
        }
        SkillHudSnapshot snapshot = snapshot(player.getUniqueId()).orElse(null);
        if (snapshot == null) return;
        int cap = snapshot.manaCap();
        int current = snapshot.mana();
        int segments = Math.clamp(plugin.getConfig().getInt("hud.segments", 10), 5, 20);
        int filled = cap == 0 ? 0 : (int) Math.round((double) current / cap * segments);
        filled = Math.clamp(filled, 0, segments);
        int maximum = Math.clamp(plugin.getConfig().getInt("hud.max-skills", 3), 1, 4);
        List<SkillCooldownSnapshot> displayed = SkillHudProjection.select(
                snapshot.learnedSkills(), focusSkillId, now, maximum);
        String cooldownSignature = displayed.stream().map(entry -> entry.skillId() + ":"
                        + entry.remainingSeconds(now)).collect(java.util.stream.Collectors.joining(","));
        boolean customGlyph = plugin.getConfig().getBoolean("hud.use-resource-glyphs", false)
                && player.getResourcePackStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
        String signature = String.join("|", messages.locale(player), Integer.toString(current),
                Integer.toString(cap), cooldownSignature, Boolean.toString(customGlyph),
                profile == null ? "loading" : profile.archetype().name());
        long refreshMillis = Math.clamp(plugin.getConfig().getLong(
                "hud.refresh-seconds", 5L), 2L, 15L) * 1000L;
        String previous = hudSignatures.put(player.getUniqueId(), signature);
        long lastSent = hudSentAt.getOrDefault(player.getUniqueId(), 0L);
        if (signature.equals(previous) && now - lastSent < refreshMillis) return;
        hudSentAt.put(player.getUniqueId(), now);
        Component value = customGlyph && profile != null
                ? Component.empty().append(HudGlyphs.icon(HudGlyphs.classGlyph(profile.archetype())))
                        .append(Component.space()).append(HudGlyphs.icon(HudGlyphs.MANA)).append(Component.space())
                : Component.text(messages.text(player, "skill-command.hud-mana") + " ", NamedTextColor.AQUA);
        value = value
                .append(Component.text("▰".repeat(filled), NamedTextColor.AQUA))
                .append(Component.text("▱".repeat(segments - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + current + "/" + cap, NamedTextColor.WHITE));
        if (displayed.isEmpty()) {
            value = value.append(Component.text("  ·  " + messages.text(player,
                    "skill-command.hud-no-skills"), NamedTextColor.GRAY));
        } else {
            for (SkillCooldownSnapshot entry : displayed) {
                long remaining = entry.remainingSeconds(now);
                value = value.append(Component.text("  ·  ", NamedTextColor.DARK_GRAY));
                if (customGlyph) {
                    java.util.Optional<Character> glyph = HudGlyphs.skillGlyph(entry.skillId());
                    if (glyph.isPresent()) {
                        value = value.append(HudGlyphs.icon(glyph.orElseThrow())).append(Component.space());
                    }
                }
                value = value.append(Component.text(skillName(player, entry.skillId()) + " ", NamedTextColor.GOLD))
                        .append(Component.text(remaining == 0
                                        ? messages.text(player, "skill-command.hud-ready")
                                        : messages.text(player, "skill-command.hud-cooldown",
                                                Map.of("seconds", remaining)),
                                remaining == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            }
        }
        value = value.append(Component.text("  ·  " + messages.text(player,
                "skill-command.hud-controls"), NamedTextColor.GRAY));
        player.sendActionBar(value);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer(), true);
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player(), false);
    }

    @EventHandler
    public void onLogout(LogoutEvent event) {
        clearSession(event.getPlayer(), false);
    }

    private void clearSession(Player player, boolean evictSkillCache) {
        UUID playerId = player.getUniqueId();
        // AuthMe also emits LogoutEvent while a connection is closing. Make
        // the subsequent PlayerQuitEvent idempotent so it cannot erase the
        // just-persisted cooldown string with an already-empty memory map.
        if (!authenticatedPlayers.remove(playerId, player)) {
            // A rapid reconnect can install a new Player handle before the old
            // connection emits its final quit event. Never evict that new
            // connection's shared service cache from the stale event.
            if (evictSkillCache && !authenticatedPlayers.containsKey(playerId)) skills.evict(playerId);
            return;
        }
        persistSessionState(player);
        mechanics.stopOwner(playerId);
        learningPlayers.remove(playerId, player);
        castingPlayers.remove(playerId, player);
        treeLoadingPlayers.remove(playerId, player);
        hud.remove(playerId);
        hudSignatures.remove(playerId);
        hudSentAt.remove(playerId);
        PendingSkillInput pendingInput = pendingSkillInputs.remove(playerId);
        if (pendingInput != null) pendingInput.task().cancel();
        battleTrances.remove(playerId);
        defenseStates.remove(playerId);
        lineLinks.remove(playerId);
        standardStates.remove(playerId);
        fateSeals.remove(playerId);
        lineLinks.entrySet().removeIf(entry -> entry.getValue().guardian().equals(playerId));
        standardStates.entrySet().removeIf(entry -> entry.getValue().owner().equals(playerId));
        fateSeals.entrySet().removeIf(entry -> entry.getValue().owner().equals(playerId));
        cooldowns.remove(playerId);
        mana.remove(playerId);
        learnedSkillIds.remove(playerId);
        learnedRefreshTimes.remove(playerId);
        if (evictSkillCache) skills.evict(playerId);
    }

    private void restoreSessionState(Player player, PlayerProfile profile) {
        UUID playerId = player.getUniqueId();
        int cap = manaCap(profile);
        Integer storedMana = player.getPersistentDataContainer().get(manaKey, PersistentDataType.INTEGER);
        mana.putIfAbsent(playerId, SkillSessionState.restoreMana(storedMana, cap));
        if (!cooldowns.containsKey(playerId)) {
            String storedCooldowns = player.getPersistentDataContainer().get(
                    cooldownsKey, PersistentDataType.STRING);
            Map<String, Long> restored = new ConcurrentHashMap<>(SkillSessionState.decodeCooldowns(
                    storedCooldowns, Set.copyOf(configuredSkillIds()), System.currentTimeMillis()));
            cooldowns.put(playerId, restored);
        }
        persistSessionState(player);
    }

    private void persistSessionState(Player player) {
        UUID playerId = player.getUniqueId();
        Integer currentMana = mana.get(playerId);
        if (currentMana != null) player.getPersistentDataContainer().set(
                manaKey, PersistentDataType.INTEGER, Math.max(0, currentMana));
        String encoded = SkillSessionState.encodeCooldowns(
                cooldowns.getOrDefault(playerId, Map.of()), System.currentTimeMillis());
        if (encoded.isBlank()) player.getPersistentDataContainer().remove(cooldownsKey);
        else player.getPersistentDataContainer().set(cooldownsKey, PersistentDataType.STRING, encoded);
    }

    void shutdown() {
        for (Player player : Set.copyOf(authenticatedPlayers.values())) {
            if (currentSession(player)) persistSessionState(player);
        }
        pendingSkillInputs.values().forEach(input -> input.task().cancel());
        pendingSkillInputs.clear();
        mechanics.shutdown();
        sequences.close();
        defenseStates.clear();
        lineLinks.clear();
        standardStates.clear();
        fateSeals.clear();
        authenticatedPlayers.clear();
        learningPlayers.clear();
        castingPlayers.clear();
        treeLoadingPlayers.clear();
    }

    private boolean currentSession(Player player) {
        if (player == null || !player.isOnline()
                || Bukkit.getPlayer(player.getUniqueId()) != player
                || authenticatedPlayers.get(player.getUniqueId()) != player) return false;
        try {
            return AuthMeApi.getInstance().isAuthenticated(player);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean beginOperation(Map<UUID, Player> operations, Player player) {
        UUID playerId = player.getUniqueId();
        Player active = operations.putIfAbsent(playerId, player);
        if (active == null) return true;
        if (active == player) return false;
        // Only the currently authenticated connection may replace a stale
        // operation. The old callback removes by identity and cannot clear it.
        return currentSession(player) && operations.replace(playerId, active, player);
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalPluginAccessException | IllegalStateException ignored) {
            // Durable service mutations are authoritative. Connection-local UI
            // and caches are reconstructed after the next authenticated join.
        }
    }

    private record PendingSkillInput(Player player, long startedAtMillis, long token, BukkitTask task) {}

    private int manaCap(PlayerProfile profile) {
        return 100 + profile.attribute(CoreAttribute.INTELLIGENCE) * 3 + profile.attribute(CoreAttribute.STAMINA);
    }

    private int skillMaximumLevel(int ascension) {
        return SkillLevelRules.maximumLevel(plugin.getConfig().getIntegerList(
                "skill-leveling.maximum-level-by-ascension"), ascension);
    }

    private int skillUpgradeCost(int currentLevel) {
        return SkillLevelRules.upgradeCost(plugin.getConfig().getIntegerList(
                "skill-leveling.upgrade-point-costs"), currentLevel);
    }

    private double skillCooldownReduction() {
        return plugin.getConfig().getDouble("skill-leveling.cooldown-reduction-per-level", 0.04);
    }

    private double skillCooldownFloor() {
        return plugin.getConfig().getDouble("skill-leveling.minimum-cooldown-multiplier", 0.84);
    }

    private void showTree(Player player, int requestedPage) {
        loadTree(player, requestedPage, null);
    }

    private void refreshTree(Player player, SkillMenu menu) {
        loadTree(player, menu.page(), menu);
    }

    private void loadTree(Player player, int requestedPage, SkillMenu expectedMenu) {
        ConfigurationSection skills = plugin.getConfig().getConfigurationSection("skills");
        if (skills == null) return;
        UUID playerId = player.getUniqueId();
        if (!beginOperation(treeLoadingPlayers, player)) {
            player.sendActionBar(Component.text(messages.text(player, "skill-command.tree-loading"),
                    NamedTextColor.YELLOW));
            return;
        }
        profiles.get(playerId)
                .thenCombine(branches.branches(playerId), ProfileAndBranches::new)
                .thenCombine(this.skills.skills(playerId), LearnContext::new)
                .whenComplete((context, error) -> sync(() -> {
            treeLoadingPlayers.remove(playerId, player);
            if (!currentSession(player)) return;
            if (expectedMenu != null && !isViewing(player, expectedMenu)) return;
            if (error != null) {
                player.sendMessage(Component.text(messages.text(player, "skill-command.load-error"), NamedTextColor.RED));
                return;
            }
            PlayerProfile profile = context.profileAndBranches().profile();
            Map<Integer, String> selectedBranches = context.profileAndBranches().branches();
            Map<String, SkillProgress> learnedSkills = context.learnedSkills();
            EquipmentTraits traits = equipmentTraits(player);
            cacheLearned(player.getUniqueId(), learnedSkills.keySet());
            if (SkillMenuPolicy.mode(profile.archetype()) == SkillMenuPolicy.Mode.CHOOSE_CLASS) {
                if (expectedMenu == null) showClassChoice(player, profile, learnedSkills);
                return;
            }
            List<String> visible = skills.getKeys(false).stream()
                    .filter(id -> {
                        ConfigurationSection skill = skills.getConfigurationSection(id);
                        if (skill == null) return false;
                        Archetype owner = parseArchetype(skill.getString("class", "UNCHOSEN"));
                        return profile.archetype() == Archetype.UNCHOSEN || owner == profile.archetype()
                                || traits.activeSkillRunes().contains(id);
                    })
                    .sorted(Comparator
                            .comparingInt((String id) -> skills.getInt(id + ".minimum-ascension", 0))
                            .thenComparingInt(id -> skills.getInt(id + ".minimum-level", 1))
                            .thenComparing(id -> id))
                    .toList();
            int pages = Math.max(1, (visible.size() + SkillMenuLayout.CONTENT.size() - 1)
                    / SkillMenuLayout.CONTENT.size());
            int page = Math.clamp(requestedPage, 0, pages - 1);
            String className = messages.text(player,
                    "classes." + profile.archetype().name().toLowerCase(Locale.ROOT));
            SkillMenu menu = expectedMenu == null
                    ? new SkillMenu(messages.text(player, "skill-command.gui-title", Map.of(
                            "class", className, "page", page + 1, "pages", pages)), page)
                    : expectedMenu;
            menu.clear();
            int remainingPoints = SkillMenuPolicy.remainingPoints(profile.skillPoints(), learnedSkills.values());
            menu.set(SkillMenuLayout.HEADER,
                    skillHeader(player, profile, learnedSkills.size(), remainingPoints), null);
            int start = page * SkillMenuLayout.CONTENT.size();
            int end = Math.min(visible.size(), start + SkillMenuLayout.CONTENT.size());
            Set<Integer> occupied = new java.util.HashSet<>();
            NextSkillTarget nextTarget = null;
            for (int index = start; index < end; index++) {
                String id = visible.get(index);
                ConfigurationSection skill = skills.getConfigurationSection(id);
                if (skill == null) continue;
                boolean learned = learnedSkills.containsKey(id);
                boolean runeActive = traits.activeSkillRunes().contains(id);
                boolean borrowed = !learned && runeActive;
                boolean mastered = learned && runeActive;
                SkillLock lock = learned || borrowed ? null
                        : skillLock(player, profile, selectedBranches, learnedSkills, skill);
                String lockReason = lock == null ? null : lock.reason();
                SkillProgress progress = learnedSkills.get(id);
                String action = SkillMenuPolicy.action(
                        learned, borrowed, lockReason, id, progress == null ? 1 : progress.level());
                int configuredSlot = profile.archetype() == Archetype.UNCHOSEN || page > 0
                        ? -1 : skill.getInt("menu-slot", -1);
                int slot = SkillMenuLayout.CONTENT.contains(configuredSlot) && occupied.add(configuredSlot)
                        ? configuredSlot
                        : SkillMenuLayout.CONTENT.stream().filter(candidate -> !occupied.contains(candidate)).findFirst()
                                .orElseThrow();
                occupied.add(slot);
                menu.set(slot, skillItem(player, id, skill, progress, borrowed, mastered,
                        lockReason, traits, profile, remainingPoints), action);
                if (!learned && parseArchetype(skill.getString("class", "UNCHOSEN")) == profile.archetype()) {
                    NextSkillTarget candidate = new NextSkillTarget(id, lock);
                    if (nextTarget == null || SkillMenuPolicy.objectivePriority(
                            candidate.gate()) < SkillMenuPolicy.objectivePriority(nextTarget.gate())) {
                        nextTarget = candidate;
                    }
                }
            }
            String objectiveAction = nextTarget == null ? null
                    : nextTarget.lock() == null ? "learn " + nextTarget.skillId()
                    : SkillMenuPolicy.routeFor(nextTarget.gate());
            menu.set(SkillMenuLayout.NEXT_OBJECTIVE,
                    nextObjectiveItem(player, profile, remainingPoints, nextTarget), objectiveAction);
            menu.set(SkillMenuLayout.MAIN_MENU, mainMenuItem(player), "command:menu");
            menu.set(SkillMenuLayout.MENTOR, navigationShortcut(player, Material.LODESTONE,
                    "skill-command.gui-mentor", "skill-command.gui-mentor-lore"), "command:hubfind skills");
            if (page > 0) menu.set(SkillMenuLayout.PREVIOUS, navigationItem(player, false),
                    "page:" + (page - 1));
            menu.set(SkillMenuLayout.CONTROLS, controlsItem(player), null);
            menu.set(SkillMenuLayout.HUD, cooldownOverviewItem(player), "hud");
            menu.set(SkillMenuLayout.CLASS_AND_ATTRIBUTES, navigationShortcut(player, Material.NETHER_STAR,
                    "skill-command.gui-class", "skill-command.gui-class-lore"), "command:class");
            menu.set(SkillMenuLayout.HELP, navigationShortcut(player, Material.KNOWLEDGE_BOOK,
                    "skill-command.gui-help", "skill-command.gui-help-lore"), "command:rpghelp skills");
            menu.set(SkillMenuLayout.JOURNEY, navigationShortcut(player, Material.RECOVERY_COMPASS,
                    "skill-command.gui-journey", "skill-command.gui-journey-lore"), "command:rpghelp current");
            if (page + 1 < pages) menu.set(SkillMenuLayout.NEXT, navigationItem(player, true),
                    "page:" + (page + 1));
            if (expectedMenu == null) player.openInventory(menu.getInventory());
            else player.updateInventory();
        }));
    }

    private void showClassChoice(Player player, PlayerProfile profile, Map<String, SkillProgress> learnedSkills) {
        SkillMenu menu = new SkillMenu(messages.text(player, "skill-command.gui-choose-title"), 0);
        menu.set(SkillMenuLayout.HEADER, skillHeader(player, profile, learnedSkills.size(),
                SkillMenuPolicy.remainingPoints(profile.skillPoints(), learnedSkills.values())), null);
        menu.set(22, classChoiceItem(player), "command:class");
        menu.set(SkillMenuLayout.MAIN_MENU, mainMenuItem(player), "command:menu");
        menu.set(SkillMenuLayout.MENTOR, navigationShortcut(player, Material.LODESTONE,
                "skill-command.gui-mentor", "skill-command.gui-mentor-lore"), "command:hubfind skills");
        menu.set(SkillMenuLayout.CLASS_AND_ATTRIBUTES, navigationShortcut(player, Material.NETHER_STAR,
                "skill-command.gui-class", "skill-command.gui-class-lore"), "command:class");
        menu.set(SkillMenuLayout.CONTROLS, controlsItem(player), null);
        menu.set(SkillMenuLayout.HELP, navigationShortcut(player, Material.KNOWLEDGE_BOOK,
                "skill-command.gui-help", "skill-command.gui-help-lore"), "command:rpghelp skills");
        menu.set(SkillMenuLayout.JOURNEY, navigationShortcut(player, Material.RECOVERY_COMPASS,
                "skill-command.gui-journey", "skill-command.gui-journey-lore"), "command:rpghelp current");
        player.openInventory(menu.getInventory());
    }

    private ItemStack classChoiceItem(Player player) {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "skill-command.gui-choose-class"),
                NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(messages.lines(player, "skill-command.gui-choose-lines").stream()
                .flatMap(line -> MenuLoreLayout.wrap(line).stream())
                .map(line -> lore(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack skillHeader(
            Player player, PlayerProfile profile, int learnedCount, int remainingPoints
    ) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "skill-command.gui-header"), NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        addLore(lore, messages.text(player, "skill-command.gui-stage-summary", Map.of(
                "ascension", profile.ascension(), "level", profile.level())), NamedTextColor.WHITE);
        addLore(lore, messages.text(player, "skill-command.gui-points", Map.of(
                "points", remainingPoints, "learned", learnedCount)), NamedTextColor.YELLOW);
        addLore(lore, messages.text(player, "skill-command.gui-mana", Map.of(
                "current", mana.getOrDefault(player.getUniqueId(), manaCap(profile)),
                "cap", manaCap(profile))), NamedTextColor.AQUA);
        addLore(lore, messages.text(player, "skill-command.gui-tree-shape"), NamedTextColor.GRAY);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack skillItem(
            Player player, String id, ConfigurationSection skill, SkillProgress progress,
            boolean borrowed, boolean mastered, String lockReason, EquipmentTraits traits,
            PlayerProfile profile, int remainingPoints
    ) {
        boolean learned = progress != null;
        ItemStack item = new ItemStack(skillIcon(id));
        ItemMeta meta = item.getItemMeta();
        applySkillItemModel(meta, id);
        NamedTextColor color = mastered ? NamedTextColor.GOLD : learned ? NamedTextColor.GREEN
                : borrowed ? NamedTextColor.LIGHT_PURPLE
                : lockReason == null ? NamedTextColor.AQUA : NamedTextColor.GRAY;
        String stateKey = mastered ? "skill-command.gui-name-mastered"
                : learned ? "skill-command.gui-name-learned"
                : borrowed ? "skill-command.gui-name-borrowed"
                : lockReason == null ? "skill-command.gui-name-available"
                : "skill-command.gui-name-locked";
        meta.displayName(Component.text(messages.text(player, stateKey, Map.of(
                "skill", skillName(player, id))), color).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        List<String> description = messages.lines(player, "skills.descriptions." + id);
        for (String line : description) addLore(lore, line, NamedTextColor.WHITE);
        if (progress != null && progress.variant().isPresent()) {
            String variant = progress.variant().orElseThrow();
            addLore(lore, messages.text(player, "skill-command.gui-variant", Map.of(
                    "variant", messages.text(player, "skill-variants."
                            + variant.toLowerCase(Locale.ROOT).replace('_', '-')))), NamedTextColor.LIGHT_PURPLE);
            for (String line : messages.lines(player, "skill-variant-details."
                    + variant.toLowerCase(Locale.ROOT).replace('_', '-'))) {
                addLore(lore, line, NamedTextColor.GRAY);
            }
            addLore(lore, messages.text(player, "skill-command.gui-mutation-manage", Map.of(
                    "id", id)), NamedTextColor.DARK_PURPLE);
        }
        if (!description.isEmpty()) lore.add(Component.empty());
        addLore(lore, messages.text(player, "skill-command.gui-id", Map.of("id", id)), NamedTextColor.DARK_GRAY);
        EquipmentBorrowingRules.CastMode castMode = mastered
                ? EquipmentBorrowingRules.CastMode.MASTERED
                : borrowed ? EquipmentBorrowingRules.CastMode.BORROWED
                : EquipmentBorrowingRules.CastMode.LEARNED;
        int skillLevel = progress == null ? 1 : progress.level();
        long leveledBaseCooldown = SkillLevelRules.cooldownMillis(
                skill.getLong("cooldown-seconds", 10), skillLevel,
                skillCooldownReduction(), skillCooldownFloor());
        String costKey = borrowed ? "skill-command.gui-borrowed-cost"
                : learned ? "skill-command.gui-learned-cost" : "skill-command.gui-cost";
        addLore(lore, messages.text(player, costKey, Map.of(
                "points", learned ? progress.pointCost() : skill.getInt("skill-points", 1),
                "mana", EquipmentBorrowingRules.manaCost(skill.getInt("mana-cost", 10), castMode,
                        plugin.getConfig().getDouble("equipment-effects.borrowed-skill.mana-multiplier", 1.5)),
                "cooldown", (EquipmentBorrowingRules.cooldownFromBaseMillis(
                        leveledBaseCooldown, castMode,
                        plugin.getConfig().getDouble("equipment-effects.borrowed-skill.cooldown-multiplier", 1.5),
                        plugin.getConfig().getDouble("equipment-effects.mastered-skill.cooldown-multiplier", 0.85),
                        traits.skillHaste()) + 999L) / 1000L)), NamedTextColor.GRAY);
        if (learned) {
            int stageMaximum = skillMaximumLevel(profile.ascension());
            int absoluteMaximum = skillMaximumLevel(3);
            addLore(lore, messages.text(player, "skill-command.gui-level", Map.of(
                    "current", progress.level(), "stage", stageMaximum, "maximum", absoluteMaximum)),
                    NamedTextColor.AQUA);
            if (progress.level() < stageMaximum) {
                int upgradeCost = skillUpgradeCost(progress.level());
                long nextBaseCooldown = SkillLevelRules.cooldownMillis(
                        skill.getLong("cooldown-seconds", 10), progress.level() + 1,
                        skillCooldownReduction(), skillCooldownFloor());
                long nextCooldown = EquipmentBorrowingRules.cooldownFromBaseMillis(
                        nextBaseCooldown, castMode,
                        plugin.getConfig().getDouble(
                                "equipment-effects.borrowed-skill.cooldown-multiplier", 1.5),
                        plugin.getConfig().getDouble(
                                "equipment-effects.mastered-skill.cooldown-multiplier", 0.85),
                        traits.skillHaste());
                String key = remainingPoints >= upgradeCost
                        ? "skill-command.gui-upgrade-ready" : "skill-command.gui-upgrade-points";
                addLore(lore, messages.text(player, key, Map.of(
                        "next", progress.level() + 1,
                        "cost", upgradeCost,
                        "points", remainingPoints,
                        "cooldown", (nextCooldown + 999L) / 1000L)),
                        remainingPoints >= upgradeCost ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.RED);
            } else if (progress.level() < absoluteMaximum) {
                addLore(lore, messages.text(player, "skill-command.gui-upgrade-ascension", Map.of(
                        "ascension", Math.min(3, profile.ascension() + 1),
                        "next", progress.level() + 1)), NamedTextColor.YELLOW);
            } else {
                addLore(lore, messages.text(player, "skill-command.gui-upgrade-maximum"),
                        NamedTextColor.GOLD);
            }
        }
        if (borrowed) {
            addLore(lore, messages.text(player, "skill-command.gui-borrowed", Map.of(
                    "power", Math.round(EquipmentBorrowingRules.powerMultiplier(castMode,
                            plugin.getConfig().getDouble("equipment-effects.borrowed-skill.power-multiplier", 0.65),
                            1.15) * 100.0),
                    "seconds", (EquipmentBorrowingRules.strainTicks(plugin.getConfig().getInt(
                            "equipment-effects.borrowed-skill.strain-ticks", 60)) + 19) / 20)),
                    NamedTextColor.LIGHT_PURPLE);
        } else if (mastered) {
            addLore(lore, messages.text(player, "skill-command.gui-mastered", Map.of(
                    "power", Math.round(EquipmentBorrowingRules.powerMultiplier(castMode, 0.65,
                            plugin.getConfig().getDouble("equipment-effects.mastered-skill.power-multiplier", 1.15))
                            * 100.0))), NamedTextColor.GOLD);
        }
        int level = Math.max(1, skill.getInt("minimum-level", 1));
        int ascension = Math.clamp(skill.getInt("minimum-ascension", 0), 0, 3);
        String branch = skill.getString("branch", "");
        if (!branch.isBlank()) {
            addLore(lore, messages.text(player, "skill-command.gui-node-branch", Map.of(
                    "ascension", ascension,
                    "branch", messages.text(player, "class-branches." + branch))), NamedTextColor.LIGHT_PURPLE);
        } else if (level > 1 || !skill.getStringList("prerequisites").isEmpty()) {
            addLore(lore, messages.text(player, "skill-command.gui-node-advanced", Map.of(
                    "level", level)), NamedTextColor.YELLOW);
        } else {
            addLore(lore, messages.text(player, "skill-command.gui-node-starter"), NamedTextColor.GREEN);
        }
        addLore(lore, messages.text(player, "skill-command.gui-stage", Map.of(
                "level", level, "ascension", ascension)), NamedTextColor.GRAY);
        if (!branch.isBlank()) addLore(lore, messages.text(player, "skill-command.gui-branch", Map.of(
                "branch", messages.text(player, "class-branches." + branch))), NamedTextColor.LIGHT_PURPLE);
        List<String> prerequisites = skill.getStringList("prerequisites");
        if (!prerequisites.isEmpty()) addLore(lore, messages.text(player, "skill-command.gui-prerequisite", Map.of(
                "skill", prerequisites.stream().map(value -> skillName(player, value))
                        .collect(java.util.stream.Collectors.joining(", ")))), NamedTextColor.GRAY);
        ConfigurationSection requirements = skill.getConfigurationSection("requirements");
        if (requirements != null && !requirements.getKeys(false).isEmpty()) {
            String attributes = requirements.getKeys(false).stream().sorted().map(key ->
                    attributeName(player, CoreAttribute.valueOf(key)) + " " + requirements.getInt(key))
                    .collect(java.util.stream.Collectors.joining(" · "));
            addLore(lore, messages.text(player, "skill-command.gui-attributes", Map.of(
                    "attributes", attributes)), NamedTextColor.GRAY);
        }
        lore.add(Component.empty());
        if (learned || borrowed) {
            long remaining = new SkillCooldownSnapshot(id, cooldowns
                    .getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(id, 0L))
                    .remainingSeconds(System.currentTimeMillis());
            addLore(lore, remaining == 0L
                            ? messages.text(player, "skill-command.gui-status-ready")
                            : messages.text(player, "skill-command.gui-status-cooldown", Map.of("seconds", remaining)),
                    remaining == 0L ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
            addLore(lore, messages.text(player, learned
                    ? "skill-command.gui-cast-and-upgrade" : "skill-command.gui-cast"), NamedTextColor.GREEN);
            addLore(lore, messages.text(player, "skill-command.gui-cast-command", Map.of("id", id)),
                    NamedTextColor.DARK_GRAY);
            if (learned) addLore(lore, messages.text(player, "skill-command.gui-upgrade-command", Map.of(
                    "id", id)), NamedTextColor.DARK_GRAY);
        } else if (lockReason == null) {
            addLore(lore, messages.text(player, "skill-command.gui-learn"), NamedTextColor.AQUA);
        } else {
            addLore(lore, messages.text(player, "skill-command.gui-locked", Map.of(
                    "reason", lockReason)), NamedTextColor.RED);
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private SkillLock skillLock(
            Player player, PlayerProfile profile, Map<Integer, String> selectedBranches,
            Map<String, SkillProgress> learnedSkills, ConfigurationSection skill
    ) {
        Archetype required = parseArchetype(skill.getString("class", "UNCHOSEN"));
        if (profile.archetype() != required) return new SkillLock(SkillMenuPolicy.Gate.CLASS,
                messages.text(player, "skill-command.class-mismatch"));
        int ascension = Math.clamp(skill.getInt("minimum-ascension", 0), 0, 3);
        if (profile.ascension() < ascension) return new SkillLock(SkillMenuPolicy.Gate.ASCENSION,
                messages.text(player, "skill-command.ascension-required", Map.of(
                        "ascension", ascension)));
        int level = Math.max(1, skill.getInt("minimum-level", 1));
        if (profile.level() < level) return new SkillLock(SkillMenuPolicy.Gate.LEVEL,
                messages.text(player, "skill-command.level-required", Map.of("level", level)));
        String branch = skill.getString("branch", "");
        if (!branch.isBlank() && !selectedBranches.containsValue(branch)) return new SkillLock(
                SkillMenuPolicy.Gate.BRANCH, messages.text(player, "skill-command.branch-required", Map.of(
                        "branch", messages.text(player, "class-branches." + branch))));
        for (String prerequisite : skill.getStringList("prerequisites")) {
            if (!learnedSkills.containsKey(prerequisite)) return new SkillLock(
                    SkillMenuPolicy.Gate.PREREQUISITE, messages.text(
                            player, "skill-command.prerequisite-required", Map.of(
                                    "skill", skillName(player, prerequisite))));
        }
        ConfigurationSection requirements = skill.getConfigurationSection("requirements");
        if (requirements != null) {
            for (String key : requirements.getKeys(false)) {
                CoreAttribute attribute = CoreAttribute.valueOf(key);
                if (profile.attribute(attribute) < requirements.getInt(key)) return new SkillLock(
                        SkillMenuPolicy.Gate.ATTRIBUTE, messages.text(
                                player, "skill-command.attribute-required", Map.of(
                                        "attribute", attributeName(player, attribute),
                                        "required", requirements.getInt(key))));
            }
        }
        int remainingPoints = SkillMenuPolicy.remainingPoints(profile.skillPoints(), learnedSkills.values());
        if (remainingPoints < skill.getInt("skill-points", 1)) return new SkillLock(
                SkillMenuPolicy.Gate.POINTS, messages.text(player, "skill-command.points-insufficient"));
        return null;
    }

    private ItemStack nextObjectiveItem(
            Player player, PlayerProfile profile, int remainingPoints, NextSkillTarget target
    ) {
        ItemStack item = new ItemStack(target == null ? Material.NETHER_STAR : Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        String title = target == null
                ? messages.text(player, "skill-command.gui-objective-complete")
                : messages.text(player, "skill-command.gui-objective", Map.of(
                        "skill", skillName(player, target.skillId())));
        NamedTextColor titleColor = target == null ? NamedTextColor.GREEN
                : target.lock() == null ? NamedTextColor.AQUA : NamedTextColor.GOLD;
        meta.displayName(Component.text(title, titleColor).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        addLore(lore, messages.text(player, "skill-command.gui-objective-status", Map.of(
                "ascension", profile.ascension(), "level", profile.level(),
                "points", remainingPoints)), NamedTextColor.WHITE);
        if (target == null) {
            addLore(lore, messages.text(player, "skill-command.gui-objective-complete-lore"),
                    NamedTextColor.GREEN);
        } else if (target.lock() == null) {
            addLore(lore, messages.text(player, "skill-command.gui-objective-ready"), NamedTextColor.AQUA);
        } else {
            addLore(lore, messages.text(player, "skill-command.gui-objective-locked", Map.of(
                    "reason", target.lock().reason())), NamedTextColor.RED);
            String routeKey = switch (target.gate()) {
                case CLASS, ASCENSION, BRANCH, ATTRIBUTE -> "skill-command.gui-objective-route-class";
                case LEVEL, POINTS -> "skill-command.gui-objective-route-level";
                case PREREQUISITE -> "skill-command.gui-objective-route-prerequisite";
            };
            addLore(lore, messages.text(player, routeKey), NamedTextColor.YELLOW);
        }
        lore.add(Component.empty());
        addLore(lore, messages.text(player, "skill-command.gui-point-sources"), NamedTextColor.GRAY);
        addLore(lore, messages.text(player, "skill-command.gui-growth-route"), NamedTextColor.GRAY);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack navigationItem(Player player, boolean next) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player,
                next ? "skill-command.gui-next" : "skill-command.gui-previous"), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack mainMenuItem(Player player) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "skill-command.gui-main-menu"),
                NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(loreLines(messages.text(player, "skill-command.gui-main-menu-lore"), NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack controlsItem(Player player) {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "skill-command.gui-controls"),
                NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(messages.lines(player, "skill-command.gui-control-lines").stream()
                .flatMap(line -> MenuLoreLayout.wrap(line).stream())
                .map(line -> lore(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack cooldownOverviewItem(Player player) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, "skill-command.gui-hud"),
                NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(loreLines(messages.text(player, "skill-command.gui-hud-lore"), NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack navigationShortcut(Player player, Material material, String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, nameKey), NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(loreLines(messages.text(player, loreKey), NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private Component lore(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private void addLore(List<Component> target, String value, NamedTextColor color) {
        target.addAll(loreLines(value, color));
    }

    private List<Component> loreLines(String value, NamedTextColor color) {
        return MenuLoreLayout.wrap(value).stream().map(line -> lore(line, color)).toList();
    }

    private Archetype parseArchetype(String value) {
        try {
            return Archetype.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return Archetype.UNCHOSEN;
        }
    }

    private Material skillIcon(String id) {
        return switch (id) {
            case "heal" -> Material.GHAST_TEAR;
            case "renewal" -> Material.TOTEM_OF_UNDYING;
            case "sanctuary" -> Material.BEACON;
            case "fate-seal" -> Material.ENCHANTED_BOOK;
            case "war-cry", "blood-rage" -> Material.IRON_AXE;
            case "shield-wall", "fortress" -> Material.SHIELD;
            case "shadow-step", "smoke-bomb" -> Material.ENDER_PEARL;
            case "venom-blade", "toxic-burst" -> Material.SPIDER_EYE;
            case "fireball", "meteor" -> Material.FIRE_CHARGE;
            case "frost-nova" -> Material.PACKED_ICE;
            case "mana-surge" -> Material.AMETHYST_SHARD;
            case "power-shot", "volley", "piercing-shot" -> Material.BOW;
            case "beast-call" -> Material.WOLF_SPAWN_EGG;
            case "improvisation" -> Material.BUNDLE;
            case "second-wind" -> Material.FEATHER;
            case "battle-trance" -> Material.NETHERITE_SWORD;
            case "arcane-edge" -> Material.ECHO_SHARD;
            case "life-thread", "soul-resonance" -> Material.HEART_OF_THE_SEA;
            case "omen-reversal" -> Material.ENDER_EYE;
            case "rallying-standard", "unbroken-line" -> Material.WHITE_BANNER;
            case "ruin-charge" -> Material.NETHERITE_AXE;
            case "void-cut", "vanished-horizon" -> Material.ENDER_PEARL;
            case "plague-bloom" -> Material.FERMENTED_SPIDER_EYE;
            case "ash-domain" -> Material.MAGMA_CREAM;
            case "time-lock" -> Material.CLOCK;
            case "axiomatic-break" -> Material.END_CRYSTAL;
            case "gale-step", "horizon-arrow" -> Material.SPECTRAL_ARROW;
            case "pack-hunt" -> Material.GOAT_HORN;
            case "adaptive-arsenal" -> Material.SMITHING_TABLE;
            case "rune-reversal" -> Material.RECOVERY_COMPASS;
            case "open-form" -> Material.NETHER_STAR;
            default -> Material.BOOK;
        };
    }

    private void applySkillItemModel(ItemMeta meta, String skillId) {
        meta.setItemModel(new NamespacedKey("originalrpg", SkillItemModels.key(skillId)));
    }

    private ItemStack learningItem(Player player, String id) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        applySkillItemModel(meta, id);
        meta.displayName(Component.text(messages.text(player, "skill-command.gui-learning", Map.of(
                "skill", skillName(player, id))), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(loreLines(messages.text(player, "skill-command.gui-learning-lore"), NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack upgradingItem(Player player, String id) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        applySkillItemModel(meta, id);
        meta.displayName(Component.text(messages.text(player, "skill-command.gui-upgrading", Map.of(
                "skill", skillName(player, id))), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(loreLines(messages.text(player, "skill-command.gui-upgrading-lore"), NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private void requestLearn(Player player, String id, SkillMenu sourceMenu, int sourceSlot) {
        UUID playerId = player.getUniqueId();
        if (!beginOperation(learningPlayers, player)) {
            player.sendActionBar(Component.text(
                    messages.text(player, "skill-command.operation-pending"), NamedTextColor.YELLOW));
            return;
        }
        if (sourceMenu != null) {
            if (!isViewing(player, sourceMenu)) {
                learningPlayers.remove(playerId, player);
                return;
            }
            if (sourceSlot >= 0) {
                sourceMenu.set(sourceSlot, learningItem(player, id), null);
                player.updateInventory();
            }
        }
        profiles.get(playerId)
                .thenCombine(branches.branches(playerId), ProfileAndBranches::new)
                .thenCombine(skills.skills(playerId), LearnContext::new)
                .whenComplete((context, error) -> sync(() -> {
            if (!currentSession(player)) {
                learningPlayers.remove(playerId, player);
                return;
            }
            if (error != null) {
                player.sendActionBar(Component.text(
                        messages.text(player, "skill-command.load-error"), NamedTextColor.RED));
                finishLearning(player, sourceMenu);
                return;
            }
            learn(player, context.profileAndBranches().profile(), context.profileAndBranches().branches(),
                    context.learnedSkills(), id, sourceMenu);
        }));
    }

    private void learn(
            Player player, PlayerProfile profile, Map<Integer, String> selectedBranches,
            Map<String, SkillProgress> learnedSkills, String id, SkillMenu sourceMenu
    ) {
        ConfigurationSection skill = plugin.getConfig().getConfigurationSection("skills." + id);
        if (skill == null) {
            player.sendMessage(Component.text(messages.text(player, "skill-command.missing"), NamedTextColor.RED));
            finishLearning(player, sourceMenu);
            return;
        }
        Archetype required = parseArchetype(skill.getString("class", "UNCHOSEN"));
        if (profile.archetype() != required) {
            player.sendMessage(Component.text(messages.text(player, "skill-command.class-mismatch"), NamedTextColor.RED));
            finishLearning(player, sourceMenu);
            return;
        }
        int minimumAscension = Math.clamp(skill.getInt("minimum-ascension", 0), 0, 3);
        if (profile.ascension() < minimumAscension) {
            player.sendMessage(Component.text(messages.text(player, "skill-command.ascension-required", Map.of(
                    "ascension", minimumAscension)), NamedTextColor.RED));
            finishLearning(player, sourceMenu);
            return;
        }
        int minimumLevel = Math.max(1, skill.getInt("minimum-level", 1));
        if (profile.level() < minimumLevel) {
            player.sendMessage(Component.text(messages.text(player, "skill-command.level-required", Map.of(
                    "level", minimumLevel)), NamedTextColor.RED));
            finishLearning(player, sourceMenu);
            return;
        }
        String requiredBranch = skill.getString("branch", "");
        if (!requiredBranch.isBlank() && !selectedBranches.containsValue(requiredBranch)) {
            player.sendMessage(Component.text(messages.text(player, "skill-command.branch-required", Map.of(
                    "branch", messages.text(player, "class-branches." + requiredBranch))), NamedTextColor.RED));
            finishLearning(player, sourceMenu);
            return;
        }
        for (String prerequisite : skill.getStringList("prerequisites")) {
            if (!learnedSkills.containsKey(prerequisite)) {
                player.sendMessage(Component.text(messages.text(player, "skill-command.prerequisite-required", Map.of(
                        "skill", skillName(player, prerequisite))), NamedTextColor.RED));
                finishLearning(player, sourceMenu);
                return;
            }
        }
        ConfigurationSection requirements = skill.getConfigurationSection("requirements");
        if (requirements != null) {
            for (String key : requirements.getKeys(false)) {
                CoreAttribute attribute = CoreAttribute.valueOf(key);
                if (profile.attribute(attribute) < requirements.getInt(key)) {
                    player.sendMessage(Component.text(messages.text(player, "skill-command.attribute-required", Map.of(
                            "attribute", attributeName(player, attribute), "required", requirements.getInt(key))),
                            NamedTextColor.RED));
                    finishLearning(player, sourceMenu);
                    return;
                }
            }
        }
        int cost = skill.getInt("skill-points", 1);
        int remainingPoints = SkillMenuPolicy.remainingPoints(profile.skillPoints(), learnedSkills.values());
        if (remainingPoints < cost) {
            player.sendActionBar(Component.text(
                    messages.text(player, "skill-command.points-insufficient"), NamedTextColor.RED));
            finishLearning(player, sourceMenu);
            return;
        }
        skills.learn(player.getUniqueId(), id, cost).whenComplete((result, error) ->
                sync(() -> {
                    if (!currentSession(player)) {
                        learningPlayers.remove(player.getUniqueId(), player);
                        return;
                    }
                    if (error != null) {
                        player.sendMessage(Component.text(messages.text(player, "skill-command.learn-failed"), NamedTextColor.RED));
                    } else if (result.status() == SkillLearnResult.Status.SUCCESS) {
                        rememberLearned(player.getUniqueId(), id);
                        refreshJourney(player);
                        Component learned = Component.text(messages.text(player, "skill-command.learned", Map.of(
                                "skill", skillName(player, id))), NamedTextColor.GREEN);
                        if (sourceMenu == null) player.sendMessage(learned);
                        else player.sendActionBar(learned);
                    } else if (result.status() == SkillLearnResult.Status.ALREADY_LEARNED) {
                        player.sendMessage(Component.text(messages.text(player, "skill-command.already-learned"), NamedTextColor.YELLOW));
                    } else if (result.status() == SkillLearnResult.Status.INSUFFICIENT_POINTS) {
                        player.sendMessage(Component.text(messages.text(player, "skill-command.points-insufficient"), NamedTextColor.RED));
                    } else {
                        player.sendMessage(Component.text(messages.text(player, "skill-command.learn-invalid"), NamedTextColor.RED));
                    }
                    finishLearning(player, sourceMenu);
                }));
    }

    private void requestUpgrade(
            Player player, String id, Integer renderedLevel, SkillMenu sourceMenu, int sourceSlot
    ) {
        ConfigurationSection definition = plugin.getConfig().getConfigurationSection("skills." + id);
        if (definition == null) {
            player.sendMessage(Component.text(messages.text(player, "skill-command.missing"), NamedTextColor.RED));
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!beginOperation(learningPlayers, player)) {
            player.sendActionBar(Component.text(
                    messages.text(player, "skill-command.operation-pending"), NamedTextColor.YELLOW));
            return;
        }
        if (sourceMenu != null) {
            if (!isViewing(player, sourceMenu)) {
                learningPlayers.remove(playerId, player);
                return;
            }
            if (sourceSlot >= 0) {
                sourceMenu.set(sourceSlot, upgradingItem(player, id), null);
                player.updateInventory();
            }
        }
        profiles.get(playerId).thenCombine(skills.skill(playerId, id), UpgradeContext::new)
                .whenComplete((context, loadError) -> sync(() -> {
            if (!currentSession(player)) {
                learningPlayers.remove(playerId, player);
                return;
            }
            if (loadError != null) {
                player.sendActionBar(Component.text(
                        messages.text(player, "skill-command.load-error"), NamedTextColor.RED));
                finishLearning(player, sourceMenu);
                return;
            }
            if (context.progress().isEmpty()) {
                player.sendActionBar(Component.text(
                        messages.text(player, "skill-command.upgrade-not-learned"), NamedTextColor.RED));
                finishLearning(player, sourceMenu);
                return;
            }
            SkillProgress current = context.progress().orElseThrow();
            int expectedLevel = renderedLevel == null ? current.level() : renderedLevel;
            int stageMaximum = skillMaximumLevel(context.profile().ascension());
            int cost = skillUpgradeCost(expectedLevel);
            skills.upgrade(playerId, id, expectedLevel, stageMaximum, cost)
                    .whenComplete((result, upgradeError) -> sync(() -> {
                if (!currentSession(player)) {
                    learningPlayers.remove(playerId, player);
                    return;
                }
                if (upgradeError != null) {
                    player.sendActionBar(Component.text(
                            messages.text(player, "skill-command.upgrade-failed"), NamedTextColor.RED));
                } else {
                    showUpgradeResult(player, id, context.profile(), result, cost, sourceMenu != null);
                }
                finishLearning(player, sourceMenu);
            }));
        }));
    }

    private void showUpgradeResult(
            Player player, String id, PlayerProfile profile, SkillUpgradeResult result,
            int chargedCost, boolean compact
    ) {
        Component message;
        switch (result.status()) {
            case SUCCESS -> message = Component.text(messages.text(player, "skill-command.upgraded", Map.of(
                    "skill", skillName(player, id),
                    "level", result.progress().level(),
                    "cost", chargedCost)), NamedTextColor.GREEN);
            case NOT_LEARNED -> message = Component.text(
                    messages.text(player, "skill-command.upgrade-not-learned"), NamedTextColor.RED);
            case MAX_LEVEL -> {
                boolean absolute = result.progress() != null
                        && result.progress().level() >= skillMaximumLevel(3);
                String key = absolute ? "skill-command.upgrade-maximum"
                        : "skill-command.upgrade-ascension-locked";
                message = Component.text(messages.text(player, key, Map.of(
                        "ascension", Math.min(3, profile.ascension() + 1))),
                        absolute ? NamedTextColor.GOLD : NamedTextColor.YELLOW);
            }
            case STALE_LEVEL -> message = Component.text(
                    messages.text(player, "skill-command.upgrade-stale"), NamedTextColor.YELLOW);
            case INSUFFICIENT_POINTS -> message = Component.text(
                    messages.text(player, "skill-command.points-insufficient"), NamedTextColor.RED);
            case INVALID -> message = Component.text(
                    messages.text(player, "skill-command.upgrade-invalid"), NamedTextColor.RED);
            default -> throw new IllegalStateException("Unhandled skill-upgrade result " + result.status());
        }
        if (compact) player.sendActionBar(message);
        else player.sendMessage(message);
    }

    private void finishLearning(Player player, SkillMenu sourceMenu) {
        learningPlayers.remove(player.getUniqueId(), player);
        if (sourceMenu != null && isViewing(player, sourceMenu)) refreshTree(player, sourceMenu);
    }

    private boolean isViewing(Player player, SkillMenu menu) {
        return player.getOpenInventory().getTopInventory().getHolder() == menu;
    }

    /** Resolve lazily because RpgGuide is enabled after the skills plugin. */
    private void refreshJourney(Player player) {
        JourneyService journey = plugin.getServer().getServicesManager().load(JourneyService.class);
        if (journey != null) journey.refresh(player);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("learn", "upgrade", "cast", "castslot", "slot", "hud");
        if (args.length == 2 && (args[0].equalsIgnoreCase("slot")
                || args[0].equalsIgnoreCase("castslot"))) return List.of("1", "2", "3", "4");
        if (args.length == 2 && (args[0].equalsIgnoreCase("learn")
                || args[0].equalsIgnoreCase("upgrade") || args[0].equalsIgnoreCase("cast"))) {
            ConfigurationSection skills = plugin.getConfig().getConfigurationSection("skills");
            if (skills == null) return List.of();
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return skills.getKeys(false).stream().filter(value -> value.startsWith(prefix)).sorted().toList();
        }
        return List.of();
    }

    private record SkillAndProfile(java.util.Optional<SkillProgress> skill, PlayerProfile profile) {
    }

    private record LearnedAndProfile(Map<String, SkillProgress> skills, PlayerProfile profile) {
    }

    private record ProfileAndBranches(PlayerProfile profile, Map<Integer, String> branches) {
    }

    private record LearnContext(ProfileAndBranches profileAndBranches, Map<String, SkillProgress> learnedSkills) {
    }

    private record UpgradeContext(PlayerProfile profile, java.util.Optional<SkillProgress> progress) {
    }

    private record SkillLock(SkillMenuPolicy.Gate gate, String reason) {
        private SkillLock {
            java.util.Objects.requireNonNull(gate, "gate");
            java.util.Objects.requireNonNull(reason, "reason");
        }
    }

    private record NextSkillTarget(String skillId, SkillLock lock) {
        private NextSkillTarget {
            java.util.Objects.requireNonNull(skillId, "skillId");
        }

        private SkillMenuPolicy.Gate gate() {
            return lock == null ? null : lock.gate();
        }
    }

    private record EquipmentTraits(
            Set<String> activeSkillRunes,
            Set<String> dormantSkillRunes,
            boolean healingBoost,
            int skillHaste,
            int healing
    ) {
    }

    private record HudState(String focusSkillId, long visibleUntil) {
    }

    private record BattleTranceState(long expiresAt, long lastHitAt, int stacks) {
    }

    private record DefenseState(
            SkillDefenseRules.Mode mode, long expiresAt,
            org.bukkit.Location origin, org.bukkit.util.Vector facing
    ) {
    }

    private record LineLinkState(UUID guardian, long expiresAt) {
    }

    private record StandardState(
            UUID owner, long expiresAt, org.bukkit.Location origin, double radius
    ) {
    }

    private record FateSealState(UUID owner, long expiresAt) {
    }

    private String skillName(CommandSender audience, String id) {
        return messages.text(audience, "skills.names." + id);
    }

    private String attributeName(CommandSender audience, CoreAttribute attribute) {
        return messages.text(audience, "attributes." + attribute.name().toLowerCase(Locale.ROOT));
    }
}
