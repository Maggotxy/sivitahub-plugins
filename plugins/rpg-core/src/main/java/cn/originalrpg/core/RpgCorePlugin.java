package cn.originalrpg.core;

import cn.originalrpg.api.model.ChapterDefinition;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.EconomyAnalyticsService;
import cn.originalrpg.api.service.CommerceService;
import cn.originalrpg.api.service.CurrencyExchangeService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.PersistenceMetricsService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.SkillService;
import cn.originalrpg.api.service.MerchantService;
import cn.originalrpg.api.service.PetProgressService;
import cn.originalrpg.api.service.PetRevivalService;
import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.DungeonEntryService;
import cn.originalrpg.api.service.DungeonRewardService;
import cn.originalrpg.api.service.CommunityService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.CommissionEvidenceService;
import cn.originalrpg.api.service.SeasonExpeditionService;
import cn.originalrpg.api.service.AdminTestService;
import cn.originalrpg.api.service.AdminIncidentCompensationService;
import cn.originalrpg.api.service.AscensionService;
import cn.originalrpg.api.service.HiddenPathService;
import cn.originalrpg.api.service.EquipmentOperationService;
import cn.originalrpg.api.service.EquipmentLedgerAuditService;
import cn.originalrpg.core.command.RpgCommand;
import cn.originalrpg.core.command.LanguageCommand;
import cn.originalrpg.core.i18n.YamlMessageService;
import cn.originalrpg.core.persistence.Database;
import cn.originalrpg.core.persistence.JdbcEconomyService;
import cn.originalrpg.core.persistence.JdbcEconomyAnalyticsService;
import cn.originalrpg.core.persistence.JdbcActivityEconomyService;
import cn.originalrpg.core.persistence.JdbcCommerceService;
import cn.originalrpg.core.persistence.JdbcCurrencyExchangeService;
import cn.originalrpg.core.persistence.JdbcProfileService;
import cn.originalrpg.core.persistence.JdbcMarketService;
import cn.originalrpg.core.persistence.JdbcSkillService;
import cn.originalrpg.core.persistence.JdbcMerchantService;
import cn.originalrpg.core.persistence.JdbcPetProgressService;
import cn.originalrpg.core.persistence.JdbcPetRevivalService;
import cn.originalrpg.core.persistence.JdbcClassBranchService;
import cn.originalrpg.core.persistence.JdbcDungeonEntryService;
import cn.originalrpg.core.persistence.JdbcDungeonRewardService;
import cn.originalrpg.core.persistence.JdbcCommunityService;
import cn.originalrpg.core.persistence.JdbcChapterService;
import cn.originalrpg.core.persistence.JdbcCommissionEvidenceService;
import cn.originalrpg.core.persistence.JdbcSeasonExpeditionService;
import cn.originalrpg.core.persistence.JdbcNodeLease;
import cn.originalrpg.core.persistence.JdbcAdminTestService;
import cn.originalrpg.core.persistence.JdbcAdminIncidentCompensationService;
import cn.originalrpg.core.persistence.JdbcAscensionService;
import cn.originalrpg.core.persistence.JdbcHiddenPathService;
import cn.originalrpg.core.persistence.JdbcEquipmentOperationService;
import cn.originalrpg.core.persistence.JdbcEquipmentLedgerAuditService;
import cn.originalrpg.core.persistence.OperationalStarlightPolicy;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class RpgCorePlugin extends JavaPlugin {
    private Database database;
    private cn.originalrpg.core.persistence.MechanismRegistry mechanisms;
    private ThreadPoolExecutor databaseExecutor;
    private ScheduledExecutorService nodeLeaseExecutor;
    private JdbcNodeLease nodeLease;
    private final AtomicBoolean chapterRefreshInFlight = new AtomicBoolean();
    private final AtomicBoolean nodeLeaseRefreshInFlight = new AtomicBoolean();
    private final FailStopCoordinator failStop = new FailStopCoordinator();
    private final AtomicBoolean reservationRecoveryInFlight = new AtomicBoolean();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            database = Database.open(getConfig());
            database.migrate();
            // Phase 0: mechanism registry & Kill Switch (C-005)
            mechanisms = new cn.originalrpg.core.persistence.MechanismRegistry(database.dataSource());
            mechanisms.initialize();
            getLogger().info("Mechanism registry initialized (C-005): hunt/dungeon/commission/market/season/starlight/community switches ready.");
            int maximumPoolSize = Math.clamp(getConfig().getInt("database.maximum-pool-size", 6), 2, 32);
            int databaseThreads = Math.clamp(getConfig().getInt("database.executor-threads", maximumPoolSize),
                    2, maximumPoolSize);
            int queueCapacity = Math.clamp(getConfig().getInt("database.queue-capacity", 256), 32, 4096);
            databaseExecutor = new ThreadPoolExecutor(
                    databaseThreads, databaseThreads, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    Thread.ofPlatform().daemon(true).name("rpg-database-", 0L).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
            // Both scheduling and JDBC capacity are isolated from gameplay.
            // Tick stalls and a saturated ledger queue cannot starve fencing.
            nodeLeaseExecutor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon(true).name("rpg-node-lease").factory());

            String nodeId = environmentOrConfig("RPG_NODE_ID",
                    getConfig().getString("network.node-id", "standalone"));
            long nodeLeaseSeconds = Math.clamp(
                    getConfig().getLong("network.node-lease-seconds", 60L), 30L, 300L);
            nodeLease = new JdbcNodeLease(
                    database.nodeLeaseDataSource(), nodeLeaseExecutor, nodeId,
                    Duration.ofSeconds(nodeLeaseSeconds), database.localH2());
            // File-backed H2 has already fenced every previous process via its
            // OS file lock. PostgreSQL keeps the stricter unexpired-token rule
            // needed by a future multi-Paper network.
            if (!nodeLease.acquire(database.localH2())) {
                throw new IllegalStateException("RPG_NODE_ID '" + nodeId
                        + "' already has an unexpired Paper process lease");
            }

            int startingEnergy = getConfig().getInt("progression.starting-energy", 100);
            int energyPerStamina = getConfig().getInt("progression.energy-per-stamina", 2);
            BigDecimal startingBalance = new BigDecimal(getConfig().getString("progression.starting-balance", "0.00"));

            Duration startupTimeout = Duration.ofMillis(StartupGate.boundedTimeoutMillis(
                    getConfig().getLong("database.startup-operation-timeout-ms",
                            StartupGate.DEFAULT_TIMEOUT_MILLIS)));

            JdbcChapterService chapters = createChapterService();
            var initialChapter = StartupGate.await(
                    chapters.initialize(), startupTimeout, "Initial chapter ledger gate");
            JdbcProfileService profiles = new JdbcProfileService(
                    database.dataSource(), databaseExecutor, startingEnergy, energyPerStamina);
            JdbcEconomyService economy = new JdbcEconomyService(database.dataSource(), databaseExecutor, startingBalance);
            JdbcActivityEconomyService activityEconomy = new JdbcActivityEconomyService(
                    database.dataSource(), databaseExecutor, startingBalance, mechanisms);
            JdbcCommissionEvidenceService commissionEvidence = new JdbcCommissionEvidenceService(
                    database.dataSource(), databaseExecutor);
            JdbcEconomyAnalyticsService economyAnalytics = new JdbcEconomyAnalyticsService(
                    database.dataSource(), databaseExecutor);
            OperationalStarlightPolicy starlightPolicy = new OperationalStarlightPolicy(
                    getConfig().getLong("commerce.admin-starlight.maximum-per-grant", 500L),
                    getConfig().getLong("commerce.admin-starlight.daily-limit", 2_000L),
                    getConfig().getLong("commerce.admin-starlight.weekly-limit", 8_000L),
                    ZoneId.of(getConfig().getString(
                            "commerce.admin-starlight.timezone", "Asia/Shanghai")));
            JdbcCommerceService commerce = new JdbcCommerceService(
                    database.dataSource(), databaseExecutor, startingBalance, starlightPolicy, mechanisms);
            JdbcCurrencyExchangeService currencyExchange = new JdbcCurrencyExchangeService(
                    database.dataSource(), databaseExecutor, startingBalance,
                    getConfig().getConfigurationSection("wealth-exchange"));
            YamlMessageService messages = new YamlMessageService(this);
            JdbcMarketService market = new JdbcMarketService(
                    database.dataSource(), databaseExecutor, startingBalance, mechanisms);
            JdbcEquipmentOperationService equipmentOperations = new JdbcEquipmentOperationService(
                    database.dataSource(), databaseExecutor, startingBalance);
            JdbcEquipmentLedgerAuditService equipmentLedger = new JdbcEquipmentLedgerAuditService(
                    database.dataSource(), databaseExecutor);
            JdbcSkillService skills = new JdbcSkillService(
                    database.dataSource(), databaseExecutor, startingBalance);
            JdbcAdminTestService adminTests = new JdbcAdminTestService(
                    database.dataSource(), databaseExecutor, profiles, skills, startingEnergy, energyPerStamina);
            JdbcAdminIncidentCompensationService incidentCompensations =
                    new JdbcAdminIncidentCompensationService(
                            database.dataSource(), databaseExecutor, profiles, startingBalance);
            JdbcMerchantService merchant = new JdbcMerchantService(
                    database.dataSource(), databaseExecutor, startingBalance);
            JdbcPetProgressService petProgress = new JdbcPetProgressService(
                    database.dataSource(), databaseExecutor);
            JdbcPetRevivalService petRevivals = new JdbcPetRevivalService(
                    database.dataSource(), databaseExecutor, startingBalance);
            JdbcClassBranchService classBranches = new JdbcClassBranchService(database.dataSource(), databaseExecutor);
            JdbcAscensionService ascensions = new JdbcAscensionService(
                    database.dataSource(), databaseExecutor, profiles, chapters::maximumOpenAscension);
            JdbcHiddenPathService hiddenPaths = new JdbcHiddenPathService(database.dataSource(), databaseExecutor);
            JdbcDungeonEntryService dungeonEntries = new JdbcDungeonEntryService(
                    database.dataSource(), databaseExecutor, profiles, startingEnergy, energyPerStamina, nodeLease);
            JdbcDungeonRewardService dungeonRewards = new JdbcDungeonRewardService(
                    database.dataSource(), databaseExecutor, profiles, mechanisms);
            JdbcSeasonExpeditionService seasonExpeditions = new JdbcSeasonExpeditionService(
                    database.dataSource(), databaseExecutor,
                    Math.clamp(getConfig().getInt("season-expedition.weekly-honor", 100), 0, 1_000),
                    Math.clamp(getConfig().getInt("season-expedition.server-first-bonus", 50), 0, 1_000),
                    mechanisms);
            JdbcCommunityService communities = new JdbcCommunityService(
                    database.dataSource(), databaseExecutor, mechanisms);
            var recoveredEntries = StartupGate.await(
                    dungeonEntries.recoverAll(), startupTimeout, "Dungeon reservation recovery gate");
            if (recoveredEntries.reservations() > 0) {
                getLogger().warning("Recovered " + recoveredEntries.reservations()
                        + " unfinished dungeon energy reservations (" + recoveredEntries.energy() + " energy restored).");
            }
            getServer().getServicesManager().register(ProfileService.class, profiles, this, ServicePriority.Normal);
            getServer().getServicesManager().register(EconomyService.class, economy, this, ServicePriority.Normal);
            getServer().getServicesManager().register(ActivityEconomyService.class, activityEconomy, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    CommissionEvidenceService.class, commissionEvidence, this, ServicePriority.Normal);
            getServer().getServicesManager().register(EconomyAnalyticsService.class, economyAnalytics, this, ServicePriority.Normal);
            getServer().getServicesManager().register(CommerceService.class, commerce, this, ServicePriority.Normal);
            getServer().getServicesManager().register(CurrencyExchangeService.class, currencyExchange,
                    this, ServicePriority.Normal);
            getServer().getServicesManager().register(MessageService.class, messages, this, ServicePriority.Normal);
            getServer().getServicesManager().register(MarketService.class, market, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    EquipmentOperationService.class, equipmentOperations, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    EquipmentLedgerAuditService.class, equipmentLedger, this, ServicePriority.Normal);
            getServer().getServicesManager().register(SkillService.class, skills, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    AdminTestService.class, adminTests, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    AdminIncidentCompensationService.class, incidentCompensations, this, ServicePriority.Normal);
            getServer().getServicesManager().register(MerchantService.class, merchant, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    PetProgressService.class, petProgress, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    PetRevivalService.class, petRevivals, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    ClassBranchService.class, classBranches, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    AscensionService.class, ascensions, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    HiddenPathService.class, hiddenPaths, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    DungeonEntryService.class, dungeonEntries, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    DungeonRewardService.class, dungeonRewards, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    SeasonExpeditionService.class, seasonExpeditions, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    CommunityService.class, communities, this, ServicePriority.Normal);
            getServer().getServicesManager().register(
                    ChapterService.class, chapters, this, ServicePriority.Normal);
            PersistenceMetricsService persistenceMetrics = () -> database.metrics(databaseExecutor);
            getServer().getServicesManager().register(PersistenceMetricsService.class, persistenceMetrics,
                    this, ServicePriority.Normal);

            RpgCommand command = new RpgCommand(this, profiles, activityEconomy, chapters, messages);
            PluginCommand rpg = Objects.requireNonNull(getCommand("rpg"), "rpg command");
            rpg.setExecutor(command);
            LanguageCommand languageCommand = new LanguageCommand(messages);
            PluginCommand language = Objects.requireNonNull(getCommand("language"), "language command");
            language.setExecutor(languageCommand);
            language.setTabCompleter(languageCommand);
            getServer().getPluginManager().registerEvents(command, this);
            startEnergyRegeneration(profiles);
            startChapterRefresh(chapters);
            startNodeLeaseHeartbeat(nodeLeaseSeconds);
            startReservationRecovery(dungeonEntries);
            getLogger().info("RPG core started with transactional persistence; season="
                    + initialChapter.seasonId() + " chapter=" + initialChapter.chapterId()
                    + " revision=" + initialChapter.revision() + " node=" + nodeLease.nodeId() + ".");
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "RPG core failed to start", exception);
            requestServerShutdown("RPG core initialization failed");
        }
    }

    private JdbcChapterService createChapterService() {
        ConfigurationSection definitions = getConfig().getConfigurationSection("chapters.definitions");
        if (definitions == null) throw new IllegalStateException("Chapter definitions are missing");
        List<ChapterDefinition> chapters = new ArrayList<>();
        for (String id : definitions.getKeys(false)) {
            chapters.add(new ChapterDefinition(id,
                    definitions.getInt(id + ".sequence", -1),
                    definitions.getInt(id + ".maximum-open-ascension", -1)));
        }
        return new JdbcChapterService(database.dataSource(), databaseExecutor,
                getConfig().getString("chapters.season-id", "launch-1"),
                getConfig().getString("chapters.initial", "awakening"), chapters);
    }

    private void startChapterRefresh(JdbcChapterService chapters) {
        long seconds = Math.clamp(getConfig().getLong("chapters.refresh-seconds", 30L), 10L, 300L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!chapterRefreshInFlight.compareAndSet(false, true)) return;
            chapters.refresh().whenComplete((state, error) -> {
                chapterRefreshInFlight.set(false);
                if (error != null) getLogger().warning("Chapter refresh failed closed: " + error.getMessage());
            });
        }, seconds * 20L, seconds * 20L);
    }

    private void startNodeLeaseHeartbeat(long leaseSeconds) {
        long heartbeatSeconds = Math.max(5L, leaseSeconds / 3L);
        nodeLeaseExecutor.scheduleWithFixedDelay(() -> {
            if (!nodeLeaseRefreshInFlight.compareAndSet(false, true)) return;
            try {
                boolean renewed = nodeLease.renew();
                if (!renewed) requestServerShutdown("Paper node lease was fenced by another process");
            } catch (RuntimeException error) {
                Duration safeRetryWindow = Duration.ofSeconds(heartbeatSeconds).plusSeconds(5L);
                if (nodeLease.mayStillBeValidFor(safeRetryWindow)) {
                    getLogger().warning("Paper node lease heartbeat failed; retrying before expiry: "
                            + error.getMessage());
                } else {
                    requestServerShutdown("Paper node lease expired while its database was unavailable");
                }
            } finally {
                nodeLeaseRefreshInFlight.set(false);
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    private void startReservationRecovery(JdbcDungeonEntryService dungeonEntries) {
        long seconds = Math.clamp(
                getConfig().getLong("network.orphan-recovery-seconds", 30L), 15L, 300L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!reservationRecoveryInFlight.compareAndSet(false, true)) return;
            dungeonEntries.recoverAll().whenComplete((recovery, error) -> {
                reservationRecoveryInFlight.set(false);
                if (error != null) {
                    getLogger().warning("Orphaned dungeon reservation recovery failed: " + error.getMessage());
                } else if (recovery.reservations() > 0) {
                    getLogger().warning("Recovered " + recovery.reservations()
                            + " orphaned dungeon energy reservations (" + recovery.energy() + " energy restored).");
                }
            });
        }, seconds * 20L, seconds * 20L);
    }

    private void requestServerShutdown(String reason) {
        boolean requested = failStop.request(Bukkit.isPrimaryThread(), Bukkit::shutdown,
                shutdown -> getServer().getScheduler().runTask(this, shutdown));
        if (requested) {
            getLogger().severe(reason
                    + "; shutting down Paper to prevent gameplay without its authoritative ledger.");
        }
    }

    private void startEnergyRegeneration(ProfileService profiles) {
        long intervalSeconds = Math.max(30L, getConfig().getLong("progression.energy-regen.interval-seconds", 300L));
        int amount = Math.max(1, getConfig().getInt("progression.energy-regen.amount", 2));
        int playersPerTick = Math.clamp(
                getConfig().getInt("progression.energy-regen.players-per-tick", 2), 1, 32);
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        Set<UUID> queued = new java.util.HashSet<>();
        Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
        getServer().getScheduler().runTaskTimer(this, () -> getServer().getOnlinePlayers().forEach(player -> {
            UUID playerId = player.getUniqueId();
            // A cached profile is the suite-wide proof that the authenticated
            // lifecycle has completed. Never regenerate limbo accounts.
            if (profiles.cached(playerId).isPresent()
                    && !inFlight.contains(playerId) && queued.add(playerId)) queue.addLast(playerId);
        }), intervalSeconds * 20L, intervalSeconds * 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (int index = 0; index < playersPerTick; index++) {
                UUID playerId = queue.pollFirst();
                if (playerId == null) break;
                queued.remove(playerId);
                if (getServer().getPlayer(playerId) == null || !inFlight.add(playerId)) continue;
                profiles.restoreEnergy(playerId, amount).whenComplete((profile, error) -> {
                    inFlight.remove(playerId);
                    if (error != null) getLogger().warning(
                            "Energy regeneration failed for " + playerId + ": " + error.getMessage());
                });
            }
        }, intervalSeconds * 20L, 1L);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (nodeLeaseExecutor != null) {
            // Stop periodic renewal before releasing the token. Otherwise a
            // final heartbeat could race the clean release during shutdown.
            nodeLeaseExecutor.close();
        }
        if (nodeLease != null) {
            try {
                if (!nodeLease.release()) {
                    getLogger().warning("Paper node lease was already absent or replaced during shutdown.");
                }
            } catch (RuntimeException exception) {
                getLogger().warning("Could not release Paper node lease; it will expire automatically: "
                        + exception.getMessage());
            }
        }
        if (databaseExecutor != null) {
            databaseExecutor.close();
        }
        if (database != null) {
            database.close();
        }
    }

    private static String environmentOrConfig(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
