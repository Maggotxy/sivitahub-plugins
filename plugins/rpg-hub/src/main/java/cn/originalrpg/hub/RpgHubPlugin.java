package cn.originalrpg.hub;

import cn.originalrpg.api.service.GuideMenuService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.UUID;

public final class RpgHubPlugin extends JavaPlugin implements Listener {
    private NamespacedKey versionKey;
    private NamespacedKey centerXKey;
    private NamespacedKey baseYKey;
    private NamespacedKey centerZKey;
    private HubBuilder builder;
    private HubNpcManager npcs;
    private HubTravel travel;
    private BackTravel back;
    private HomeTravel home;
    private HubWayfinder wayfinder;
    private HubMessages hubMessages;
    private AuthenticationSession authentication;
    private volatile HubSite site;
    private volatile boolean building;
    private BukkitTask creatureSweepTask;
    private BukkitTask squaremapRegistrationTask;
    private BukkitTask npcPreparationTask;
    private Runnable squaremapCleanup = () -> { };
    private int squaremapRegistrationAttempts;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        versionKey = new NamespacedKey(this, "hub_build_version");
        centerXKey = new NamespacedKey(this, "hub_center_x");
        baseYKey = new NamespacedKey(this, "hub_base_y");
        centerZKey = new NamespacedKey(this, "hub_center_z");
        MessageService localeService = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        GuideMenuService guideMenus = Objects.requireNonNull(
                getServer().getServicesManager().load(GuideMenuService.class), "GuideMenuService");
        ProfileService profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        JourneyService journeys = Objects.requireNonNull(
                getServer().getServicesManager().load(JourneyService.class), "JourneyService");
        CombatStateService combat = Objects.requireNonNull(
                getServer().getServicesManager().load(CombatStateService.class), "CombatStateService");
        hubMessages = new HubMessages(this, localeService);
        authentication = new AuthenticationSession();
        back = new BackTravel(this, hubMessages, combat, authentication);
        builder = new HubBuilder(this);
        npcs = new HubNpcManager(this, guideMenus, profiles, journeys, hubMessages, authentication);
        travel = new HubTravel(this, hubMessages, combat, journeys,
                back, this::readySite, authentication);
        wayfinder = new HubWayfinder(this, hubMessages, journeys,
                () -> OptionalServices.enabled(this, PerformanceService.class), this::readySite, authentication);
        home = new HomeTravel(this, hubMessages, combat, back, authentication);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(back, this);
        getServer().getPluginManager().registerEvents(home, this);
        getServer().getPluginManager().registerEvents(wayfinder, this);
        getServer().getPluginManager().registerEvents(
                new HubProtectionListener(this, this::currentSite, npcs, travel, hubMessages), this);
        getServer().getPluginManager().registerEvents(
                new LegacyHubMigration(this, this::readySite, authentication), this);
        HubCommand command = new HubCommand(this, travel, home, back, wayfinder, hubMessages);
        registerCommand("hub", command);
        registerCommand("wild", command);
        registerCommand("home", command);
        registerCommand("sethome", command);
        registerCommand("back", command);
        registerCommand("hubfind", command);
        registerCommand("hubadmin", command);
        getServer().getScheduler().runTask(this, this::initialize);
    }

    @Override
    public void onDisable() {
        if (creatureSweepTask != null) creatureSweepTask.cancel();
        creatureSweepTask = null;
        if (npcPreparationTask != null) npcPreparationTask.cancel();
        npcPreparationTask = null;
        if (builder != null) builder.stop();
        if (npcs != null) npcs.close();
        if (wayfinder != null) wayfinder.close();
        if (travel != null) travel.close();
        if (home != null) home.close();
        if (back != null) back.close();
        if (authentication != null) authentication.clear();
        cancelSquaremapRegistration();
        squaremapCleanup.run();
        squaremapCleanup = () -> { };
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        UUID playerId = event.player().getUniqueId();
        authentication.activate(playerId);
        if (travel != null) travel.revoke(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        UUID playerId = event.player().getUniqueId();
        authentication.revoke(playerId);
        if (travel != null) travel.revoke(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        authentication.forget(playerId);
        if (travel != null) travel.revoke(playerId);
    }

    HubSite currentSite() {
        return site;
    }

    HubSite readySite() {
        return building ? null : site;
    }

    boolean restoreNpcs() {
        HubSite current = readySite();
        if (current == null) return false;
        prepareRuntime(current, true);
        return true;
    }

    boolean rebuild() {
        HubSite current = site;
        if (current == null || building || builder.running()) return false;
        building = true;
        cancelSquaremapRegistration();
        squaremapCleanup.run();
        squaremapCleanup = () -> { };
        builder.start(current, getConfig().getInt("hub.blocks-per-tick", 8_000),
                this::finishBuild, this::constructionFailed);
        return true;
    }

    String status(CommandSender audience) {
        HubSite current = site;
        if (current == null) return hubMessages.text(audience, "building");
        return hubMessages.text(audience, "status",
                "world", current.world().getName(),
                "x", current.centerX(), "y", current.baseY(), "z", current.centerZ(),
                "version", current.version(), "built", !building, "npcs", npcs.count(current));
    }

    private void initialize() {
        String worldName = getConfig().getString("hub.world", "world");
        World world = loadHubWorld(worldName);
        if (world == null) {
            getLogger().severe("Hub world is not loaded: " + worldName);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        int radius = Math.clamp(getConfig().getInt("hub.radius", 52), 36, 96);
        int protectionRadius = Math.clamp(getConfig().getInt("hub.protection-radius", 60), radius, 128);
        int version = Math.max(1, getConfig().getInt("hub.build-version", 1));
        PersistentDataContainer data = world.getPersistentDataContainer();
        Integer storedVersion = data.get(versionKey, PersistentDataType.INTEGER);
        Integer storedX = data.get(centerXKey, PersistentDataType.INTEGER);
        Integer storedY = data.get(baseYKey, PersistentDataType.INTEGER);
        Integer storedZ = data.get(centerZKey, PersistentDataType.INTEGER);
        int centerX = storedX == null ? world.getSpawnLocation().getBlockX() : storedX;
        int centerZ = storedZ == null ? world.getSpawnLocation().getBlockZ() : storedZ;
        int baseY = storedY == null ? HubBlueprint.selectBaseY(world, centerX, centerZ) : storedY;
        site = new HubSite(world, centerX, baseY, centerZ, radius, protectionRadius, version);
        configureHubWorld(world, site);
        if (storedVersion != null && storedVersion == version) {
            finishRuntime(site);
            return;
        }
        if (!getConfig().getBoolean("hub.auto-build", true)) {
            getLogger().warning("Hub is not built and hub.auto-build is disabled.");
            return;
        }
        building = true;
        builder.start(site, getConfig().getInt("hub.blocks-per-tick", 8_000),
                this::finishBuild, this::constructionFailed);
    }

    private World loadHubWorld(String worldName) {
        World loaded = getServer().getWorld(worldName);
        if (loaded != null) return loaded;
        if (!getConfig().getBoolean("hub.create-isolated-world", false)) return null;
        getLogger().info("Creating isolated void hub world: " + worldName);
        return new WorldCreator(worldName)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generatorSettings("{\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"biome\":\"minecraft:plains\"}")
                .createWorld();
    }

    private void configureHubWorld(World world, HubSite current) {
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setGameRule(GameRules.PVP, false);
        world.setTime(6_000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.RESPAWN_RADIUS, 0);
        world.getWorldBorder().setCenter(current.centerX() + 0.5, current.centerZ() + 0.5);
        world.getWorldBorder().setSize((current.protectionRadius() + 4.0) * 2.0);
        world.getWorldBorder().setWarningDistance(0);
    }

    private void finishBuild(HubSite completed) {
        PersistentDataContainer data = completed.world().getPersistentDataContainer();
        data.set(versionKey, PersistentDataType.INTEGER, completed.version());
        data.set(centerXKey, PersistentDataType.INTEGER, completed.centerX());
        data.set(baseYKey, PersistentDataType.INTEGER, completed.baseY());
        data.set(centerZKey, PersistentDataType.INTEGER, completed.centerZ());
        completed.world().setSpawnLocation(completed.arrival());
        building = false;
        finishRuntime(completed);
        getLogger().info("SivitaHub hub construction completed.");
    }

    private void finishRuntime(HubSite current) {
        current.world().setSpawnLocation(current.arrival());
        prepareRuntime(current, false);
    }

    private void constructionFailed() {
        building = false;
        getLogger().severe("Hub construction stopped before any block changes; use /hubadmin rebuild after checking storage.");
    }

    private void prepareRuntime(HubSite current, boolean forced) {
        if (npcPreparationTask != null) npcPreparationTask.cancel();
        npcPreparationTask = null;
        int minChunkX = (current.centerX() - current.radius() - 8) >> 4;
        int maxChunkX = (current.centerX() + current.radius() + 8) >> 4;
        int minChunkZ = (current.centerZ() - current.radius() - 8) >> 4;
        int maxChunkZ = (current.centerZ() + current.radius() + 8) >> 4;
        GeneratedChunkLoads.rectangle(current.world(), minChunkX, maxChunkX, minChunkZ, maxChunkZ, false)
                .whenComplete((chunks, error) -> sync(() -> {
            if (current != site || building) return;
            if (error != null || chunks == null || chunks.isEmpty()) {
                getLogger().severe("Completed hub chunks are missing; NPC runtime was not started");
                return;
            }
            finishPreparedRuntime(current, forced);
        }));
    }

    private void finishPreparedRuntime(HubSite current, boolean forced) {
        if (getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
            // Apply the region before spawning mannequins. A persisted region
            // from an older build may still contain mob-spawning=deny, which
            // would cancel every mannequin while allowing its label to spawn.
            getLogger().info("WorldGuard hub region active=" + WorldGuardHubIntegration.apply(this, current));
        }
        int removedCreatures = purgeUnexpectedCreatures(current);
        if (removedCreatures > 0) getLogger().info(
                "Removed " + removedCreatures + " unexpected living entities from the isolated hub world.");
        int activeNpcs = npcs.ensure(current);
        getLogger().info("Hub NPCs active=" + activeNpcs + "/" + HubRole.values().length);
        if (activeNpcs < HubRole.values().length) {
            npcPreparationTask = getServer().getScheduler().runTaskLater(this,
                    () -> prepareRuntime(current, forced), 20L);
            return;
        }
        if (creatureSweepTask != null) creatureSweepTask.cancel();
        long sweepTicks = Math.clamp(getConfig().getLong(
                "hub.creature-sweep-ticks", 200L), 100L, 1200L);
        creatureSweepTask = getServer().getScheduler().runTaskTimer(this,
                () -> purgeUnexpectedCreatures(current), sweepTicks, sweepTicks);
        if (getServer().getPluginManager().isPluginEnabled("squaremap")) {
            scheduleSquaremapRegistration(current);
        }
    }

    private void sync(Runnable action) {
        if (!isEnabled()) return;
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Disable cancels runtime preparation.
        }
    }

    private void scheduleSquaremapRegistration(HubSite current) {
        cancelSquaremapRegistration();
        squaremapRegistrationAttempts = 0;
        // squaremap enables before the isolated hub exists. Its WorldLoadEvent
        // callback normally makes the map world ready quickly, but startup
        // order and slow storage can delay it. Retry for at most one minute;
        // never leave a permanent poller behind.
        squaremapRegistrationTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (!isEnabled() || current != readySite()) {
                cancelSquaremapRegistration();
                return;
            }
            squaremapRegistrationAttempts++;
            if (SquaremapHubIntegration.worldReady(current)) {
                squaremapCleanup.run();
                squaremapCleanup = SquaremapHubIntegration.register(this, current);
                cancelSquaremapRegistration();
                return;
            }
            if (squaremapRegistrationAttempts >= 30) {
                getLogger().warning("squaremap did not enable the isolated hub world within 60 seconds; "
                        + "landmark registration stopped instead of polling forever.");
                cancelSquaremapRegistration();
            }
        }, 40L, 40L);
    }

    private void cancelSquaremapRegistration() {
        if (squaremapRegistrationTask != null) squaremapRegistrationTask.cancel();
        squaremapRegistrationTask = null;
    }

    private int purgeUnexpectedCreatures(HubSite current) {
        int removed = 0;
        for (LivingEntity entity : current.world().getLivingEntities()) {
            if (entity instanceof Player || npcs.managed(entity) || entity.hasMetadata("NPC")) continue;
            entity.remove();
            removed++;
        }
        return removed;
    }

    private void registerCommand(String name, HubCommand handler) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), name + " command");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }
}
