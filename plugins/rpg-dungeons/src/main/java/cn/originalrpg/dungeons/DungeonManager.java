package cn.originalrpg.dungeons;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.event.CommunityEventCompletedEvent;
import cn.originalrpg.api.event.DungeonCompletedEvent;
import cn.originalrpg.api.model.SeasonExpeditionCompletion;
import cn.originalrpg.api.model.DungeonRewardDelivery;
import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.DungeonRewardSettlement;
import cn.originalrpg.api.model.DungeonTicketCandidate;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.service.DungeonRewardService;
import cn.originalrpg.api.service.MobAppearanceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

final class DungeonManager implements Listener {

    private final Plugin plugin;
    private final DungeonInstanceStorage instanceStorage;
    private final ProfileService profiles;
    private final MessageService messages;
    private final DungeonRewardService dungeonRewards;
    private final DungeonRewardJournal rewardJournal;
    private final DungeonRewardIoCoordinator rewardIo;
    private final DungeonVisuals visuals;
    private final SeasonExpeditionController seasonExpeditions;
    private final NamespacedKey bossMarker;
    private final NamespacedKey returnLocation;
    private final NamespacedKey dungeonId;
    private final NamespacedKey ticketId;
    private final NamespacedKey skillBook;
    private final NamespacedKey skillBookInstance;
    private final NamespacedKey bossMaterial;
    private final NamespacedKey participants;
    private final NamespacedKey waveMarker;
    private final Map<String, RewardDefinition> rewards;
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, UUID> bossWorlds = new HashMap<>();
    private final Map<UUID, BossAttackState> bossAttacks = new HashMap<>();
    private final Map<UUID, WaveAttackState> waveAttacks = new HashMap<>();
    private final Map<UUID, String> playerInstances = new HashMap<>();
    private final Map<String, Set<UUID>> instanceMembers = new HashMap<>();
    private final Map<String, UUID> instanceSessions = new HashMap<>();
    private final Map<String, UUID> entrySettlementHolds = new HashMap<>();
    private final Map<String, BukkitTask> instanceTimeouts = new HashMap<>();
    private final Map<String, String> instanceTypes = new HashMap<>();
    private final Map<String, ArrayDeque<World>> readyInstances = new HashMap<>();
    private final Map<String, Encounter> encounters = new HashMap<>();
    private final Map<String, SeasonRunSession> seasonRuns = new HashMap<>();
    private final ArrayDeque<InstanceRequest> waitingRequests = new ArrayDeque<>();
    private final ArrayDeque<ArenaBuild> resetQueue = new ArrayDeque<>();
    private final Set<UUID> dirtyBossBars = new HashSet<>();
    private final Set<String> pendingBossSpawns = new HashSet<>();
    private final Set<UUID> pendingRewardSettlements = ConcurrentHashMap.newKeySet();
    // Main-thread-only retry state. A world is not returned/recycled until its
    // one immutable all-party intent has become durable, bounding this map by
    // maximumInstanceWorlds even during a storage outage.
    private final Map<UUID, PendingRewardIntent> pendingRewardIntents = new HashMap<>();
    private final int warmInstancesPerDungeon;
    private final int maximumInstanceWorlds;
    private final int maximumQueueSize;
    private final int blocksPerTick;
    private final int instanceViewDistance;
    private final int instanceSimulationDistance;
    private final long queueTimeoutMillis;
    private final double buildMinimumTps;
    private ArenaBuild activeBuild;
    private boolean shuttingDown;
    private boolean recoveryScanPending;
    private int tickCounter;
    private long mechanicsTick;
    // 只在主线程递增：副本世界名必须进程内唯一，且必须落在 disposable 命名空间里。
    private long instanceWorldOrdinal;
    private Consumer<Player> exitHandler = ignored -> { };

    DungeonManager(
            Plugin plugin,
            ProfileService profiles,
            MessageService messages,
            DungeonRewardService dungeonRewards,
            SeasonExpeditionController seasonExpeditions
    ) {
        this.plugin = plugin;
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path overworldFolder = Bukkit.getWorlds().stream()
                .filter(world -> !DungeonInstanceStorage.isInstanceName(world.getName()))
                .filter(world -> world.getEnvironment() == World.Environment.NORMAL)
                .findFirst()
                .or(() -> Bukkit.getWorlds().stream()
                        .filter(world -> !DungeonInstanceStorage.isInstanceName(world.getName())).findFirst())
                .map(world -> world.getWorldFolder().toPath())
                .orElseGet(() -> worldContainer.resolve("world"));
        Path pluginsFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize().getParent();
        Path worldGuardRoot = (pluginsFolder == null ? worldContainer.resolve("plugins") : pluginsFolder)
                .resolve("WorldGuard").resolve("worlds");
        this.instanceStorage = new DungeonInstanceStorage(worldContainer, overworldFolder, worldGuardRoot);
        this.profiles = profiles;
        this.messages = messages;
        this.dungeonRewards = java.util.Objects.requireNonNull(dungeonRewards, "dungeonRewards");
        this.rewardJournal = new DungeonRewardJournal(plugin.getDataFolder().toPath().resolve("reward-outbox"));
        this.visuals = new DungeonVisuals(plugin);
        this.seasonExpeditions = java.util.Objects.requireNonNull(seasonExpeditions, "seasonExpeditions");
        bossMarker = new NamespacedKey(plugin, "boss");
        returnLocation = new NamespacedKey(plugin, "return_location");
        dungeonId = new NamespacedKey(plugin, "dungeon_id");
        ticketId = new NamespacedKey(plugin, "ticket_id");
        skillBook = java.util.Objects.requireNonNull(NamespacedKey.fromString("rpgskills:skill_book"));
        skillBookInstance = java.util.Objects.requireNonNull(NamespacedKey.fromString("rpgskills:book_instance"));
        bossMaterial = new NamespacedKey(plugin, "boss_material");
        participants = new NamespacedKey(plugin, "participants");
        waveMarker = new NamespacedKey(plugin, "wave_mob");
        rewards = resolveRewards();
        warmInstancesPerDungeon = Math.clamp(
                plugin.getConfig().getInt("instance-pool.warm-per-dungeon", 2), 0, 8);
        maximumInstanceWorlds = Math.clamp(
                plugin.getConfig().getInt("instance-pool.maximum-worlds", 16), 1, 64);
        int rewardQueueCapacity = Math.clamp(plugin.getConfig().getInt(
                "reward-journal.queue-capacity", 96), maximumInstanceWorlds + 8, 1024);
        this.rewardIo = new DungeonRewardIoCoordinator(rewardJournal,
                task -> Bukkit.getScheduler().runTask(plugin, task), rewardQueueCapacity);
        maximumQueueSize = Math.clamp(
                plugin.getConfig().getInt("instance-pool.maximum-queued-entries", 64), 1, 512);
        blocksPerTick = Math.clamp(
                plugin.getConfig().getInt("instance-pool.blocks-per-tick", 384), 32, 2048);
        instanceViewDistance = Math.clamp(
                plugin.getConfig().getInt("instance-pool.view-distance", 4), 2, 32);
        instanceSimulationDistance = Math.clamp(
                plugin.getConfig().getInt("instance-pool.simulation-distance", 3), 2, 32);
        queueTimeoutMillis = Math.clamp(
                plugin.getConfig().getLong("instance-pool.queue-timeout-seconds", 45L), 5L, 300L) * 1000L;
        buildMinimumTps = Math.clamp(
                plugin.getConfig().getDouble("instance-pool.build-minimum-tps", 18.5), 10.0, 20.0);
    }

