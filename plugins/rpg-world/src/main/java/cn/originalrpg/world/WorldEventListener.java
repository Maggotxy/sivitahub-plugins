package cn.originalrpg.world;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.rules.CombatRules;
import cn.originalrpg.api.rules.PityRules;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.MobAppearanceService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.ui.HudGlyphs;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.AbstractCubeMob;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Witch;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class WorldEventListener implements Listener {
    private final Plugin plugin;
    private final MessageService messages;
    private final ProfileService profiles;
    private final NamespacedKey bloodMoonMob;
    private final NamespacedKey huntMaterial;
    private final NamespacedKey bloodMoonRare;
    private final NamespacedKey bloodMoonArchetype;
    private final NamespacedKey bloodMoonSkillReadyAt;
    private final NamespacedKey mobLevel;
    private final NamespacedKey eliteAffix;
    private final NamespacedKey pickupOwner;
    private final NamespacedKey bloodMoonFailures;
    private final Map<UUID, HuntChain> chains = new HashMap<>();
    private final Set<UUID> activeBloodMoons = new HashSet<>();
    private final Map<UUID, BossBar> bloodMoonBars = new HashMap<>();
    private final Set<UUID> bloodMoonCreepers = new HashSet<>();
    private BukkitTask feedbackTask;
    private BukkitTask creeperTelegraphTask;

    WorldEventListener(Plugin plugin, MessageService messages, ProfileService profiles) {
        this.plugin = plugin;
        this.messages = messages;
        this.profiles = profiles;
        bloodMoonMob = new NamespacedKey(plugin, "blood_moon");
        huntMaterial = new NamespacedKey(plugin, "hunt_material");
        bloodMoonRare = new NamespacedKey(plugin, "blood_moon_rare");
        bloodMoonArchetype = new NamespacedKey(plugin, "blood_moon_archetype");
        bloodMoonSkillReadyAt = new NamespacedKey(plugin, "blood_moon_skill_ready_at");
        mobLevel = java.util.Objects.requireNonNull(NamespacedKey.fromString("rpgmobs:level"));
        eliteAffix = java.util.Objects.requireNonNull(NamespacedKey.fromString("rpgmobs:elite_affix"));
        pickupOwner = java.util.Objects.requireNonNull(NamespacedKey.fromString("rpgmarket:pickup_owner"));
        bloodMoonFailures = new NamespacedKey(plugin, "blood_moon_material_failures");
    }

    void start() {
        int migrated = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (migrateLegacyBloodMoonMob(entity)) migrated++;
                trackBloodMoonCreeper(entity);
            }
        }
        if (migrated > 0) plugin.getLogger().info(
                "Migrated loaded legacy blood-moon monsters=" + migrated + " (removed uniform glow/strength)");
        // A plugin reload or server boot may happen halfway through an existing
        // blood moon. Warm the state without replaying the dramatic start title.
        for (World world : plugin.getServer().getWorlds()) {
            if (!isBloodMoon(world)) continue;
            activeBloodMoons.add(world.getUID());
            world.getPlayers().forEach(this::updateBloodMoonBar);
        }
        feedbackTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateWorldFeedback, 20L, 100L);
        creeperTelegraphTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::telegraphIgnitedCreepers, 10L, 10L);
    }

    void stop() {
        if (feedbackTask != null) feedbackTask.cancel();
        if (creeperTelegraphTask != null) creeperTelegraphTask.cancel();
        for (Map.Entry<UUID, BossBar> entry : bloodMoonBars.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null) player.hideBossBar(entry.getValue());
        }
        bloodMoonBars.clear();
        activeBloodMoons.clear();
        bloodMoonCreepers.clear();
        chains.clear();
    }

    private void updateWorldFeedback() {
        Set<UUID> activePlayers = new HashSet<>();
        // All players in this feedback pulse share one server-load snapshot.
        // Resolving the optional service once avoids repeated service-manager
        // lookups for every ambient burst and remains safe across hot disables.
        ServerLoadState load = load();
        for (World world : plugin.getServer().getWorlds()) {
            if (!isOverworld(world)) continue;
            boolean active = isBloodMoon(world);
            if (active && activeBloodMoons.add(world.getUID())) {
                if (world.getTime() <= WorldEventRules.NIGHT_START + 200L) {
                    world.getPlayers().forEach(this::bloodMoonStarted);
                } else {
                    world.getPlayers().forEach(this::updateBloodMoonBar);
                }
            } else if (!active && activeBloodMoons.remove(world.getUID())) {
                world.getPlayers().forEach(this::bloodMoonEnded);
            }
            if (!active) continue;
            for (Player player : world.getPlayers()) {
                activePlayers.add(player.getUniqueId());
                updateBloodMoonBar(player);
                ambientBloodMoon(player, load);
            }
        }
        for (UUID playerId : new HashSet<>(bloodMoonBars.keySet())) {
            if (activePlayers.contains(playerId)) continue;
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) player.hideBossBar(bloodMoonBars.get(playerId));
            bloodMoonBars.remove(playerId);
        }
    }

    private void bloodMoonStarted(Player player) {
        player.sendMessage(Component.text(messages.text(player, "world.blood-moon-start"), NamedTextColor.RED));
        player.showTitle(Title.title(
                Component.text(messages.text(player, "world.blood-moon-title"), NamedTextColor.DARK_RED),
                Component.text(messages.text(player, "world.blood-moon-subtitle"), NamedTextColor.GRAY)));
        updateBloodMoonBar(player);
        VisualMode mode = VisualMode.forPlayer(player);
        float volume = mode.volume(plugin.getConfig().getDouble("visual-effects.sound-volume", 0.65));
        if (volume > 0.0F) player.playSound(player.getLocation(), Sound.ENTITY_WITHER_SPAWN,
                SoundCategory.AMBIENT, volume, 0.65F);
        burst(player, Particle.DUST, plugin.getConfig().getInt("visual-effects.event-particles", 20),
                new Particle.DustOptions(Color.fromRGB(155, 15, 35), 1.4F));
    }

    private void bloodMoonEnded(Player player) {
        player.sendMessage(Component.text(messages.text(player, "world.blood-moon-end"), NamedTextColor.GRAY));
        hideBloodMoonBar(player);
        VisualMode mode = VisualMode.forPlayer(player);
        float volume = mode.volume(plugin.getConfig().getDouble("visual-effects.sound-volume", 0.65));
        if (volume > 0.0F) player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH,
                SoundCategory.AMBIENT, volume * 0.7F, 1.35F);
    }

    private void updateBloodMoonBar(Player player) {
        long time = player.getWorld().getTime();
        int every = Math.max(1, plugin.getConfig().getInt("blood-moon.every-nights", 7));
        WorldEventRules.BloodMoonTiming timing = WorldEventRules.bloodMoon(
                player.getWorld().getFullTime(), time, every);
        float progress = WorldEventRules.bloodMoonProgress(time);
        long seconds = timing.remainingSeconds();
        // Keep the custom font on the icon child only. Appending normal text to
        // the icon component makes the client inherit the bitmap-only HUD font, where
        // ordinary digits and CJK glyphs do not exist and render as tofu.
        Component label = Component.text(messages.text(player, "world.blood-moon-bar", Map.of(
                "seconds", seconds)), NamedTextColor.RED);
        Component name = HudGlyphs.available(player)
                ? HudGlyphs.withIcon(HudGlyphs.BLOOD_MOON, label)
                : label;
        BossBar bar = bloodMoonBars.get(player.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            bloodMoonBars.put(player.getUniqueId(), bar);
            player.showBossBar(bar);
        } else {
            bar.name(name);
            bar.progress(progress);
        }
    }

    private void hideBloodMoonBar(Player player) {
        BossBar bar = bloodMoonBars.remove(player.getUniqueId());
        if (bar != null) player.hideBossBar(bar);
    }

    private void ambientBloodMoon(Player player, ServerLoadState load) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        if (load == ServerLoadState.CRITICAL) return;
        int configured = load == ServerLoadState.NORMAL
                ? plugin.getConfig().getInt("visual-effects.ambient-particles", 3) : 1;
        burst(player, Particle.ASH, configured, null, load);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        LivingEntity monster = event.getEntity();
        if (!(monster instanceof Enemy) || !isBloodMoon(monster.getWorld())
                || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        monster.getPersistentDataContainer().set(bloodMoonMob, PersistentDataType.BYTE, (byte) 1);
        String archetype = bloodMoonArchetype(monster);
        monster.getPersistentDataContainer().set(bloodMoonArchetype, PersistentDataType.STRING, archetype);
        if (monster instanceof Creeper) bloodMoonCreepers.add(monster.getUniqueId());
        int minimumSpecialLevel = Math.clamp(plugin.getConfig().getInt(
                "blood-moon.special-ability-minimum-player-level", 5), 1, 30);
        if (!WorldEventRules.bloodMoonSpecialsAllowed(
                lowestNearbyEffectiveLevel(monster, 96.0), minimumSpecialLevel)) return;
        double rareChance = Math.clamp(plugin.getConfig().getDouble("blood-moon.rare-mob-chance", 0.08), 0.0, 1.0);
        if (ThreadLocalRandom.current().nextDouble() >= rareChance) return;
        monster.getPersistentDataContainer().set(bloodMoonRare, PersistentDataType.STRING, archetype);
        Player audience = nearestAudience(monster);
        String rareName = messages.text(audience, "world.blood-moon-mobs." + archetype + ".name");
        Integer level = monster.getPersistentDataContainer().get(mobLevel, PersistentDataType.INTEGER);
        String affix = monster.getPersistentDataContainer().get(eliteAffix, PersistentDataType.STRING);
        String elitePrefix = affix == null ? "" : messages.text(audience, "mobs.elite-name", Map.of(
                "affix", messages.text(audience, "mobs.affixes." + affix))) + " ";
        String nameKey = level == null ? "world.rare-mob-name-unscaled" : "world.rare-mob-name";
        monster.customName(Component.text(messages.text(audience, nameKey, Map.of(
                "elite", elitePrefix,
                "name", rareName,
                "level", level == null ? "" : level)), NamedTextColor.DARK_RED));
        monster.setCustomNameVisible(true);
        empowerRareArchetype(monster, archetype);
        MobAppearanceService appearances = appearances();
        if (appearances != null) appearances.applyBloodMoonModel(monster, archetype);
        rareMobSpawned(monster);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBloodMoonAttack(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)) return;
        LivingEntity monster = attackingMonster(event);
        if (monster == null || !monster.getPersistentDataContainer().has(
                bloodMoonMob, PersistentDataType.BYTE)) return;
        PlayerProfile targetProfile = profiles.cached(target.getUniqueId()).orElse(null);
        int minimumSpecialLevel = Math.clamp(plugin.getConfig().getInt(
                "blood-moon.special-ability-minimum-player-level", 5), 1, 30);
        if (targetProfile == null || !WorldEventRules.bloodMoonSpecialsAllowed(
                targetProfile.effectiveLevel(), minimumSpecialLevel) || !claimBloodSkill(monster)) return;
        String archetype = monster.getPersistentDataContainer().getOrDefault(
                bloodMoonArchetype, PersistentDataType.STRING, "moon-touched");
        switch (archetype) {
            case "bloodfiend" -> {
                double maximum = monster.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                        ? monster.getHealth()
                        : monster.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                monster.setHealth(Math.min(maximum, monster.getHealth() + Math.max(1.0, event.getFinalDamage() * 0.5)));
                target.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 60, 0));
            }
            case "eclipse-archer" -> {
                int freeze = Math.min(target.getMaxFreezeTicks(), Math.max(target.getFreezeTicks(), 70));
                target.setFreezeTicks(freeze);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1));
            }
            case "shadow-weaver" -> {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2));
                target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 0));
            }
            case "rift-walker" -> {
                target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 35, 0));
                blinkBehind(monster, target);
            }
            case "curse-weaver" -> {
                for (Player nearby : target.getWorld().getNearbyPlayers(target.getLocation(), 4.5)) {
                    nearby.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0));
                    nearby.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 50, 0));
                }
            }
            case "night-raider" -> {
                target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0));
                double maximum = monster.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                        ? monster.getHealth()
                        : monster.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                monster.setHealth(Math.min(maximum, monster.getHealth() + 2.0));
            }
            default -> target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 35, 0));
        }
        bloodSkillFeedback(monster, target, archetype);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBloodMoonExplosion(EntityExplodeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || !creeper.getPersistentDataContainer().has(
                bloodMoonMob, PersistentDataType.BYTE)) return;
        // The event monster is dangerous to players, not a free terrain-grief tool.
        event.blockList().clear();
        for (Player player : creeper.getWorld().getNearbyPlayers(creeper.getLocation(), 6.0)) {
            player.setFireTicks(Math.max(player.getFireTicks(), 45));
            player.sendActionBar(Component.text(messages.text(player,
                    "world.blood-moon-mobs.skill-trigger", Map.of(
                            "mob", messages.text(player, "world.blood-moon-mobs.ember-core.name"),
                            "skill", messages.text(player, "world.blood-moon-mobs.ember-core.skill"))),
                    NamedTextColor.RED));
        }
        bloodMoonCreepers.remove(creeper.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBloodMoonBed(PlayerBedEnterEvent event) {
        if (!isBloodMoon(event.getPlayer().getWorld())) return;
        event.setCancelled(true);
        event.getPlayer().setRespawnLocation(event.getBed().getLocation(), false);
        event.getPlayer().sendMessage(Component.text(messages.text(event.getPlayer(),
                "world.blood-moon-bed"), NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBloodMoonTimeSkip(TimeSkipEvent event) {
        if (event.getSkipReason() == TimeSkipEvent.SkipReason.NIGHT_SKIP && isBloodMoon(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        MobAppearanceService appearances = appearances();
        if (appearances != null) appearances.clear(event.getEntity());
        bloodMoonCreepers.remove(event.getEntity().getUniqueId());
        Player killer = event.getEntity().getKiller();
        if (killer == null || !isOverworld(killer.getWorld())) return;
        if (!(event.getEntity() instanceof Enemy)) {
            chains.remove(killer.getUniqueId());
            return;
        }
        Integer level = event.getEntity().getPersistentDataContainer().get(mobLevel, PersistentDataType.INTEGER);
        PlayerProfile profile = profiles.cached(killer.getUniqueId()).orElse(null);
        if (level == null || profile == null || !CombatRules.allowsCustomDrops(level, profile.effectiveLevel())) {
            chains.remove(killer.getUniqueId());
            return;
        }
        Instant now = Instant.now();
        String type = event.getEntityType().name();
        String variant = huntVariant(event.getEntity());
        String targetKey = WorldEventRules.huntIdentity(type, variant);
        HuntChain previous = chains.get(killer.getUniqueId());
        double resetDistance = Math.max(16.0, plugin.getConfig().getDouble("hunt-chain.reset-distance", 128.0));
        long resetSeconds = Math.max(1L, plugin.getConfig().getLong("hunt-chain.reset-seconds", 300L));
        boolean sameWorld = previous != null && previous.worldId().equals(killer.getWorld().getUID());
        double distanceSquared = sameWorld
                ? previous.location().distanceSquared(killer.getLocation()) : Double.POSITIVE_INFINITY;
        boolean continues = previous != null && WorldEventRules.continuesHunt(
                previous.targetKey(), targetKey,
                sameWorld, distanceSquared, resetDistance,
                elapsedMillis(previous.lastKill(), now), resetSeconds);
        int count = continues ? previous.count() + 1 : 1;
        int huntFailures = continues ? previous.materialFailures() : 0;

        double perStack = plugin.getConfig().getDouble("hunt-chain.chance-per-stack", 0.002);
        double maximum = plugin.getConfig().getDouble("hunt-chain.maximum-bonus-chance", 0.08);
        double chainChance = WorldEventRules.huntBonus(count, perStack, maximum);
        boolean bloodMoonKill = event.getEntity().getPersistentDataContainer().has(
                bloodMoonMob, PersistentDataType.BYTE);
        double bloodMoonChance = bloodMoonKill
                ? plugin.getConfig().getDouble("blood-moon.bonus-drop-chance", 0.08) : 0.0;
        double rareBonus = event.getEntity().getPersistentDataContainer().has(bloodMoonRare, PersistentDataType.STRING)
                ? plugin.getConfig().getDouble("blood-moon.rare-mob-extra-drop-chance", 0.22) : 0.0;
        double totalChance = Math.clamp(chainChance + bloodMoonChance + rareBonus, 0.0, 1.0);
        double roll = ThreadLocalRandom.current().nextDouble();
        int huntGuarantee = Math.clamp(plugin.getConfig().getInt(
                "hunt-chain.material-drop-pity", 30), 1, 10_000);
        PityRules.Outcome huntOutcome = PityRules.attempt(
                huntFailures, huntGuarantee, totalChance, roll);
        int bloodFailures = Math.max(0, killer.getPersistentDataContainer().getOrDefault(
                bloodMoonFailures, PersistentDataType.INTEGER, 0));
        int bloodGuarantee = Math.clamp(plugin.getConfig().getInt(
                "blood-moon.material-drop-pity", 12), 1, 10_000);
        PityRules.Outcome bloodOutcome = bloodMoonKill
                ? PityRules.attempt(bloodFailures, bloodGuarantee, totalChance, roll) : null;
        boolean dropped = huntOutcome.dropped() || bloodOutcome != null && bloodOutcome.dropped();
        boolean guaranteed = huntOutcome.guaranteed() || bloodOutcome != null && bloodOutcome.guaranteed();
        int nextHuntFailures = dropped ? 0 : huntOutcome.failures();
        if (bloodMoonKill) {
            killer.getPersistentDataContainer().set(bloodMoonFailures, PersistentDataType.INTEGER,
                    dropped ? 0 : bloodOutcome.failures());
        }
        chains.put(killer.getUniqueId(), new HuntChain(targetKey, type, variant, count,
                nextHuntFailures, killer.getWorld().getUID(), killer.getLocation().clone(), now));

        if (dropped) {
            event.getDrops().add(createHuntMaterial(killer, count, bloodMoonChance > 0));
            rareHuntDrop(killer);
            if (guaranteed) killer.sendMessage(Component.text(messages.text(killer,
                    "world.pity-drop", Map.of("item", messages.text(killer, bloodMoonKill
                            ? "world.items.blood-moon-essence" : "world.items.hunt-crystal"))),
                    NamedTextColor.GOLD));
        }
        double bonusPercent = Math.round(chainChance * 1000) / 10.0;
        killer.sendActionBar(Component.text(messages.text(killer, "world.hunt-chain-prefix"), NamedTextColor.RED)
                .append(Component.translatable(event.getEntityType().translationKey(), NamedTextColor.WHITE))
                .append(huntVariantLabel(killer, variant))
                .append(Component.text(messages.text(killer, "world.hunt-chain-detail", Map.of(
                        "count", count, "bonus", bonusPercent,
                        "pity", nextHuntFailures, "guarantee", huntGuarantee)), NamedTextColor.RED)));
        if (plugin.getConfig().getIntegerList("hunt-chain.feedback-milestones").contains(count)) {
            huntMilestone(killer, count);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity living) trackBloodMoonCreeper(living);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getEntities()) {
            if (entity instanceof Creeper) bloodMoonCreepers.remove(entity.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        double resetDistance = Math.max(16.0, plugin.getConfig().getDouble("hunt-chain.teleport-reset-distance", 128.0));
        if (event.getFrom().getWorld() != event.getTo().getWorld()
                || event.getFrom().distanceSquared(event.getTo()) > resetDistance * resetDistance) {
            chains.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        if (isBloodMoon(event.player().getWorld())) updateBloodMoonBar(event.player());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        chains.remove(event.getPlayer().getUniqueId());
        hideBloodMoonBar(event.getPlayer());
        if (isBloodMoon(event.getPlayer().getWorld())) updateBloodMoonBar(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        chains.remove(event.getPlayer().getUniqueId());
        hideBloodMoonBar(event.getPlayer());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        chains.remove(event.player().getUniqueId());
        hideBloodMoonBar(event.player());
    }

    private String bloodMoonArchetype(LivingEntity monster) {
        if (monster instanceof Zombie) return "bloodfiend";
        if (monster instanceof AbstractSkeleton) return "eclipse-archer";
        if (monster instanceof Spider) return "shadow-weaver";
        if (monster instanceof Creeper) return "ember-core";
        if (monster instanceof Enderman) return "rift-walker";
        if (monster instanceof Witch) return "curse-weaver";
        if (monster instanceof Phantom) return "night-raider";
        return "moon-touched";
    }

    private boolean migrateLegacyBloodMoonMob(LivingEntity monster) {
        if (!monster.getPersistentDataContainer().has(bloodMoonMob, PersistentDataType.BYTE)
                || monster.getPersistentDataContainer().has(bloodMoonArchetype, PersistentDataType.STRING)) {
            return false;
        }
        monster.removePotionEffect(PotionEffectType.GLOWING);
        monster.removePotionEffect(PotionEffectType.STRENGTH);
        String archetype = bloodMoonArchetype(monster);
        monster.getPersistentDataContainer().set(bloodMoonArchetype, PersistentDataType.STRING, archetype);
        if (monster instanceof Creeper) bloodMoonCreepers.add(monster.getUniqueId());
        if (monster.getPersistentDataContainer().has(bloodMoonRare, PersistentDataType.STRING)) {
            monster.getPersistentDataContainer().set(bloodMoonRare, PersistentDataType.STRING, archetype);
            empowerRareArchetype(monster, archetype);
            MobAppearanceService appearances = appearances();
            if (appearances != null) appearances.applyBloodMoonModel(monster, archetype);
        }
        return true;
    }

    private void empowerRareArchetype(LivingEntity monster, String archetype) {
        switch (archetype) {
            case "bloodfiend" -> {
                monster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                        PotionEffect.INFINITE_DURATION, 1));
                monster.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                        PotionEffect.INFINITE_DURATION, 0));
            }
            case "eclipse-archer" -> monster.addPotionEffect(new PotionEffect(
                    PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 0));
            case "shadow-weaver" -> monster.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1));
            case "ember-core" -> {
                Creeper creeper = (Creeper) monster;
                creeper.setPowered(true);
                creeper.setMaxFuseTicks(40);
                creeper.setExplosionRadius(3);
            }
            case "rift-walker" -> monster.addPotionEffect(new PotionEffect(
                    PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0));
            case "curse-weaver" -> monster.addPotionEffect(new PotionEffect(
                    PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 1));
            case "night-raider" -> {
                Phantom phantom = (Phantom) monster;
                phantom.setSize(Math.min(4, phantom.getSize() + 1));
            }
            default -> monster.addPotionEffect(new PotionEffect(
                    PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0));
        }
    }

    private LivingEntity attackingMonster(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity living && living instanceof Enemy) return living;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity living && living instanceof Enemy) return living;
        return null;
    }

    private boolean claimBloodSkill(LivingEntity monster) {
        long now = System.currentTimeMillis();
        long readyAt = monster.getPersistentDataContainer().getOrDefault(
                bloodMoonSkillReadyAt, PersistentDataType.LONG, 0L);
        if (readyAt > now) return false;
        long cooldown = Math.clamp(plugin.getConfig().getLong(
                "blood-moon.skills.cooldown-seconds", 8L), 3L, 30L) * 1_000L;
        monster.getPersistentDataContainer().set(
                bloodMoonSkillReadyAt, PersistentDataType.LONG, now + cooldown);
        return true;
    }

    private void blinkBehind(LivingEntity monster, Player target) {
        org.bukkit.util.Vector flat = target.getEyeLocation().getDirection().setY(0.0);
        if (flat.lengthSquared() < 0.01) return;
        Location destination = target.getLocation().subtract(flat.normalize().multiply(2.2));
        int chunkX = destination.getBlockX() >> 4;
        int chunkZ = destination.getBlockZ() >> 4;
        if (!destination.getWorld().isChunkLoaded(chunkX, chunkZ)
                || !destination.getBlock().isPassable()
                || !destination.clone().add(0, 1, 0).getBlock().isPassable()
                || !destination.clone().subtract(0, 1, 0).getBlock().getType().isSolid()) return;
        destination.setYaw(target.getLocation().getYaw());
        monster.teleport(destination);
    }

    private void bloodSkillFeedback(LivingEntity monster, Player target, String archetype) {
        Color color = switch (archetype) {
            case "bloodfiend" -> Color.fromRGB(214, 45, 63);
            case "eclipse-archer" -> Color.fromRGB(115, 201, 238);
            case "shadow-weaver" -> Color.fromRGB(156, 72, 196);
            case "ember-core" -> Color.fromRGB(245, 118, 36);
            case "rift-walker" -> Color.fromRGB(176, 78, 235);
            case "curse-weaver" -> Color.fromRGB(94, 207, 126);
            case "night-raider" -> Color.fromRGB(83, 140, 230);
            default -> Color.fromRGB(176, 48, 71);
        };
        Sound sound = switch (archetype) {
            case "bloodfiend" -> Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR;
            case "eclipse-archer" -> Sound.ITEM_CROSSBOW_SHOOT;
            case "shadow-weaver" -> Sound.ENTITY_SPIDER_AMBIENT;
            case "rift-walker" -> Sound.ENTITY_ENDERMAN_TELEPORT;
            case "curse-weaver" -> Sound.ENTITY_WITCH_CELEBRATE;
            case "night-raider" -> Sound.ENTITY_PHANTOM_BITE;
            default -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE;
        };
        Location center = target.getLocation().add(0.0, 1.0, 0.0);
        ServerLoadState state = load();
        int configured = state == ServerLoadState.NORMAL ? 10 : state == ServerLoadState.PRESSURE ? 4 : 1;
        double radius = Math.clamp(plugin.getConfig().getDouble(
                "visual-effects.receiver-radius", 40.0), 8.0, 64.0);
        for (Player receiver : target.getWorld().getNearbyPlayers(center, radius)) {
            int count = VisualMode.forPlayer(receiver).particles(configured);
            if (count > 0) receiver.spawnParticle(Particle.DUST, center, count,
                    0.5, 0.65, 0.5, 0.01, new Particle.DustOptions(color, 1.15F));
        }
        float volume = VisualMode.forPlayer(target).volume(
                plugin.getConfig().getDouble("visual-effects.sound-volume", 0.65));
        if (volume > 0.0F) target.playSound(center, sound, SoundCategory.HOSTILE, volume, 0.9F);
        target.sendActionBar(Component.text(messages.text(target,
                "world.blood-moon-mobs.skill-trigger", Map.of(
                        "mob", messages.text(target, "world.blood-moon-mobs." + archetype + ".name"),
                        "skill", messages.text(target, "world.blood-moon-mobs." + archetype + ".skill"))),
                NamedTextColor.RED));
    }

    private void telegraphIgnitedCreepers() {
        if (bloodMoonCreepers.isEmpty()) return;
        ServerLoadState load = load();
        var iterator = bloodMoonCreepers.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            org.bukkit.entity.Entity entity = plugin.getServer().getEntity(id);
            if (!(entity instanceof Creeper creeper) || !creeper.isValid() || creeper.isDead()) {
                iterator.remove();
                continue;
            }
            if (!creeper.isIgnited() || load == ServerLoadState.CRITICAL) continue;
            Location center = creeper.getLocation().add(0.0, 1.0, 0.0);
            for (Player player : creeper.getWorld().getNearbyPlayers(center, 24.0)) {
                int count = VisualMode.forPlayer(player).particles(load == ServerLoadState.NORMAL ? 6 : 2);
                if (count > 0) player.spawnParticle(Particle.DUST, center, count,
                        0.45, 0.6, 0.45, 0.01,
                        new Particle.DustOptions(Color.fromRGB(255, 91, 35), 1.25F));
            }
        }
    }

    private void trackBloodMoonCreeper(LivingEntity entity) {
        if (entity instanceof Creeper creeper
                && creeper.getPersistentDataContainer().has(
                bloodMoonMob, PersistentDataType.BYTE)) {
            bloodMoonCreepers.add(creeper.getUniqueId());
        }
    }

    private void rareMobSpawned(LivingEntity monster) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        ServerLoadState load = load();
        Location center = monster.getLocation().add(0.0, Math.max(0.6, monster.getHeight() * 0.55), 0.0);
        double radius = Math.clamp(plugin.getConfig().getDouble("visual-effects.receiver-radius", 40.0), 8.0, 64.0);
        for (Player player : monster.getWorld().getNearbyPlayers(center, radius)) {
            int count = VisualMode.forPlayer(player).particles(load == ServerLoadState.NORMAL ? 12 : 4);
            if (count > 0) player.spawnParticle(Particle.DUST, center, count, 0.55, 0.75, 0.55, 0.02,
                    new Particle.DustOptions(Color.fromRGB(185, 20, 35), 1.25F));
        }
    }

    private void huntMilestone(Player player, int count) {
        player.sendActionBar(Component.text(messages.text(player, "world.hunt-milestone", Map.of(
                "count", count)), NamedTextColor.GOLD));
        burst(player, Particle.ENCHANTED_HIT, 10, null);
        float volume = VisualMode.forPlayer(player).volume(
                plugin.getConfig().getDouble("visual-effects.sound-volume", 0.65));
        if (volume > 0.0F) player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
                SoundCategory.PLAYERS, volume, Math.min(1.8F, 1.0F + count / 100.0F));
    }

    private void rareHuntDrop(Player player) {
        burst(player, Particle.END_ROD, 10, null);
        float volume = VisualMode.forPlayer(player).volume(
                plugin.getConfig().getDouble("visual-effects.sound-volume", 0.65));
        if (volume > 0.0F) player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, volume, 1.45F);
    }

    private void burst(Player player, Particle particle, int configured, Object data) {
        burst(player, particle, configured, data, load());
    }

    private void burst(
            Player player, Particle particle, int configured, Object data, ServerLoadState load
    ) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        int loadCount = switch (load) {
            case NORMAL -> configured;
            case PRESSURE -> configured / 2;
            case CRITICAL -> Math.min(2, configured / 4);
        };
        int count = VisualMode.forPlayer(player).particles(Math.clamp(loadCount, 0, 32));
        if (count <= 0) return;
        Location center = player.getLocation().add(0.0, 1.0, 0.0);
        if (data == null) player.spawnParticle(particle, center, count, 0.75, 0.65, 0.75, 0.02);
        else player.spawnParticle(particle, center, count, 0.75, 0.65, 0.75, 0.02, data);
    }

    private ItemStack createHuntMaterial(Player player, int chain, boolean bloodMoon) {
        ItemStack item = new ItemStack(bloodMoon ? Material.NETHER_STAR : Material.PRISMARINE_CRYSTALS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(messages.text(player, bloodMoon
                        ? "world.items.blood-moon-essence" : "world.items.hunt-crystal"),
                bloodMoon ? NamedTextColor.RED : NamedTextColor.AQUA));
        meta.lore(java.util.List.of(Component.text(messages.text(player, "world.items.chain-lore", Map.of(
                "count", chain)), NamedTextColor.GRAY)));
        meta.setItemModel(new NamespacedKey("originalrpg", bloodMoon ? "blood_moon_essence" : "hunt_crystal"));
        meta.getPersistentDataContainer().set(huntMaterial, PersistentDataType.INTEGER, chain);
        meta.getPersistentDataContainer().set(pickupOwner, PersistentDataType.STRING, player.getUniqueId().toString());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBloodMoon(World world) {
        if (!isOverworld(world)) return false;
        int every = Math.max(1, plugin.getConfig().getInt("blood-moon.every-nights", 7));
        return WorldEventRules.bloodMoon(world.getFullTime(), world.getTime(), every).active();
    }

    WorldStatus status(Player player) {
        World world = isOverworld(player.getWorld()) ? player.getWorld() : plugin.getServer().getWorlds().stream()
                .filter(this::isOverworld).findFirst().orElse(player.getWorld());
        int every = Math.max(1, plugin.getConfig().getInt("blood-moon.every-nights", 7));
        WorldEventRules.BloodMoonTiming bloodMoon = WorldEventRules.bloodMoon(
                world.getFullTime(), world.getTime(), every);

        HuntStatus hunt = null;
        HuntChain chain = chains.get(player.getUniqueId());
        if (chain != null) {
            long resetSeconds = Math.max(1L, plugin.getConfig().getLong("hunt-chain.reset-seconds", 300L));
            long remaining = WorldEventRules.huntSecondsRemaining(
                    elapsedMillis(chain.lastKill(), Instant.now()), resetSeconds);
            if (remaining == 0L) {
                chains.remove(player.getUniqueId());
            } else {
                double chance = WorldEventRules.huntBonus(chain.count(),
                        plugin.getConfig().getDouble("hunt-chain.chance-per-stack", 0.002),
                        plugin.getConfig().getDouble("hunt-chain.maximum-bonus-chance", 0.08));
                hunt = new HuntStatus(chain.entityType(), chain.variant(), chain.count(),
                        Math.round(chance * 1_000.0) / 10.0, remaining,
                        Math.max(16.0, plugin.getConfig().getDouble("hunt-chain.reset-distance", 128.0)),
                        resetSeconds, chain.materialFailures(), Math.clamp(plugin.getConfig().getInt(
                                "hunt-chain.material-drop-pity", 30), 1, 10_000));
            }
        }
        int bloodFailures = Math.max(0, player.getPersistentDataContainer().getOrDefault(
                bloodMoonFailures, PersistentDataType.INTEGER, 0));
        int bloodGuarantee = Math.clamp(plugin.getConfig().getInt(
                "blood-moon.material-drop-pity", 12), 1, 10_000);
        return new WorldStatus(bloodMoon, hunt, bloodFailures, bloodGuarantee);
    }

    private String huntVariant(LivingEntity entity) {
        if (entity instanceof AbstractCubeMob cube) return "size-" + cube.getSize();
        if (entity instanceof Phantom phantom) return "size-" + phantom.getSize();
        if (entity instanceof Creeper creeper) return creeper.isPowered() ? "charged" : "normal";
        if (entity instanceof Ageable ageable) return ageable.isAdult() ? "adult" : "baby";
        return "";
    }

    private Component huntVariantLabel(Player player, String variant) {
        if (variant == null || variant.isBlank()) return Component.empty();
        if (variant.startsWith("size-")) {
            return Component.text(messages.text(player, "world.hunt-variants.size", Map.of(
                    "size", variant.substring("size-".length()))), NamedTextColor.GRAY);
        }
        return Component.text(messages.text(player, "world.hunt-variants." + variant), NamedTextColor.GRAY);
    }

    private boolean isOverworld(World world) {
        String survivalWorld = plugin.getConfig().getString("worlds.survival", "world");
        return world.getEnvironment() == World.Environment.NORMAL && world.getName().equals(survivalWorld);
    }

    private Player nearestAudience(LivingEntity monster) {
        return monster.getWorld().getNearbyPlayers(monster.getLocation(), 64.0).stream().findFirst().orElse(null);
    }

    private int lowestNearbyEffectiveLevel(LivingEntity monster, double radius) {
        int lowest = Integer.MAX_VALUE;
        for (Player player : monster.getWorld().getNearbyPlayers(monster.getLocation(), radius)) {
            PlayerProfile profile = profiles.cached(player.getUniqueId()).orElse(null);
            if (profile != null) lowest = Math.min(lowest, profile.effectiveLevel());
        }
        return lowest == Integer.MAX_VALUE ? 0 : lowest;
    }

    private ServerLoadState load() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        return performance == null ? ServerLoadState.NORMAL : performance.state();
    }

    private MobAppearanceService appearances() {
        return OptionalServices.enabled(plugin, MobAppearanceService.class);
    }

    private static long elapsedMillis(Instant earlier, Instant later) {
        long start = earlier.toEpochMilli();
        long end = later.toEpochMilli();
        return end >= start ? end - start : 0L;
    }

    record WorldStatus(
            WorldEventRules.BloodMoonTiming bloodMoon,
            HuntStatus hunt,
            int bloodMoonFailures,
            int bloodMoonGuarantee
    ) {
    }

    record HuntStatus(
            String entityType,
            String variant,
            int count,
            double bonusPercent,
            long remainingSeconds,
            double resetDistance,
            long resetSeconds,
            int materialFailures,
            int materialGuarantee
    ) {
    }

    private record HuntChain(
            String targetKey,
            String entityType,
            String variant,
            int count,
            int materialFailures,
            UUID worldId,
            Location location,
            Instant lastKill
    ) {
    }
}