    void setExitHandler(Consumer<Player> exitHandler) {
        this.exitHandler = exitHandler;
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::maintainPool, 20L, 100L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::recoverRewardJournal, 40L, 100L);
    }

    void cleanupOrphanedInstances() {
        java.util.Set<String> loadedWorlds = Bukkit.getWorlds().stream().map(World::getName).collect(java.util.stream.Collectors.toSet());
        final List<Path> candidates;
        try {
            // Snapshot before the pool starts creating new worlds. Scanning from the async
            // task could otherwise mistake a newly-created 26.2 namespaced world for an orphan.
            candidates = instanceStorage.orphanDirectories(loadedWorlds);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not scan orphan dungeon instances: " + exception.getMessage());
            return;
        }
        if (candidates.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int deleted = instanceStorage.deleteOrphans(candidates, plugin.getLogger()::warning);
            plugin.getLogger().info("Cleaned " + deleted + " orphan dungeon storage directories.");
        });
    }

    /**
     * Paper discovers namespaced dimension folders before plugins enable. A
     * completed instance is intentionally disposable and no player can hold a
     * live session across a process restart, so keeping those worlds loaded
     * would turn every old test run into startup time, WorldGuard configuration
     * work and retained chunks. Only exact instance names are touched; normal
     * worlds and any world containing a player are never candidates.
     */
    void cleanupStartupInstances() {
        int unloaded = 0;
        for (World world : List.copyOf(Bukkit.getWorlds())) {
            if (!DungeonInstanceStorage.isInstanceName(world.getName()) || !world.getPlayers().isEmpty()) continue;
            if (Bukkit.unloadWorld(world, false)) unloaded++;
        }
        if (unloaded > 0) {
            plugin.getLogger().info("Unloaded " + unloaded
                    + " disposable dungeon instance world(s) from the previous process.");
        }
        cleanupOrphanedInstances();
    }

    InstanceRequestStatus requestInstance(String id, Set<UUID> playerIds, Consumer<World> callback) {
        return requestInstance(id, playerIds, null, callback);
    }

    InstanceRequestStatus requestInstance(
            String id,
            Set<UUID> playerIds,
            SeasonExpeditionRotation rotation,
            Consumer<World> callback
    ) {
        if (callback == null) return InstanceRequestStatus.REJECTED;
        if (shuttingDown || playerIds == null || playerIds.isEmpty() || playerIds.size() > 8) {
            callback.accept(null);
            return InstanceRequestStatus.REJECTED;
        }
        Set<UUID> members = Set.copyOf(playerIds);
        if (members.stream().anyMatch(playerInstances::containsKey)) {
            callback.accept(null);
            return InstanceRequestStatus.REJECTED;
        }
        World ready = pollReady(id);
        if (ready != null) {
            assignInstance(id, members, ready, rotation);
            callback.accept(ready);
            return InstanceRequestStatus.ASSIGNED;
        }
        if (waitingRequests.size() >= maximumQueueSize) {
            callback.accept(null);
            return InstanceRequestStatus.REJECTED;
        }
        waitingRequests.addLast(new InstanceRequest(id, members, rotation,
                System.currentTimeMillis() + queueTimeoutMillis, callback));
        Bukkit.getScheduler().runTask(plugin, this::maintainPool);
        return InstanceRequestStatus.QUEUED;
    }

    /**
     * 生成一次性副本世界名。名字永远由运行时决定：{@code config.yml} 里没有
     * 权威世界名，所以不存在「配置写了但找不到世界」的可能。
     */
    private String nextInstanceWorldName(String id) {
        String worldName = DungeonWorldNames.allocate(id, ++instanceWorldOrdinal);
        if (plugin.getConfig().getBoolean("instance-pool.log-instance-worlds", false)) {
            plugin.getLogger().info("Preparing disposable dungeon world " + worldName + " for " + id);
        }
        return worldName;
    }

    private void startNewInstance(String id, ConfigurationSection config) {
        String worldName = nextInstanceWorldName(id);
        WorldCreator creator = new WorldCreator(worldName)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:plains\"}");
        World world = creator.createWorld();
        if (world == null) {
            failFirstRequest(id);
            return;
        }
        configureWorld(world, id.endsWith("-hidden"));
        instanceTypes.put(worldName, id);
        activeBuild = arenaBuild(id, world, config.getInt("y", 80));
    }

    private void assignInstance(
            String id, Set<UUID> playerIds, World world, SeasonExpeditionRotation rotation
    ) {
        String worldName = world.getName();
        cancelInstanceTimeout(worldName);
        UUID sessionId = UUID.randomUUID();
        instanceSessions.put(worldName, sessionId);
        playerIds.forEach(playerId -> playerInstances.put(playerId, worldName));
        instanceMembers.put(worldName, new HashSet<>(playerIds));
        if (rotation != null) {
            seasonRuns.put(worldName, new SeasonRunSession(UUID.randomUUID(), rotation, playerIds));
        }
        long timeoutMinutes = Math.max(5L, plugin.getConfig().getLong("session-timeout-minutes", 30L));
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin,
                () -> timeoutSession(worldName, sessionId), timeoutMinutes * 60L * 20L);
        instanceTimeouts.put(worldName, timeout);
    }

    void releasePlayer(UUID playerId, World previousWorld) {
        String instance = playerInstances.get(playerId);
        if (instance == null || previousWorld == null || !instance.equals(previousWorld.getName())) return;
        playerInstances.remove(playerId);
        Set<UUID> members = instanceMembers.get(instance);
        if (members != null) members.remove(playerId);
        if (members == null || members.isEmpty()) {
            instanceMembers.remove(instance);
            if (!hasInstanceHold(instance)) releaseInstance(previousWorld);
        }
    }

    UUID holdEntrySettlement(World world) {
        if (world == null) return null;
        String worldName = world.getName();
        UUID sessionId = instanceSessions.get(worldName);
        if (sessionId == null || entrySettlementHolds.putIfAbsent(worldName, sessionId) != null) return null;
        return sessionId;
    }

    void releaseEntrySettlement(World world, UUID sessionId) {
        if (world == null || sessionId == null) return;
        String worldName = world.getName();
        if (!entrySettlementHolds.remove(worldName, sessionId)) return;
        if (instanceMembers.getOrDefault(worldName, Set.of()).isEmpty()
                && !hasPendingRewardIntent(worldName)) releaseInstance(world);
    }

    private boolean hasInstanceHold(String worldName) {
        return entrySettlementHolds.containsKey(worldName) || hasPendingRewardIntent(worldName);
    }

    private boolean hasPendingRewardIntent(String worldName) {
        return pendingRewardIntents.values().stream()
                .anyMatch(intent -> intent.worldName.equals(worldName));
    }

    private void releaseRewardHeldInstanceIfVacant(String worldName) {
        if (hasInstanceHold(worldName)
                || !instanceMembers.getOrDefault(worldName, Set.of()).isEmpty()) return;
        World world = Bukkit.getWorld(worldName);
        if (world != null) releaseInstance(world);
    }

    boolean areInstanceAllies(UUID first, UUID second) {
        if (first.equals(second)) return true;
        String instance = playerInstances.get(first);
        if (instance == null || !instance.equals(playerInstances.get(second))) return false;
        Set<UUID> members = instanceMembers.get(instance);
        return members != null && members.contains(first) && members.contains(second);
    }

    void ensureBoss(String id, World world, Location entrance, Player audience) {
        ConfigurationSection bossConfig = plugin.getConfig().getConfigurationSection("dungeons." + id + ".boss");
        if (bossConfig == null) return;
        SeasonRunSession seasonRun = seasonRuns.get(world.getName());
        if (seasonRun != null && seasonRun.start()) {
            seasonExpeditions.announceStart(world.getPlayers(), seasonRun.rotation);
        }
        boolean hidden = id.endsWith("-hidden");
        String name = messages.text(audience, "dungeon.bosses." + id);
        LivingEntity existing = world.getLivingEntities().stream().filter(entity ->
                entity.getPersistentDataContainer().has(bossMarker, PersistentDataType.BYTE)).findFirst().orElse(null);
        if (existing != null) {
            MobAppearanceService appearance = appearances();
            if (appearance != null) appearance.applyDungeonBossModel(existing, id);
            ensureBossBar(existing, name, hidden, audience);
            bossAttacks.computeIfAbsent(existing.getUniqueId(), ignored ->
                    new BossAttackState(mechanicsTick + attackInterval(id, 0, world.getName()),
                            DungeonRewardRules.require(id).patternOffset()));
            return;
        }
        if (encounters.containsKey(world.getName())) return;
        ConfigurationSection waves = plugin.getConfig().getConfigurationSection("dungeons." + id + ".waves");
        List<String> waveIds = waves == null ? List.of() : waves.getKeys(false).stream().sorted().toList();
        if (!waveIds.isEmpty()) {
            Encounter encounter = new Encounter(id, entrance.clone(), audience.getUniqueId(), waveIds, 0);
            encounters.put(world.getName(), encounter);
            spawnWave(world, encounter);
            return;
        }
        scheduleBoss(id, world, entrance, audience);
    }

    private void scheduleBoss(String id, World world, Location entrance, Player audience) {
        String worldName = world.getName();
        boolean alreadyPresent = world.getLivingEntities().stream().anyMatch(entity ->
                entity.getPersistentDataContainer().has(bossMarker, PersistentDataType.BYTE));
        if (shuttingDown || alreadyPresent || !pendingBossSpawns.add(worldName)) return;
        boolean hidden = id.endsWith("-hidden");
        Location spawn = entrance.clone().add(0.0, 1.0, 14.0);
        world.getPlayers().forEach(player -> player.sendActionBar(Component.text(messages.text(
                player, "dungeon.boss-warning", Map.of("boss", messages.text(player,
                        "dungeon.bosses." + id))), hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD)));
        visuals.bossWarning(spawn, hidden);
        long delay = Math.clamp(plugin.getConfig().getLong(
                "visual-effects.boss-warning-delay-ticks", 30L), 10L, 100L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingBossSpawns.remove(worldName);
            if (shuttingDown || Bukkit.getWorld(world.getUID()) == null
                    || instanceMembers.getOrDefault(worldName, Set.of()).isEmpty()) return;
            boolean exists = world.getLivingEntities().stream().anyMatch(entity ->
                    entity.getPersistentDataContainer().has(bossMarker, PersistentDataType.BYTE));
            if (exists) return;
            Player currentAudience = Bukkit.getPlayer(audience.getUniqueId());
            if (currentAudience == null || !currentAudience.isOnline() || currentAudience.getWorld() != world) {
                currentAudience = world.getPlayers().stream().findFirst().orElse(null);
            }
            if (currentAudience != null) spawnBoss(id, world, entrance, currentAudience);
        }, delay);
    }

    private void spawnBoss(String id, World world, Location entrance, Player audience) {
        ConfigurationSection bossConfig = plugin.getConfig().getConfigurationSection("dungeons." + id + ".boss");
        if (bossConfig == null) return;
        boolean hidden = id.endsWith("-hidden");
        String name = messages.text(audience, "dungeon.bosses." + id);
        Location spawn = entrance.clone().add(0, 1, 14);
        Mob boss = spawnConfiguredBoss(world, spawn, bossConfig, hidden);
        boss.getPersistentDataContainer().set(bossMarker, PersistentDataType.BYTE, (byte) 1);
        boss.getPersistentDataContainer().set(dungeonId, PersistentDataType.STRING, id);
        Set<UUID> session = instanceMembers.getOrDefault(world.getName(), Set.of());
        SeasonExpeditionAffixRules.Modifiers seasonModifiers = seasonModifiers(world.getName());
        boss.getPersistentDataContainer().set(participants, PersistentDataType.STRING,
                session.stream().map(UUID::toString).sorted().collect(java.util.stream.Collectors.joining(",")));
        boss.customName(Component.text(name, hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD));
        boss.setCustomNameVisible(true);
        int partySize = Math.max(1, session.size());
        double healthScale = 1.0 + (partySize - 1) * Math.clamp(
                plugin.getConfig().getDouble("party.boss-health-per-extra-player", 0.65), 0.0, 2.0);
        double damageScale = 1.0 + (partySize - 1) * Math.clamp(
                plugin.getConfig().getDouble("party.boss-damage-per-extra-player", 0.12), 0.0, 0.5);
        double health = Math.clamp(seasonModifiers.bossHealth(
                bossConfig.getDouble("health", hidden ? 600.0 : 240.0) * healthScale), 20.0, 2048.0);
        AttributeInstance maximum = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maximum != null) maximum.setBaseValue(health);
        boss.setHealth(health);
        AttributeInstance damage = boss.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) damage.setBaseValue(Math.clamp(seasonModifiers.damage(
                bossConfig.getDouble("attack-damage", hidden ? 18.0 : 10.0) * damageScale), 1.0, 100.0));
        boss.setRemoveWhenFarAway(false);
        MobAppearanceService appearance = appearances();
        if (appearance != null) appearance.applyDungeonBossModel(boss, id);
        ensureBossBar(boss, name, hidden, audience);
        bossAttacks.put(boss.getUniqueId(), new BossAttackState(
                mechanicsTick + attackInterval(id, 0, world.getName()),
                DungeonRewardRules.require(id).patternOffset()));
        visuals.bossArrival(boss, hidden);
    }

    private Mob spawnConfiguredBoss(World world, Location spawn, ConfigurationSection bossConfig, boolean hidden) {
        String configured = bossConfig.getString("type", hidden ? "WITHER_SKELETON" : "ZOMBIE");
        try {
            Entity entity = world.spawnEntity(spawn, EntityType.valueOf(configured.toUpperCase(java.util.Locale.ROOT)));
            if (entity instanceof Mob mob) return mob;
            entity.remove();
            plugin.getLogger().warning("Dungeon boss type is not a mob: " + configured + "; using fallback");
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid dungeon boss type: " + configured + "; using fallback");
        }
        return hidden ? world.spawn(spawn, WitherSkeleton.class) : world.spawn(spawn, Zombie.class);
    }

    private void spawnWave(World world, Encounter encounter) {
        if (encounter.waveIndex >= encounter.waveIds.size()) {
            if (!encounters.remove(world.getName(), encounter)) return;
            Player audience = Bukkit.getPlayer(encounter.audienceId);
            if (audience == null) audience = world.getPlayers().stream().findFirst().orElse(null);
            if (audience != null) scheduleBoss(encounter.id, world, encounter.entrance, audience);
            return;
        }
        String waveId = encounter.waveIds.get(encounter.waveIndex);
        ConfigurationSection wave = plugin.getConfig().getConfigurationSection(
                "dungeons." + encounter.id + ".waves." + waveId);
        if (wave == null) {
            encounter.waveIndex++;
            spawnWave(world, encounter);
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(wave.getString("type", "ZOMBIE").toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid dungeon wave entity type for " + encounter.id + "/" + waveId);
            encounter.waveIndex++;
            spawnWave(world, encounter);
            return;
        }
        SeasonExpeditionAffixRules.Modifiers seasonModifiers = seasonModifiers(world.getName());
        int count = Math.clamp(seasonModifiers.waveCount(wave.getInt("count", 4)), 1, 24);
        int partySize = Math.max(1, instanceMembers.getOrDefault(world.getName(), Set.of()).size());
        double healthScale = 1.0 + (partySize - 1) * Math.clamp(
                plugin.getConfig().getDouble("party.wave-health-per-extra-player", 0.35), 0.0, 1.0);
        double damageScale = 1.0 + (partySize - 1) * Math.clamp(
                plugin.getConfig().getDouble("party.wave-damage-per-extra-player", 0.08), 0.0, 0.4);
        int waveNumber = encounter.waveIndex + 1;
        String archetype = wave.getString("archetype", "");
        WaveSkillRules.Skill waveSkill = WaveSkillRules.find(wave.getString("skill", "")).orElse(null);
        long skillInterval = waveSkill == null ? 0L : Math.clamp(
                wave.getLong("skill-interval-ticks", waveSkill.intervalTicks()), 100L, 400L);
        double skillDamage = waveSkill == null ? 0.0 : Math.clamp(seasonModifiers.damage(
                wave.getDouble("skill-damage", wave.getDouble("attack-damage", 6.0) * 0.75) * damageScale),
                1.0, 40.0);
        world.getPlayers().forEach(player -> player.sendMessage(Component.text(messages.text(player,
                "dungeon.wave-start", Map.of("wave", waveNumber, "count", count)), NamedTextColor.YELLOW)));
        visuals.waveStart(encounter.entrance, encounter.id.endsWith("-hidden"));
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2.0 * index / count;
            Location location = encounter.entrance.clone().add(Math.cos(angle) * 9.0, 1.0,
                    11.0 + Math.sin(angle) * 7.0);
            Entity spawned = world.spawnEntity(location, type);
            if (!(spawned instanceof Mob monster)) {
                spawned.remove();
                continue;
            }
            monster.getPersistentDataContainer().set(waveMarker, PersistentDataType.BYTE, (byte) 1);
            String nameKey = archetype.isBlank() ? "dungeon.wave-mob" : "dungeon.wave-mobs." + archetype;
            monster.customName(Component.text(messages.text(world.getPlayers().stream().findFirst().orElse(null),
                    nameKey, Map.of("wave", waveNumber)), NamedTextColor.RED));
            double health = Math.clamp(seasonModifiers.waveHealth(
                    wave.getDouble("health", 40.0) * healthScale), 1.0, 2048.0);
            AttributeInstance maximum = monster.getAttribute(Attribute.MAX_HEALTH);
            if (maximum != null) maximum.setBaseValue(health);
            monster.setHealth(Math.min(health, maximum == null ? health : maximum.getValue()));
            AttributeInstance damage = monster.getAttribute(Attribute.ATTACK_DAMAGE);
            if (damage != null) damage.setBaseValue(Math.clamp(seasonModifiers.damage(
                    wave.getDouble("attack-damage", 6.0) * damageScale), 0.0, 100.0));
            monster.setRemoveWhenFarAway(false);
            MobAppearanceService appearance = appearances();
            if (appearance != null && !archetype.isBlank()) {
                appearance.applyDungeonWaveModel(monster, archetype);
            }
            if (waveSkill != null) {
                long stagger = Math.max(8L, skillInterval / Math.max(1, count));
                waveAttacks.put(monster.getUniqueId(), new WaveAttackState(
                        waveSkill, mechanicsTick + 55L + stagger * index, skillInterval,
                        skillDamage, encounter.id.endsWith("-hidden")));
            }
        }
        if (world.getLivingEntities().stream().noneMatch(entity ->
                entity.getPersistentDataContainer().has(waveMarker, PersistentDataType.BYTE))) {
            encounter.waveIndex++;
            spawnWave(world, encounter);
        }
    }

    @EventHandler
    public void onWaveMobDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (!mob.getPersistentDataContainer().has(waveMarker, PersistentDataType.BYTE)) return;
        waveAttacks.remove(mob.getUniqueId());
        event.getDrops().clear();
        event.setDroppedExp(0);
        Bukkit.getScheduler().runTask(plugin, () -> advanceWaveIfCleared(mob.getWorld()));
    }

    private void advanceWaveIfCleared(World world) {
        if (world.getLivingEntities().stream().anyMatch(entity ->
                entity.getPersistentDataContainer().has(waveMarker, PersistentDataType.BYTE))) return;
        Encounter encounter = encounters.get(world.getName());
        if (encounter == null) return;
        encounter.waveIndex++;
        spawnWave(world, encounter);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity boss)
                || !boss.getPersistentDataContainer().has(bossMarker, PersistentDataType.BYTE)) return;
        dirtyBossBars.add(boss.getUniqueId());
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity boss = event.getEntity();
        String id = boss.getPersistentDataContainer().get(dungeonId, PersistentDataType.STRING);
        String worldName = boss.getWorld().getName();
        if (!DungeonCompletionPolicy.isManagedBoss(id,
                boss.getPersistentDataContainer().has(bossMarker, PersistentDataType.BYTE),
                instanceTypes.get(worldName))) return;
        encounters.remove(boss.getWorld().getName());
        BossBar bar = bossBars.remove(boss.getUniqueId());
        bossWorlds.remove(boss.getUniqueId());
        bossAttacks.remove(boss.getUniqueId());
        dirtyBossBars.remove(boss.getUniqueId());
        if (bar != null) bar.removeAll();
        visuals.bossDefeated(boss.getLocation(), id.endsWith("-hidden"));
        MobAppearanceService appearance = appearances();
        if (appearance != null) appearance.playAnimation(boss, "death");
        event.getDrops().clear();
        event.setDroppedExp(0);
        Player killer = boss.getKiller();
        Set<UUID> session = instanceMembers.getOrDefault(worldName, Set.of());
        List<Player> eligibleSessionMembers = session.stream().map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull).filter(Player::isOnline)
                .filter(player -> player.getWorld().equals(boss.getWorld())).toList();
        Set<UUID> recipientIds = DungeonCompletionPolicy.recipientIds(
                eligibleSessionMembers.stream().map(Player::getUniqueId).toList(),
                killer == null ? null : killer.getUniqueId());
        List<Player> recipients = recipientIds.stream().map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull).filter(Player::isOnline)
                .filter(player -> player.getWorld().equals(boss.getWorld())).toList();
        if (recipients.isEmpty()) return;
        boolean hidden = id.endsWith("-hidden");
        RewardDefinition reward = rewards.get(id);
        if (reward == null) {
            plugin.getLogger().severe("No validated reward definition exists for dungeon " + id);
            return;
        }
        recipients.forEach(recipient -> recipient.sendMessage(Component.text(messages.text(
                recipient, "dungeon.completed", Map.of("boss", messages.text(recipient,
                        "dungeon.bosses." + id))), NamedTextColor.GREEN)));
        long experience = plugin.getConfig().getLong("dungeons." + id + ".boss.rpg-experience", hidden ? 3000L : 700L);
        UUID runId = boss.getUniqueId();
        List<DungeonRewardRequest> rewardRequests = new ArrayList<>(recipients.size());
        for (Player recipient : recipients) {
            // Item serialization, localized names and every other Player/Bukkit access
            // happen here on the event thread. The journal worker receives only this
            // deeply immutable API request graph.
            rewardRequests.add(rewardRequest(runId, recipient, id, experience, reward, hidden));
        }
        Optional<SeasonExpeditionCompletion> seasonCompletion = seasonCompletion(
                id, worldName, recipientIds, recipients);
        DungeonRewardBatch batch = new DungeonRewardBatch(runId, rewardRequests,
                seasonCompletion.map(value -> new SeasonExpeditionCompletion(
                        runId, value.seasonId(), value.rotationId(), value.dungeonId(),
                        value.partySize(), value.durationMillis(), value.participantIds(), value.completedAt())));
        PendingRewardIntent intent = new PendingRewardIntent(batch, id, worldName,
                instanceSessions.get(worldName), recipientIds, hidden,
                killer == null ? recipients.getFirst().getName() : killer.getName());
        pendingRewardIntents.put(runId, intent);
        submitRewardIntent(intent);
    }

    private void submitRewardIntent(PendingRewardIntent intent) {
        if (shuttingDown || intent.persisting) return;
        intent.persisting = true;
        rewardIo.persist(intent.batch).whenComplete((ignored, error) -> {
            intent.persisting = false;
            if (error != null) {
                plugin.getLogger().severe("Could not persist all-party dungeon reward intent run="
                        + intent.batch.runId() + ": " + error.getMessage());
                notifyPending(intent.batch);
                // Queue saturation and transient I/O failures use the same bounded,
                // main-thread retry state. No database operation or completion
                // side effect has started at this point.
                if (!shuttingDown) Bukkit.getScheduler().runTaskLater(plugin,
                        () -> submitRewardIntent(intent), 40L);
                return;
            }
            settleReward(intent.batch, true);
            // Complete every world/session side effect while this intent still
            // owns the recycle hold. A party may leave during a slow fsync; if
            // the hold were removed first, releasePlayer could hand the same
            // world to a new party before the old season/event state is closed.
            completeDungeonAfterDurableIntent(intent);
            pendingRewardIntents.remove(intent.batch.runId(), intent);
            releaseRewardHeldInstanceIfVacant(intent.worldName);
        });
    }

    private void completeDungeonAfterDurableIntent(PendingRewardIntent intent) {
        List<Player> recipients = intent.recipientIds.stream().map(Bukkit::getPlayer)
                .filter(java.util.Objects::nonNull).filter(Player::isOnline).toList();
        if (intent.hidden) {
            Bukkit.getOnlinePlayers().forEach(audience -> audience.sendMessage(Component.text(messages.text(
                    audience, "dungeon.hidden-boss-defeated", Map.of("player", intent.victor,
                            "boss", messages.text(audience, "dungeon.bosses." + intent.dungeon))),
                    NamedTextColor.LIGHT_PURPLE)));
        }
        Bukkit.getPluginManager().callEvent(new DungeonCompletedEvent(intent.dungeon, intent.recipientIds));
        // Phase 6：同一次完成同时作为社群治理动作（community.event）的真实触发点。
        // 参与者角色由消费方（rpg-community）按社群成员表判定，这里不传角色，
        // 因此事件默认按 MEMBER 处理 —— 不会因为「拿不到角色」而放宽前置。
        Bukkit.getPluginManager().callEvent(
                new CommunityEventCompletedEvent(intent.dungeon, intent.recipientIds, intent.victor));
        long delay = Math.max(3L, plugin.getConfig().getLong("completion-return-delay-seconds", 8L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!DungeonSessionRules.isCurrentSession(
                    instanceSessions.get(intent.worldName), intent.sessionId)) return;
            intent.recipientIds.forEach(playerId -> {
                if (!intent.worldName.equals(playerInstances.get(playerId))) return;
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()
                        && player.getWorld().getName().equals(intent.worldName)) exitHandler.accept(player);
            });
        }, delay * 20L);
    }

    private void notifyPending(DungeonRewardBatch batch) {
        batch.requests().forEach(request -> {
            Player player = Bukkit.getPlayer(request.playerId());
            if (player != null && player.isOnline()) player.sendMessage(Component.text(
                    messages.text(player, "dungeon.reward-pending"), NamedTextColor.YELLOW));
        });
    }

    private Optional<SeasonExpeditionCompletion> seasonCompletion(
            String dungeon,
            String worldName,
            Set<UUID> recipientIds,
            List<Player> recipients
    ) {
        SeasonRunSession run = seasonRuns.remove(worldName);
        if (run == null) return Optional.empty();
        SeasonExpeditionQualificationPolicy.Status qualification = SeasonExpeditionQualificationPolicy.evaluate(
                run.started(), run.rotation.dungeonId(), dungeon, run.participants, recipientIds,
                seasonExpeditions.remainsActive(run.rotation));
        if (qualification == SeasonExpeditionQualificationPolicy.Status.PARTY_OR_RUN_INVALID) {
            recipients.forEach(player -> player.sendMessage(Component.text(messages.text(
                    player, "dungeon.season.disqualified-party"), NamedTextColor.YELLOW)));
            return Optional.empty();
        }
        if (qualification == SeasonExpeditionQualificationPolicy.Status.ROTATION_EXPIRED) {
            recipients.forEach(player -> player.sendMessage(Component.text(messages.text(
                    player, "dungeon.season.expired"), NamedTextColor.YELLOW)));
            return Optional.empty();
        }
        long durationMillis = run.durationMillis();
        List<UUID> participants = recipientIds.stream().sorted().toList();
        return Optional.of(new SeasonExpeditionCompletion(
                run.runId, run.rotation.seasonId(), run.rotation.rotationId(), dungeon,
                run.participants.size(), durationMillis, participants, Instant.now()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        bossBars.forEach((bossId, bar) -> {
            bar.removePlayer(player);
            if (player.getWorld().getUID().equals(bossWorlds.get(bossId))) bar.addPlayer(player);
        });
        if (!DungeonSessionRules.departedRegisteredInstance(
                playerInstances.get(player.getUniqueId()), event.getFrom().getName(), player.getWorld().getName())) return;

        // Teleports initiated outside /dungeon leave (administrator tools,
        // portals and other plugins) must close the same session lifecycle.
        // Otherwise the member slot, instance and durable return marker remain
        // occupied until timeout and block the player's next dungeon entry.
        player.getPersistentDataContainer().remove(returnLocation);
        player.saveData();
        releasePlayer(player.getUniqueId(), event.getFrom());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        releaseSession(event.getPlayer());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        releaseSession(event.player());
    }

    private void releaseSession(Player player) {
        bossBars.values().forEach(bar -> bar.removePlayer(player));
        String instance = playerInstances.get(player.getUniqueId());
        if (instance == null) return;
        World world = Bukkit.getWorld(instance);
        if (world != null) Bukkit.getScheduler().runTask(plugin,
                () -> releasePlayer(player.getUniqueId(), world));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getLocation().getWorld().getName())) event.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (DungeonInstanceStorage.isInstanceName(event.getBlock().getWorld().getName())) event.setCancelled(true);
    }

    void shutdown() {
        shuttingDown = true;
        // Retry every not-yet-admitted immutable batch before closing admission.
        // Accepted writes are then drained for a bounded interval; if a slow
        // force(2) exceeds it, the non-daemon journal worker continues and the
        // still-present journal (never a partially settled party) is recovered.
        long drainMillis = Math.clamp(plugin.getConfig().getLong(
                "reward-journal.shutdown-drain-millis", 3000L), 100L, 10_000L);
        List<DungeonRewardBatch> closingIntents = pendingRewardIntents.values().stream()
                .map(intent -> intent.batch).toList();
        if (!rewardIo.close(Duration.ofMillis(drainMillis), closingIntents)) {
            plugin.getLogger().warning("Dungeon reward journal is still draining after "
                    + drainMillis + "ms; shutdown will wait for the non-daemon writer to preserve accepted intents.");
        }
        bossBars.values().forEach(BossBar::removeAll);
        bossBars.clear();
        bossWorlds.clear();
        bossAttacks.clear();
        waveAttacks.clear();
        playerInstances.clear();
        instanceMembers.clear();
        instanceSessions.clear();
        entrySettlementHolds.clear();
        instanceTimeouts.values().forEach(BukkitTask::cancel);
        instanceTimeouts.clear();
        readyInstances.clear();
        waitingRequests.clear();
        seasonRuns.clear();
        resetQueue.clear();
        dirtyBossBars.clear();
        pendingBossSpawns.clear();
        encounters.clear();
        for (World world : List.copyOf(Bukkit.getWorlds())) {
            if (DungeonInstanceStorage.isInstanceName(world.getName())) Bukkit.unloadWorld(world, false);
        }
    }

    private void timeoutSession(String worldName, UUID sessionId) {
        if (!DungeonSessionRules.isCurrentSession(instanceSessions.get(worldName), sessionId)) return;
        instanceTimeouts.remove(worldName);
        Set<UUID> members = instanceMembers.get(worldName);
        if (members == null || members.isEmpty()) return;
        World world = Bukkit.getWorld(worldName);
        for (UUID playerId : Set.copyOf(members)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(Component.text(messages.text(player, "dungeon.timeout"), NamedTextColor.YELLOW));
                exitHandler.accept(player);
            } else {
                playerInstances.remove(playerId);
                members.remove(playerId);
            }
        }
        if (members.isEmpty()) {
            instanceMembers.remove(worldName);
            if (world != null && !hasInstanceHold(worldName)) releaseInstance(world);
        }
    }

    private void ensureBossBar(LivingEntity boss, String name, boolean hidden, Player audience) {
        if (bossBars.containsKey(boss.getUniqueId())) return;
        BossBar bar = Bukkit.createBossBar(name, hidden ? BarColor.PURPLE : BarColor.YELLOW, BarStyle.SEGMENTED_10);
        AttributeInstance maximum = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maximum != null) bar.setProgress(Math.clamp(boss.getHealth() / maximum.getValue(), 0.0, 1.0));
        boss.getWorld().getPlayers().forEach(bar::addPlayer);
        bossBars.put(boss.getUniqueId(), bar);
        bossWorlds.put(boss.getUniqueId(), boss.getWorld().getUID());
    }

    private void releaseInstance(World world) {
        String name = world.getName();
        if (!DungeonInstanceStorage.isInstanceName(name)) return;
        cancelInstanceTimeout(name);
        instanceSessions.remove(name);
        entrySettlementHolds.remove(name);
        encounters.remove(name);
        pendingBossSpawns.remove(name);
        seasonRuns.remove(name);
        waveAttacks.entrySet().removeIf(entry -> {
            Entity entity = Bukkit.getEntity(entry.getKey());
            return entity == null || entity.getWorld() == world;
        });
        bossBars.entrySet().removeIf(entry -> {
            if (!world.getUID().equals(bossWorlds.get(entry.getKey()))) return false;
            entry.getValue().removeAll();
            bossWorlds.remove(entry.getKey());
            bossAttacks.remove(entry.getKey());
            return true;
        });
        world.getEntities().stream().filter(entity -> !(entity instanceof Player)).forEach(org.bukkit.entity.Entity::remove);
        String id = instanceTypes.get(name);
        ConfigurationSection config = id == null ? null
                : plugin.getConfig().getConfigurationSection("dungeons." + id);
        if (id == null || config == null || shuttingDown) return;
        resetQueue.addLast(arenaBuild(id, world, config.getInt("y", 80)));
    }

    private void cancelInstanceTimeout(String worldName) {
        BukkitTask timeout = instanceTimeouts.remove(worldName);
        if (timeout != null) timeout.cancel();
    }

    private void configureWorld(World world, boolean hidden) {
        world.setDifficulty(Difficulty.HARD);
        world.setAutoSave(false);
        world.setViewDistance(instanceViewDistance);
        world.setSimulationDistance(instanceSimulationDistance);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 0);
        world.setTime(hidden ? 18_000L : 6_000L);
        world.getWorldBorder().setCenter(0.5, 0.5);
        world.getWorldBorder().setSize(64.0);
    }

    private ArenaBuild arenaBuild(String id, World world, int y) {
        boolean hidden = id.endsWith("-hidden");
        boolean sulfur = id.startsWith("sulfur-depths");
        boolean storm = id.startsWith("storm-vault");
        Material floor = sulfur ? (hidden ? Material.CINNABAR_BRICKS : Material.SULFUR_BRICKS)
                : storm ? (hidden ? Material.OXIDIZED_CUT_COPPER : Material.TUFF_BRICKS)
                : hidden ? Material.DEEPSLATE_TILES : Material.STONE_BRICKS;
        Material wall = sulfur ? (hidden ? Material.POLISHED_CINNABAR : Material.POLISHED_SULFUR)
                : storm ? (hidden ? Material.WAXED_OXIDIZED_COPPER : Material.CHISELED_TUFF_BRICKS)
                : hidden ? Material.POLISHED_BLACKSTONE_BRICKS : Material.CHISELED_STONE_BRICKS;
        Material path = sulfur ? (hidden ? Material.CINNABAR : Material.SULFUR)
                : storm ? (hidden ? Material.EXPOSED_CUT_COPPER : Material.POLISHED_TUFF)
                : hidden ? Material.CRACKED_DEEPSLATE_TILES : Material.MOSSY_STONE_BRICKS;
        Material accent = sulfur ? (hidden ? Material.CHISELED_CINNABAR : Material.CHISELED_SULFUR)
                : storm ? (hidden ? Material.LIGHTNING_ROD : Material.CHISELED_COPPER)
                : hidden ? Material.GILDED_BLACKSTONE : Material.CHISELED_TUFF_BRICKS;
        Material light = sulfur ? Material.SHROOMLIGHT : hidden ? Material.SOUL_LANTERN : Material.LANTERN;
        ArrayList<BlockPlacement> placements = new ArrayList<>(4800);
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                Material tile = Math.abs(x) <= 2 || (Math.abs(x) <= 8 && z >= 10 && z <= 18) ? path : floor;
                placements.add(new BlockPlacement(x, y - 1, z, tile));
            }
        }
        for (int x = -24; x <= 24; x++) {
            for (int height = 0; height < 5; height++) {
                placements.add(new BlockPlacement(x, y + height, -24, wall));
                placements.add(new BlockPlacement(x, y + height, 24, wall));
            }
        }
        for (int z = -24; z <= 24; z++) {
            for (int height = 0; height < 5; height++) {
                placements.add(new BlockPlacement(-24, y + height, z, wall));
                placements.add(new BlockPlacement(24, y + height, z, wall));
            }
        }
        int[][] pillars = {{-18, -14}, {18, -14}, {-18, 10}, {18, 10}, {-10, 18}, {10, 18}};
        for (int[] pillar : pillars) {
            for (int height = 0; height < 6; height++) {
                placements.add(new BlockPlacement(pillar[0], y + height, pillar[1],
                        height == 0 || height == 5 ? accent : wall));
            }
            placements.add(new BlockPlacement(pillar[0], y + 6, pillar[1], light));
        }
        for (int x = -7; x <= 7; x++) {
            for (int z = 11; z <= 17; z++) {
                if (Math.abs(x) <= 4 && z >= 12 && z <= 16) {
                    placements.add(new BlockPlacement(x, y, z, accent));
                }
            }
        }
        for (int x = -4; x <= 4; x++) {
            placements.add(new BlockPlacement(x, y, -20, accent));
            if (Math.abs(x) == 4) {
                for (int height = 1; height <= 4; height++) {
                    placements.add(new BlockPlacement(x, y + height, -20, wall));
                }
                placements.add(new BlockPlacement(x, y + 5, -20, light));
            }
        }
        return new ArenaBuild(id, world, y, placements, 0);
    }

    private void tick() {
        mechanicsTick++;
        updateDirtyBossBars();
        tickBossMechanics();
        tickWaveMechanics();
        if (++tickCounter >= 20) {
            tickCounter = 0;
            expireRequests();
        }
        if (activeBuild == null || currentTps() < buildMinimumTps) return;
        int stop = Math.min(activeBuild.placements.size(), activeBuild.index + blocksPerTick);
        while (activeBuild.index < stop) {
            BlockPlacement placement = activeBuild.placements.get(activeBuild.index++);
            activeBuild.world.getBlockAt(placement.x(), placement.y(), placement.z())
                    .setType(placement.material(), false);
        }
        if (activeBuild.index < activeBuild.placements.size()) return;
        ArenaBuild completed = activeBuild;
        activeBuild = null;
        completed.world.setSpawnLocation(0, completed.y, 0);
        readyInstances.computeIfAbsent(completed.id, ignored -> new ArrayDeque<>())
                .addLast(completed.world);
        satisfyReadyRequests();
        maintainPool();
    }

    private void maintainPool() {
        if (shuttingDown || activeBuild != null) return;
        expireRequests();
        satisfyReadyRequests();
        if (currentTps() < buildMinimumTps) return;
        ArenaBuild reset = resetQueue.pollFirst();
        if (reset != null) {
            activeBuild = reset;
            return;
        }
        if (instanceWorldCount() >= maximumInstanceWorlds) return;

        for (InstanceRequest request : waitingRequests) {
            ConfigurationSection config = plugin.getConfig().getConfigurationSection("dungeons." + request.id());
            if (config != null && config.getBoolean("enabled", false)) {
                startNewInstance(request.id(), config);
                return;
            }
        }
        ConfigurationSection dungeons = plugin.getConfig().getConfigurationSection("dungeons");
        if (dungeons == null || warmInstancesPerDungeon <= 0) return;
        for (String id : dungeons.getKeys(false)) {
            ConfigurationSection config = dungeons.getConfigurationSection(id);
            if (config == null || !config.getBoolean("enabled", false)) continue;
            int readyCount = readyInstances.getOrDefault(id, new ArrayDeque<>()).size();
            if (readyCount < warmInstancesPerDungeon) {
                startNewInstance(id, config);
                return;
            }
        }
    }

    private void satisfyReadyRequests() {
        var iterator = waitingRequests.iterator();
        while (iterator.hasNext()) {
            InstanceRequest request = iterator.next();
            World world = pollReady(request.id());
            if (world == null) continue;
            iterator.remove();
            assignInstance(request.id(), request.playerIds(), world, request.rotation());
            request.callback().accept(world);
        }
    }

    private World pollReady(String id) {
        ArrayDeque<World> worlds = readyInstances.get(id);
        while (worlds != null && !worlds.isEmpty()) {
            World world = worlds.pollFirst();
            if (world != null && Bukkit.getWorld(world.getUID()) != null) return world;
        }
        return null;
    }

    private void expireRequests() {
        long now = System.currentTimeMillis();
        var iterator = waitingRequests.iterator();
        while (iterator.hasNext()) {
            InstanceRequest request = iterator.next();
            if (request.deadlineMillis() > now) continue;
            iterator.remove();
            request.callback().accept(null);
        }
    }

    private void failFirstRequest(String id) {
        var iterator = waitingRequests.iterator();
        while (iterator.hasNext()) {
            InstanceRequest request = iterator.next();
            if (!request.id().equals(id)) continue;
            iterator.remove();
            request.callback().accept(null);
            return;
        }
    }

    private void updateDirtyBossBars() {
        if (dirtyBossBars.isEmpty()) return;
        for (UUID bossId : Set.copyOf(dirtyBossBars)) {
            dirtyBossBars.remove(bossId);
            org.bukkit.entity.Entity entity = Bukkit.getEntity(bossId);
            if (!(entity instanceof LivingEntity boss) || boss.isDead()) continue;
            BossBar bar = bossBars.get(bossId);
            AttributeInstance maximum = boss.getAttribute(Attribute.MAX_HEALTH);
            if (bar != null && maximum != null) {
                bar.setProgress(Math.clamp(boss.getHealth() / maximum.getValue(), 0.0, 1.0));
            }
        }
    }

    private void tickBossMechanics() {
        if (bossAttacks.isEmpty()) return;
        for (Map.Entry<UUID, BossAttackState> entry : new ArrayList<>(bossAttacks.entrySet())) {
            UUID bossId = entry.getKey();
            Entity entity = Bukkit.getEntity(bossId);
            if (!(entity instanceof LivingEntity boss) || boss.isDead() || !boss.isValid()) {
                bossAttacks.remove(bossId);
                continue;
            }
            String id = boss.getPersistentDataContainer().get(dungeonId, PersistentDataType.STRING);
            if (id == null) {
                bossAttacks.remove(bossId);
                continue;
            }
            BossAttackState state = entry.getValue();
            int phase = bossPhase(boss);
            if (phase > state.phase) {
                state.phase = phase;
                announceBossPhase(boss, phase, id.endsWith("-hidden"));
            }
            if (state.pending != null) {
                if (mechanicsTick >= state.impactTick) {
                    applyBossAttack(boss, id, state);
                    state.pending = null;
                    state.center = null;
                    state.nextAttackTick = mechanicsTick
                            + attackInterval(id, state.phase, boss.getWorld().getName());
                } else if ((state.impactTick - mechanicsTick) % 5L == 0L) {
                    visuals.bossAttackTelegraph(state.center, state.pending, id.endsWith("-hidden"), false);
                }
                continue;
            }
            if (mechanicsTick < state.nextAttackTick) continue;
            BossSkillRules.Selection selection = BossSkillRules.select(id, state.sequence++, state.phase);
            state.skill = selection.skill();
            state.pending = selection.pattern();
            state.center = boss.getLocation().clone();
            prepareBossSkill(boss, state);
            long warningTicks = Math.clamp(plugin.getConfig().getLong(
                    "dungeons." + id + ".boss.abilities.telegraph-ticks", 36L), 20L, 80L);
            state.impactTick = mechanicsTick + warningTicks;
            boss.getWorld().getPlayers().forEach(player -> player.sendActionBar(Component.text(messages.text(
                    player, "dungeon.boss-skill." + state.skill.key(), Map.of(
                            "seconds", String.format(java.util.Locale.ROOT, "%.1f", warningTicks / 20.0))),
                    id.endsWith("-hidden") ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.RED)));
            visuals.bossAttackTelegraph(state.center, state.pending, id.endsWith("-hidden"), true);
        }
    }

    private void applyBossAttack(LivingEntity boss, String id, BossAttackState state) {
        boolean hidden = id.endsWith("-hidden");
        visuals.bossAttackImpact(state.center, state.pending, hidden);
        double configuredDamage = plugin.getConfig().getDouble(
                "dungeons." + id + ".boss.abilities.damage", hidden ? 10.0 : 7.0);
        double damage = Math.clamp(seasonModifiers(boss.getWorld().getName()).damage(
                configuredDamage * (1.0 + state.phase * 0.15)), 1.0, 40.0);
        Set<UUID> members = instanceMembers.getOrDefault(boss.getWorld().getName(), Set.of());
        for (UUID playerId : members) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline() || player.getWorld() != boss.getWorld()
                    || Math.abs(player.getLocation().getY() - state.center.getY()) > 4.0) continue;
            double deltaX = player.getLocation().getX() - state.center.getX();
            double deltaZ = player.getLocation().getZ() - state.center.getZ();
            if (!BossAttackRules.hits(state.pending, deltaX, deltaZ)) continue;
            player.damage(damage, boss);
            org.bukkit.util.Vector knockback = new org.bukkit.util.Vector(deltaX, 0.0, deltaZ);
            if (knockback.lengthSquared() < 0.01) knockback.setX(0.2);
            knockback.normalize().multiply(0.55).setY(0.28);
            player.setVelocity(player.getVelocity().add(knockback));
        }
        applySkillAftershock(boss, state, damage, members);
    }

    private void tickWaveMechanics() {
        if (waveAttacks.isEmpty()) return;
        for (Map.Entry<UUID, WaveAttackState> entry : new ArrayList<>(waveAttacks.entrySet())) {
            UUID mobId = entry.getKey();
            Entity entity = Bukkit.getEntity(mobId);
            if (!(entity instanceof Mob mob) || mob.isDead() || !mob.isValid()
                    || !mob.getPersistentDataContainer().has(waveMarker, PersistentDataType.BYTE)) {
                waveAttacks.remove(mobId);
                continue;
            }
            WaveAttackState state = entry.getValue();
            if (state.pending) {
                if (mechanicsTick >= state.impactTick) {
                    applyWaveAttack(mob, state);
                    state.pending = false;
                    state.center = null;
                    state.targetId = null;
                    state.nextAttackTick = mechanicsTick + state.intervalTicks;
                } else if ((state.impactTick - mechanicsTick) % 5L == 0L) {
                    visuals.waveSkillTelegraph(mob.getLocation(), state.center, state.skill, state.hidden, false);
                }
                continue;
            }
            if (mechanicsTick < state.nextAttackTick) continue;
            Player target = nearestWaveTarget(mob);
            if (target == null) {
                state.nextAttackTick = mechanicsTick + 20L;
                continue;
            }
            state.pending = true;
            state.targetId = target.getUniqueId();
            state.center = target.getLocation().clone();
            state.impactTick = mechanicsTick + state.skill.warningTicks();
            MobAppearanceService appearance = appearances();
            if (appearance != null) appearance.playAnimation(mob, "cast");
            for (Player member : activeInstancePlayers(mob.getWorld())) {
                member.sendActionBar(Component.text(messages.text(member,
                        "dungeon.wave-skill." + state.skill.key()),
                        state.hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW));
            }
            visuals.waveSkillTelegraph(mob.getLocation(), state.center, state.skill, state.hidden, true);
        }
    }

    private Player nearestWaveTarget(Mob mob) {
        return activeInstancePlayers(mob.getWorld()).stream()
                .filter(player -> player.getLocation().distanceSquared(mob.getLocation()) <= 24.0 * 24.0)
                .min(java.util.Comparator.comparingDouble(player ->
                        player.getLocation().distanceSquared(mob.getLocation())))
                .orElse(null);
    }

    private List<Player> activeInstancePlayers(World world) {
        return instanceMembers.getOrDefault(world.getName(), Set.of()).stream()
                .map(Bukkit::getPlayer)
                .filter(player -> player != null && player.isOnline() && !player.isDead()
                        && player.getWorld() == world)
                .limit(8)
                .toList();
    }

    private void applyWaveAttack(Mob mob, WaveAttackState state) {
        if (state.center == null || state.center.getWorld() != mob.getWorld()) return;
        MobAppearanceService appearance = appearances();
        if (appearance != null) appearance.playAnimation(mob, "attack");
        visuals.waveSkillImpact(mob.getLocation(), state.center, state.skill, state.hidden);
        switch (state.skill) {
            case RUIN_LUNGE -> {
                relocateWaveMob(mob, state.center, false);
                damageWaveRadius(mob, state.center, 2.35, state.damage, WaveImpact.PUSH);
            }
            case ECHO_SEAL -> damageWaveRadius(mob, state.center, 2.75, state.damage, WaveImpact.PULL);
            case FUME_VENT -> damageWaveRadius(mob, state.center, 3.25, state.damage, WaveImpact.LIFT);
            case SLAG_MORTAR -> damageWaveRadius(mob, state.center, 3.1, state.damage, WaveImpact.BURN);
            case STATIC_LINK -> applyStaticLink(mob, state);
            case GUST_FLANK -> {
                relocateWaveMob(mob, state.center, true);
                damageWaveRadius(mob, state.center, 2.6, state.damage, WaveImpact.PUSH);
            }
        }
    }

    private void applyStaticLink(Mob mob, WaveAttackState state) {
        Player primary = state.targetId == null ? null : Bukkit.getPlayer(state.targetId);
        if (primary == null || !primary.isOnline() || primary.isDead() || primary.getWorld() != mob.getWorld()) return;
        Location primaryCenter = primary.getLocation().clone().add(0.0, 1.0, 0.0);
        visuals.lightningChain(mob.getLocation().clone().add(0.0, 1.0, 0.0), primaryCenter);
        primary.damage(state.damage, mob);
        Player secondary = activeInstancePlayers(mob.getWorld()).stream()
                .filter(player -> !player.getUniqueId().equals(primary.getUniqueId()))
                .filter(player -> player.getLocation().distanceSquared(primary.getLocation()) <= 7.0 * 7.0)
                .min(java.util.Comparator.comparingDouble(player ->
                        player.getLocation().distanceSquared(primary.getLocation())))
                .orElse(null);
        if (secondary == null) return;
        visuals.lightningChain(primaryCenter, secondary.getLocation().clone().add(0.0, 1.0, 0.0));
        secondary.damage(state.damage * 0.7, mob);
    }

    private void damageWaveRadius(Mob mob, Location center, double radius, double damage, WaveImpact impact) {
        double radiusSquared = radius * radius;
        for (Player player : activeInstancePlayers(mob.getWorld())) {
            if (player.getLocation().distanceSquared(center) > radiusSquared) continue;
            player.damage(damage, mob);
            org.bukkit.util.Vector delta = player.getLocation().toVector().subtract(center.toVector()).setY(0.0);
            if (delta.lengthSquared() < 0.01) delta.setX(0.2);
            switch (impact) {
                case PUSH -> player.setVelocity(player.getVelocity().add(delta.normalize().multiply(0.55).setY(0.28)));
                case PULL -> player.setVelocity(player.getVelocity().add(delta.normalize().multiply(-0.42).setY(0.18)));
                case LIFT -> player.setVelocity(player.getVelocity().add(delta.normalize().multiply(0.22).setY(0.52)));
                case BURN -> {
                    player.setFireTicks(Math.max(player.getFireTicks(), 55));
                    player.setVelocity(player.getVelocity().add(delta.normalize().multiply(0.32).setY(0.22)));
                }
            }
        }
    }

    private void relocateWaveMob(Mob mob, Location target, boolean flank) {
        org.bukkit.util.Vector direction = target.toVector().subtract(mob.getLocation().toVector()).setY(0.0);
        if (direction.lengthSquared() < 0.01) direction.setZ(1.0);
        direction.normalize();
        org.bukkit.util.Vector offset = flank
                ? new org.bukkit.util.Vector(-direction.getZ(), 0.0, direction.getX()).multiply(2.2)
                : direction.clone().multiply(-1.45);
        Location destination = target.clone().add(offset);
        destination.setYaw(mob.getLocation().getYaw());
        destination.setPitch(0.0F);
        boolean safe = destination.getBlock().isPassable()
                && destination.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()
                && destination.clone().subtract(0.0, 1.0, 0.0).getBlock().getType().isSolid();
        if (safe) {
            visuals.silentStep(mob.getLocation(), destination);
            mob.teleport(destination);
        } else {
            mob.setVelocity(direction.multiply(flank ? 0.8 : 1.15).setY(0.24));
        }
    }

    private long attackInterval(String id, int phase, String worldName) {
        long base = Math.clamp(plugin.getConfig().getLong(
                "dungeons." + id + ".boss.abilities.interval-ticks", id.endsWith("-hidden") ? 130L : 170L),
                80L, 400L);
        long reduction = Math.clamp(plugin.getConfig().getLong(
                "dungeons." + id + ".boss.abilities.phase-reduction-ticks", 20L), 0L, 60L);
        return Math.max(60L, seasonModifiers(worldName).attackInterval(base - reduction * phase));
    }

    private SeasonExpeditionAffixRules.Modifiers seasonModifiers(String worldName) {
        SeasonRunSession run = seasonRuns.get(worldName);
        return SeasonExpeditionAffixRules.modifiers(run == null ? List.of() : run.rotation.affixes());
    }

    private static int bossPhase(LivingEntity boss) {
        AttributeInstance maximum = boss.getAttribute(Attribute.MAX_HEALTH);
        if (maximum == null || maximum.getValue() <= 0.0) return 0;
        double ratio = boss.getHealth() / maximum.getValue();
        if (ratio <= 0.33) return 2;
        if (ratio <= 0.66) return 1;
        return 0;
    }

    private void announceBossPhase(LivingEntity boss, int phase, boolean hidden) {
        MobAppearanceService appearance = appearances();
        if (appearance != null) appearance.playAnimation(boss, "phase");
        visuals.bossPhase(boss.getLocation(), hidden);
        for (Player player : boss.getWorld().getPlayers()) {
            player.showTitle(net.kyori.adventure.title.Title.title(
                    Component.text(messages.text(player, "dungeon.phase-title", Map.of("phase", phase + 1)),
                            hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.GOLD),
                    Component.text(messages.text(player, "dungeon.phase-subtitle"), NamedTextColor.RED),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(900),
                            java.time.Duration.ofMillis(300))));
        }
    }

    private void prepareBossSkill(LivingEntity boss, BossAttackState state) {
        MobAppearanceService appearance = appearances();
        if (appearance != null) appearance.playAnimation(boss, state.skill.animation());
        state.snapshots.clear();
        if (BossSkillRules.recordsPositions(state.skill)) {
            for (UUID playerId : instanceMembers.getOrDefault(boss.getWorld().getName(), Set.of())) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline() || player.getWorld() != boss.getWorld()) continue;
                Location location = player.getLocation().clone();
                state.snapshots.put(playerId, location);
                visuals.memoryMark(location, false);
            }
        }
        if (state.skill == BossSkillRules.Skill.SILENT_STEP) {
            Player target = instanceMembers.getOrDefault(boss.getWorld().getName(), Set.of()).stream()
                    .map(Bukkit::getPlayer)
                    .filter(player -> player != null && player.isOnline() && player.getWorld() == boss.getWorld())
                    .max(java.util.Comparator.comparingDouble(player -> player.getLocation().distanceSquared(boss.getLocation())))
                    .orElse(null);
            if (target != null) {
                org.bukkit.util.Vector facing = target.getLocation().getDirection().setY(0.0);
                if (facing.lengthSquared() < 0.01) facing.setZ(1.0);
                Location destination = target.getLocation().subtract(facing.normalize().multiply(2.2));
                destination.setY(state.center.getY());
                if (destination.getBlock().isPassable()
                        && destination.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()) {
                    visuals.silentStep(boss.getLocation(), destination);
                    boss.teleport(destination);
                    Location facingLocation = destination.clone();
                    facingLocation.setDirection(target.getEyeLocation().toVector().subtract(destination.toVector()));
                    boss.setRotation(facingLocation.getYaw(), facingLocation.getPitch());
                    state.center = destination.clone();
                }
            }
        }
    }

    private void applySkillAftershock(
            LivingEntity boss, BossAttackState state, double damage, Set<UUID> members
    ) {
        if (BossSkillRules.recordsPositions(state.skill)) {
            for (Map.Entry<UUID, Location> entry : state.snapshots.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                Location mark = entry.getValue();
                visuals.memoryBurst(mark);
                if (player == null || !player.isOnline() || player.getWorld() != mark.getWorld()) continue;
                if (player.getLocation().distanceSquared(mark) <= 5.0625) {
                    player.damage(Math.max(1.0, damage * 0.55), boss);
                }
            }
        }
        if (BossSkillRules.pulls(state.skill)) {
            for (UUID playerId : members) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline() || player.getWorld() != boss.getWorld()) continue;
                org.bukkit.util.Vector pull = state.center.toVector().subtract(player.getLocation().toVector());
                if (pull.lengthSquared() < 1.0 || pull.lengthSquared() > 100.0) continue;
                player.setVelocity(player.getVelocity().multiply(0.35).add(pull.normalize().multiply(0.9).setY(0.22)));
            }
            visuals.gravityPull(state.center, state.skill == BossSkillRules.Skill.CINNABAR_MAGNET);
        }
        if (BossSkillRules.chains(state.skill)) {
            List<Player> targets = members.stream().map(Bukkit::getPlayer)
                    .filter(player -> player != null && player.isOnline() && player.getWorld() == boss.getWorld())
                    .sorted(java.util.Comparator.comparing(Player::getUniqueId)).toList();
            for (int index = 1; index < targets.size(); index++) {
                Player from = targets.get(index - 1);
                Player to = targets.get(index);
                if (from.getLocation().distanceSquared(to.getLocation()) > 100.0) continue;
                visuals.lightningChain(from.getLocation().add(0.0, 1.0, 0.0),
                        to.getLocation().add(0.0, 1.0, 0.0));
                to.damage(Math.max(1.0, damage * 0.35), boss);
            }
        }
        if (BossSkillRules.leavesHazard(state.skill)) {
            scheduleHazard(boss, state.pending, state.center.clone(), damage * 0.22, members);
        }
    }

    private void scheduleHazard(
            LivingEntity boss, BossAttackRules.Pattern pattern, Location center,
            double damage, Set<UUID> members
    ) {
        final int[] pulses = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (++pulses[0] > 3 || boss.isDead() || !boss.isValid()) {
                task.cancel();
                return;
            }
            visuals.hazardPulse(center, pattern);
            for (UUID playerId : members) {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline() || player.getWorld() != center.getWorld()) continue;
                double deltaX = player.getLocation().getX() - center.getX();
                double deltaZ = player.getLocation().getZ() - center.getZ();
                if (Math.abs(player.getLocation().getY() - center.getY()) <= 4.0
                        && BossAttackRules.hits(pattern, deltaX, deltaZ)) {
                    player.damage(Math.max(1.0, damage), boss);
                }
            }
        }, 20L, 20L);
    }

    private MobAppearanceService appearances() {
        return plugin.getServer().getServicesManager().load(MobAppearanceService.class);
    }

    private int instanceWorldCount() {
        return (int) Bukkit.getWorlds().stream()
                .filter(world -> DungeonInstanceStorage.isInstanceName(world.getName())).count();
    }

    private double currentTps() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        if (performance != null && !performance.allowBackgroundWork()) return 0.0;
        double[] tps = Bukkit.getTPS();
        return tps.length == 0 ? 20.0 : Math.min(20.0, tps[0]);
    }

    enum InstanceRequestStatus {
        ASSIGNED,
        QUEUED,
        REJECTED
    }

    private record InstanceRequest(
            String id,
            Set<UUID> playerIds,
            SeasonExpeditionRotation rotation,
            long deadlineMillis,
            Consumer<World> callback
    ) {
    }

    private record BlockPlacement(int x, int y, int z, Material material) {
    }

    private static final class Encounter {
        private final String id;
        private final Location entrance;
        private final UUID audienceId;
        private final List<String> waveIds;
        private int waveIndex;

        private Encounter(String id, Location entrance, UUID audienceId, List<String> waveIds, int waveIndex) {
            this.id = id;
            this.entrance = entrance;
            this.audienceId = audienceId;
            this.waveIds = waveIds;
            this.waveIndex = waveIndex;
        }
    }

    private static final class SeasonRunSession {
        private final UUID runId;
        private final SeasonExpeditionRotation rotation;
        private final Set<UUID> participants;
        private long startedNanos = -1L;

        private SeasonRunSession(
                UUID runId, SeasonExpeditionRotation rotation, Set<UUID> participants
        ) {
            this.runId = runId;
            this.rotation = rotation;
            this.participants = Set.copyOf(participants);
        }

        private boolean start() {
            if (startedNanos >= 0L) return false;
            startedNanos = System.nanoTime();
            return true;
        }

        private boolean started() {
            return startedNanos >= 0L;
        }

        private long durationMillis() {
            if (!started()) return 0L;
            return Math.max(1_000L,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        }
    }

    private static final class BossAttackState {
        private long nextAttackTick;
        private long impactTick;
        private int sequence;
        private int phase;
        private BossAttackRules.Pattern pending;
        private BossSkillRules.Skill skill;
        private Location center;
        private final Map<UUID, Location> snapshots = new HashMap<>();

        private BossAttackState(long nextAttackTick, int sequence) {
            this.nextAttackTick = nextAttackTick;
            this.sequence = sequence;
        }
    }

    private enum WaveImpact {
        PUSH,
        PULL,
        LIFT,
        BURN
    }

    private static final class WaveAttackState {
        private final WaveSkillRules.Skill skill;
        private final long intervalTicks;
        private final double damage;
        private final boolean hidden;
        private long nextAttackTick;
        private long impactTick;
        private boolean pending;
        private UUID targetId;
        private Location center;

        private WaveAttackState(
                WaveSkillRules.Skill skill,
                long nextAttackTick,
                long intervalTicks,
                double damage,
                boolean hidden
        ) {
            this.skill = skill;
            this.nextAttackTick = nextAttackTick;
            this.intervalTicks = intervalTicks;
            this.damage = damage;
            this.hidden = hidden;
        }
    }

    private static final class ArenaBuild {
        private final String id;
        private final World world;
        private final int y;
        private final List<BlockPlacement> placements;
        private int index;

        private ArenaBuild(String id, World world, int y, List<BlockPlacement> placements, int index) {
            this.id = id;
            this.world = world;
            this.y = y;
            this.placements = placements;
            this.index = index;
        }
    }

    private Map<String, RewardDefinition> resolveRewards() {
        Map<String, RewardDefinition> result = new java.util.LinkedHashMap<>();
        for (DungeonRewardRules.RewardSpec fallback : DungeonRewardRules.all()) {
            String root = "dungeons." + fallback.id() + ".rewards.";
            String materialName = plugin.getConfig().getString(
                    root + "material.type", fallback.materialType());
            Material material = materialName == null ? null : Material.matchMaterial(materialName);
            if (material == null || material.isAir()) {
                throw new IllegalStateException("Invalid reward material for dungeon " + fallback.id()
                        + ": " + materialName);
            }
            int amount = plugin.getConfig().getInt(root + "material.amount", fallback.materialAmount());
            if (amount < 1 || amount > material.getMaxStackSize()) {
                throw new IllegalStateException("Invalid reward amount for dungeon " + fallback.id()
                        + ": " + amount + " (maximum " + material.getMaxStackSize() + ")");
            }
            String materialNameKey = requiredConfigString(
                    root + "material.name-key", fallback.materialNameKey(), fallback.id());
            NamespacedKey materialModel = modelKey(requiredConfigString(
                    root + "material.model", fallback.materialModel(), fallback.id()), fallback.id());

            String hiddenDungeonId = null;
            String ticketNameKey = null;
            NamespacedKey ticketModel = null;
            String skillBookId = null;
            String skillBookNameKey = null;
            NamespacedKey skillBookModel = null;
            if (fallback.hidden()) {
                skillBookId = requiredConfigString(
                        root + "skill-book.id", fallback.skillBookId(), fallback.id());
                skillBookNameKey = requiredConfigString(
                        root + "skill-book.name-key", fallback.skillBookNameKey(), fallback.id());
                skillBookModel = modelKey(requiredConfigString(root + "skill-book.model",
                        fallback.skillBookModel(), fallback.id()), fallback.id());
            } else {
                hiddenDungeonId = fallback.hiddenDungeonId();
                ticketNameKey = requiredConfigString(
                        root + "hidden-ticket.name-key", fallback.ticketNameKey(), fallback.id());
                ticketModel = modelKey(requiredConfigString(
                        root + "hidden-ticket.model", "hidden_ticket", fallback.id()), fallback.id());
            }
            result.put(fallback.id(), new RewardDefinition(
                    material, amount, materialNameKey, materialModel,
                    hiddenDungeonId, ticketNameKey, ticketModel,
                    skillBookId, skillBookNameKey, skillBookModel));
        }
        return Map.copyOf(result);
    }

    private String requiredConfigString(String path, String fallback, String dungeon) {
        String value = plugin.getConfig().getString(path, fallback);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + path + " for dungeon " + dungeon);
        }
        return value;
    }

    private NamespacedKey modelKey(String value, String dungeon) {
        String namespaced = value.contains(":") ? value : "originalrpg:" + value;
        NamespacedKey key = NamespacedKey.fromString(namespaced);
        if (key == null) throw new IllegalStateException("Invalid item model for dungeon " + dungeon + ": " + value);
        return key;
    }

    private DungeonRewardRequest rewardRequest(
            UUID runId,
            Player player,
            String dungeon,
            long experience,
            RewardDefinition reward,
            boolean hidden
    ) {
        UUID playerId = player.getUniqueId();
        List<DungeonRewardDelivery> deliveries = new ArrayList<>();
        deliveries.add(delivery(runId, playerId, "material",
                bossMaterial(player, reward, dungeon), "dungeon:" + dungeon + ":material"));
        Optional<DungeonTicketCandidate> ticket = Optional.empty();
        if (hidden) {
            UUID bookInstance = stableId(runId, playerId, "skill-book-instance");
            deliveries.add(delivery(runId, playerId, "skill-book",
                    skillBook(player, reward, dungeon, bookInstance),
                    "dungeon:" + dungeon + ":skill-book"));
        } else {
            double chance = Math.clamp(plugin.getConfig().getDouble(
                    "dungeons." + dungeon + ".hidden-ticket-chance", 0.20), 0.0, 1.0);
            int guarantee = Math.clamp(plugin.getConfig().getInt(
                    "dungeons." + dungeon + ".hidden-ticket-pity", 5), 1, 10_000);
            DungeonRewardDelivery ticketDelivery = delivery(runId, playerId, "hidden-ticket",
                    hiddenTicket(player, reward, dungeon), "dungeon:" + dungeon + ":hidden-ticket");
            ticket = Optional.of(new DungeonTicketCandidate(
                    ticketDelivery, dungeon + "-hidden-ticket", chance, guarantee));
        }
        return new DungeonRewardRequest(runId, dungeon, playerId, experience, deliveries, ticket);
    }

    private ItemStack hiddenTicket(Player player, RewardDefinition reward, String dungeon) {
        ItemStack ticket = named(Material.PAPER, 1,
                messages.text(player, reward.ticketNameKey()), NamedTextColor.LIGHT_PURPLE);
        ItemMeta meta = ticket.getItemMeta();
        meta.setItemModel(reward.ticketModel());
        meta.getPersistentDataContainer().set(ticketId, PersistentDataType.STRING, reward.hiddenDungeonId());
        meta.lore(List.of(
                Component.text(messages.text(player, "dungeon.items.ticket-source", Map.of(
                        "dungeon", messages.text(player, "dungeon.names." + dungeon))), NamedTextColor.GRAY),
                Component.text(messages.text(player, "dungeon.items.ticket-opens", Map.of(
                        "dungeon", messages.text(player, "dungeon.names." + reward.hiddenDungeonId()))),
                        NamedTextColor.LIGHT_PURPLE),
                Component.text(messages.text(player, "dungeon.items.ticket-rule"), NamedTextColor.DARK_GRAY)
        ));
        ticket.setItemMeta(meta);
        return ticket;
    }

    private ItemStack skillBook(
            Player player,
            RewardDefinition reward,
            String dungeon,
            UUID instanceId
    ) {
        ItemStack item = named(Material.ENCHANTED_BOOK, 1,
                messages.text(player, reward.skillBookNameKey()), NamedTextColor.GOLD);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(reward.skillBookModel());
        meta.getPersistentDataContainer().set(skillBook, PersistentDataType.STRING, reward.skillBookId());
        meta.getPersistentDataContainer().set(skillBookInstance, PersistentDataType.STRING, instanceId.toString());
        meta.lore(List.of(
                Component.text(messages.text(player, "dungeon.items.book-source", Map.of(
                        "dungeon", messages.text(player, "dungeon.names." + dungeon))), NamedTextColor.GRAY),
                Component.text(messages.text(player, skillBookUsageKey(reward.skillBookId())),
                        NamedTextColor.AQUA)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private static String skillBookUsageKey(String skillBookId) {
        return switch (skillBookId) {
            case "ancient-echo" -> "dungeon.items.book-use-ancient-echo";
            case "cinnabar-revelation" -> "dungeon.items.book-use-cinnabar-revelation";
            case "silent-thunder" -> "dungeon.items.book-use-silent-thunder";
            default -> throw new IllegalArgumentException("Unsupported hidden skill book: " + skillBookId);
        };
    }

    private DungeonRewardDelivery delivery(
            UUID runId,
            UUID playerId,
            String kind,
            ItemStack item,
            String source
    ) {
        String payload = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        return new DungeonRewardDelivery(stableId(runId, playerId, kind), payload, source);
    }

    private static UUID stableId(UUID runId, UUID playerId, String kind) {
        return UUID.nameUUIDFromBytes((runId + ":" + playerId + ":" + kind)
                .getBytes(StandardCharsets.UTF_8));
    }

    private ItemStack bossMaterial(Player player, RewardDefinition reward, String id) {
        boolean hidden = id.endsWith("-hidden");
        ItemStack item = named(reward.material(), reward.materialAmount(),
                messages.text(player, reward.materialNameKey()),
                hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(reward.materialModel());
        meta.getPersistentDataContainer().set(bossMaterial, PersistentDataType.STRING, id);
        meta.lore(List.of(
                Component.text(messages.text(player, "dungeon.items.core-source", Map.of(
                        "dungeon", messages.text(player, "dungeon.names." + id))), NamedTextColor.GRAY),
                Component.text(messages.text(player, hidden
                        ? "dungeon.items.core-tier-hidden" : "dungeon.items.core-tier-normal"),
                        hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA),
                Component.text(messages.text(player, "dungeon.items.core-use"), NamedTextColor.DARK_GRAY)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private void settleReward(
            DungeonRewardBatch batch,
            boolean notifyPlayer
    ) {
        if (!pendingRewardSettlements.add(batch.runId())) return;
        Map<UUID, DungeonRewardSettlement> settlements = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> completions = batch.requests().stream().map(request -> {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            try {
                dungeonRewards.settle(request).whenComplete((settlement, error) -> {
                    if (error != null) completion.completeExceptionally(error);
                    else {
                        settlements.put(request.playerId(), settlement);
                        completion.complete(null);
                    }
                });
            } catch (Throwable exception) {
                completion.completeExceptionally(exception);
            }
            return completion;
        }).toList();
        CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    if (!plugin.isEnabled()) return;
                    try {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (plugin.isEnabled()) {
                                finishRewardSettlement(batch, settlements, error, notifyPlayer);
                            }
                        });
                    } catch (IllegalStateException ignoredScheduleFailure) {
                        // The fsync journal remains present and the next process replays it.
                    }
                });
    }

    private void finishRewardSettlement(
            DungeonRewardBatch batch,
            Map<UUID, DungeonRewardSettlement> settlements,
            Throwable error,
            boolean notifyPlayer
    ) {
        pendingRewardSettlements.remove(batch.runId());
        if (error != null || settlements.size() != batch.requests().size()) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            plugin.getLogger().warning("Dungeon party reward remains in the recovery journal run="
                    + batch.runId() + ": " + (cause == null ? "incomplete settlement" : cause.getMessage()));
            if (notifyPlayer) notifyPending(batch);
            return;
        }
        settleCompletionEvidence(batch, () -> rewardIo.remove(batch).whenComplete((ignored, removeError) -> {
            if (removeError != null) {
                // Database receipts are idempotent and authoritative. Keeping
                // the batch replays only duplicates and retries this removal.
                plugin.getLogger().warning("Could not remove settled reward batch run="
                        + batch.runId() + ": " + removeError.getMessage());
            }
            if (!notifyPlayer) return;
            for (DungeonRewardRequest request : batch.requests()) {
                RewardDefinition reward = rewards.get(request.dungeonId());
                if (reward != null) notifyRewardSettled(request, reward, settlements.get(request.playerId()));
            }
        }));
    }

    private void settleCompletionEvidence(DungeonRewardBatch batch, Runnable acknowledged) {
        if (batch.seasonCompletion().isEmpty()) {
            acknowledged.run();
            return;
        }
        seasonExpeditions.settle(batch.seasonCompletion().orElseThrow(), success -> {
            if (success) acknowledged.run();
            else plugin.getLogger().warning("Dungeon completion remains in the recovery journal because season "
                    + "settlement was not acknowledged; run=" + batch.runId());
        });
    }

    private void notifyRewardSettled(
            DungeonRewardRequest request,
            RewardDefinition reward,
            DungeonRewardSettlement settlement
    ) {
        Player player = Bukkit.getPlayer(request.playerId());
        if (player == null || !player.isOnline()) return;
        boolean hidden = request.dungeonId().endsWith("-hidden");
        player.sendMessage(Component.text(messages.text(player, "dungeon.reward-material", Map.of(
                "amount", reward.materialAmount(),
                "item", messages.text(player, reward.materialNameKey()))),
                hidden ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA));
        if (hidden) {
            player.sendMessage(Component.text(messages.text(player, "dungeon.reward-skill-book", Map.of(
                    "item", messages.text(player, reward.skillBookNameKey()))), NamedTextColor.GOLD));
        } else {
            int guarantee = request.ticketCandidate().orElseThrow().guarantee();
            if (settlement.ticketDropped()) {
                player.sendMessage(Component.text(messages.text(player, "dungeon.ticket-obtained", Map.of(
                        "item", messages.text(player, reward.ticketNameKey()))), NamedTextColor.LIGHT_PURPLE));
            } else {
                player.sendMessage(Component.text(messages.text(player, "dungeon.ticket-pity", Map.of(
                        "current", settlement.ticketFailures(), "guarantee", guarantee)), NamedTextColor.GRAY));
            }
        }
        player.sendMessage(Component.text(messages.text(player, "dungeon.reward-mailbox", Map.of(
                "count", settlement.queuedDeliveries())), NamedTextColor.GREEN));
    }

    private void recoverRewardJournal() {
        if (shuttingDown || recoveryScanPending) return;
        recoveryScanPending = true;
        rewardIo.load().whenComplete((batches, error) -> {
            recoveryScanPending = false;
            if (error != null) {
                plugin.getLogger().severe("Could not read dungeon reward recovery journal: " + error.getMessage());
                return;
            }
            for (DungeonRewardBatch batch : batches) {
                String dungeon = batch.requests().getFirst().dungeonId();
                if (!rewards.containsKey(dungeon)) {
                    plugin.getLogger().severe("Reward journal references unknown dungeon " + dungeon);
                    continue;
                }
                settleReward(batch, false);
            }
        });
    }

    private static ItemStack named(Material material, int amount, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        item.setItemMeta(meta);
        return item;
    }

    /** All fields except the main-thread retry flag are immutable snapshots. */
    private static final class PendingRewardIntent {
        private final DungeonRewardBatch batch;
        private final String dungeon;
        private final String worldName;
        private final UUID sessionId;
        private final Set<UUID> recipientIds;
        private final boolean hidden;
        private final String victor;
        private boolean persisting;

        private PendingRewardIntent(
                DungeonRewardBatch batch,
                String dungeon,
                String worldName,
                UUID sessionId,
                Set<UUID> recipientIds,
                boolean hidden,
                String victor
        ) {
            this.batch = batch;
            this.dungeon = dungeon;
            this.worldName = worldName;
            this.sessionId = sessionId;
            this.recipientIds = Set.copyOf(recipientIds);
            this.hidden = hidden;
            this.victor = victor;
        }
    }

    private record RewardDefinition(
            Material material,
            int materialAmount,
            String materialNameKey,
            NamespacedKey materialModel,
            String hiddenDungeonId,
            String ticketNameKey,
            NamespacedKey ticketModel,
            String skillBookId,
            String skillBookNameKey,
            NamespacedKey skillBookModel
    ) {
    }
}
