package cn.originalrpg.core;

import cn.originalrpg.api.model.ChapterDefinition;
import cn.originalrpg.api.service.EconomyService;
import cn.originalrpg.api.service.ActivityEconomyService;
import cn.originalrpg.api.service.EconomyAnalyticsService;
import cn.originalrpg.api.service.CommerceService;
import cn.originalrpg.api.service.CurrencyExchangeService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.PlayerSessionService;
import cn.originalrpg.api.service.FieldJournalService;
import cn.originalrpg.api.service.PersistenceMetricsService;
import cn.originalrpg.api.service.MarketBuyOrderService;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.ProgressionAnomalyService;
import cn.originalrpg.api.service.SupportCreditService;
import cn.originalrpg.api.service.SkillService;
import cn.originalrpg.api.service.MerchantService;
import cn.originalrpg.api.service.PetProgressService;
import cn.originalrpg.api.service.PetRevivalService;
import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.DungeonEntryService;
import cn.originalrpg.api.service.DungeonRewardService;
import cn.originalrpg.api.service.CommunityGovernanceService;
import cn.originalrpg.api.service.CommunityRoleAssignmentService;
import cn.originalrpg.api.service.CommunityService;
import cn.originalrpg.api.service.CommunityLifecycleService;
import cn.originalrpg.api.service.CommunityStorageService;
import cn.originalrpg.api.service.CivilizationHistoryService;
import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.CommissionEvidenceService;
import cn.originalrpg.api.service.SeasonExpeditionService;
import cn.originalrpg.api.service.SeasonMeritWalletService;
import cn.originalrpg.api.service.SeasonSettlementService;
import cn.originalrpg.api.service.SeasonAttunementService;
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
import cn.originalrpg.core.persistence.JdbcMarketBuyOrderService;
import cn.originalrpg.core.persistence.JdbcMarketService;
import cn.originalrpg.core.persistence.JdbcProgressionAnomalyService;
import cn.originalrpg.core.persistence.JdbcSupportCreditService;
import cn.originalrpg.core.persistence.JdbcSkillService;
import cn.originalrpg.core.persistence.JdbcMerchantService;
import cn.originalrpg.core.persistence.JdbcPetProgressService;
import cn.originalrpg.core.persistence.JdbcPetRevivalService;
import cn.originalrpg.core.persistence.JdbcClassBranchService;
import cn.originalrpg.core.persistence.JdbcDungeonEntryService;
import cn.originalrpg.core.persistence.JdbcDungeonRewardService;
import cn.originalrpg.core.persistence.JdbcCommunityService;
import cn.originalrpg.core.persistence.JdbcCommunityLifecycleService;
import cn.originalrpg.core.persistence.JdbcCommunityStorageService;
import cn.originalrpg.core.persistence.JdbcCivilizationHistoryService;
import cn.originalrpg.core.persistence.CommunityLifecycleConfiguration;
import cn.originalrpg.core.persistence.JdbcChapterService;
import cn.originalrpg.core.persistence.JdbcCommissionEvidenceService;
import cn.originalrpg.core.persistence.JdbcSeasonExpeditionService;
import cn.originalrpg.core.persistence.JdbcSeasonMeritWalletService;
import cn.originalrpg.core.persistence.JdbcSeasonSettlementService;
import cn.originalrpg.core.persistence.JdbcSeasonAttunementService;
import cn.originalrpg.core.persistence.JdbcNodeLease;
import cn.originalrpg.core.persistence.JdbcOutboxRegistry;
import cn.originalrpg.core.persistence.JdbcSessionLease;
import cn.originalrpg.core.persistence.JdbcFieldJournalService;
import cn.originalrpg.core.persistence.SessionGuardedService;
import cn.originalrpg.core.persistence.OutboxDispatcher;
import cn.originalrpg.core.persistence.JdbcDungeonCompletionInbox;
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
import org.bukkit.plugin.Plugin;
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
import cn.originalrpg.api.service.MechanismControlService;
import cn.originalrpg.core.persistence.JdbcMechanismControlService;

public final class RpgCorePlugin extends JavaPlugin {
    private Database database;
    private cn.originalrpg.core.persistence.MechanismRegistry mechanisms;
    private ThreadPoolExecutor databaseExecutor;
    private ScheduledExecutorService nodeLeaseExecutor;
    private JdbcNodeLease nodeLease;
    private JdbcOutboxRegistry outbox;
    private OutboxDispatcher outboxDispatcher;
    private JdbcDungeonCompletionInbox completionInbox;
    private DungeonCompletionNotificationListener completionNotifications;
    private JdbcSessionLease sessionLease;
    private SessionLeaseListener sessionLeaseListener;
    private NodeLeaseSupervisor nodeLeaseSupervisor;
    private AutoCloseable nodeLeaseRenewal;
    private final AtomicBoolean outboxDispatchInFlight = new AtomicBoolean();
    private final AtomicBoolean chapterRefreshInFlight = new AtomicBoolean();
    private final AtomicBoolean mechanismRefreshInFlight = new AtomicBoolean();
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

            // These opt-in persistence mechanisms do not provide inventory or
            // player.dat transport. Full multi-Paper routing remains a separate gate.
            boolean outboxEnabled = getConfig().getBoolean("outbox.enabled", false);
            if (outboxEnabled) {
                outbox = new JdbcOutboxRegistry();
            }
            boolean outboxDispatchEnabled = getConfig().getBoolean("outbox.dispatch.enabled", false);
            if (outboxEnabled && outboxDispatchEnabled) {
                completionInbox = new JdbcDungeonCompletionInbox(database.dataSource(), databaseExecutor);
                outboxDispatcher = new OutboxDispatcher(database.dataSource(), List.of(completionInbox));
            }
            boolean sessionLeaseEnabled = getConfig().getBoolean("session-lease.enabled", false);
            if (sessionLeaseEnabled) {
                sessionLease = new JdbcSessionLease(
                        database.dataSource(), databaseExecutor, nodeLease.nodeId(), nodeLease.processToken(),
                        Duration.ofSeconds(Math.clamp(getConfig().getLong("session-lease.duration-seconds", 60L),
                                15L, 300L)));
                database.installSessionFencing();
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
                    database.dataSource(), databaseExecutor, startingEnergy, energyPerStamina,
                    chapters::maximumOpenAscension);
            var fieldJournal = new JdbcFieldJournalService(database.dataSource(), databaseExecutor);
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
            // Phase 4 买单簿：与卖单共用同一套托管账簿（C-009），因此同受市场熔断约束。
            JdbcMarketBuyOrderService buyOrders = new JdbcMarketBuyOrderService(
                    database.dataSource(), databaseExecutor, startingBalance, mechanisms);
            // Phase 1 成长异常清单：写入口 + 人工复核出口（[H] 门禁）。
            JdbcProgressionAnomalyService progressionAnomalies =
                    new JdbcProgressionAnomalyService(database.dataSource(), databaseExecutor);
            // Phase 5 support_credit 独立账本（C-012 物理隔离）。
            JdbcSupportCreditService supportCredit =
                    new JdbcSupportCreditService(database.dataSource(), databaseExecutor,
                            cn.originalrpg.core.persistence.SupportCreditPolicy.from(getConfig()), mechanisms);
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
                    database.dataSource(), databaseExecutor, profiles, mechanisms, outbox);
            JdbcSeasonExpeditionService seasonExpeditions = new JdbcSeasonExpeditionService(
                    database.dataSource(), databaseExecutor,
                    Math.clamp(getConfig().getInt("season-expedition.weekly-honor", 100), 0, 1_000),
                    Math.clamp(getConfig().getInt("season-expedition.server-first-bonus", 50), 0, 1_000),
                    mechanisms);
            String activeSeasonId = getConfig().getString("chapters.season-id", "launch-1");
            JdbcSeasonMeritWalletService seasonMerits = new JdbcSeasonMeritWalletService(
                    database.dataSource(), databaseExecutor, activeSeasonId);
            // W6-A2：C-008 第五条退出路径（赛季调谐）的触发者是换季结算。
            // L-009 默认关：关闭时连服务都不装配，结算的调谐步骤一次查询都不做。
            boolean seasonAttunementEnabled = getConfig()
                    .getBoolean("season-attunement.enabled", false);
            JdbcSeasonAttunementService seasonAttunements = seasonAttunementEnabled
                    ? new JdbcSeasonAttunementService(database.dataSource(), databaseExecutor, mechanisms)
                    : null;
            JdbcSeasonSettlementService seasonSettlements = new JdbcSeasonSettlementService(
                    database.dataSource(), databaseExecutor, mechanisms, seasonAttunements);
            if (seasonAttunements != null) {
                registerService(
                        SeasonAttunementService.class, seasonAttunements, this, ServicePriority.Normal);
            }
            // Phase 6 治理「接线但不启用」：开关默认关，命令与事件消费者仍在位。
            boolean communityGovernanceEnabled = getConfig()
                    .getBoolean("community.governance-enabled", false);
            JdbcCommunityService communities = new JdbcCommunityService(
                    database.dataSource(), databaseExecutor, mechanisms, communityGovernanceEnabled,
                    event -> Bukkit.getPluginManager().callEvent(event));
            var communityLifecycle = new JdbcCommunityLifecycleService(database.dataSource(), databaseExecutor,
                    communityGovernanceEnabled, CommunityLifecycleConfiguration.load(getConfig()));
            var communityStorage = new JdbcCommunityStorageService(database.dataSource(), databaseExecutor,
                    communityGovernanceEnabled && getConfig().getBoolean("community.storage.enabled", false),
                    getConfig().getInt("community.storage.maximum-stacks", 128), mechanisms);
            var recoveredEntries = StartupGate.await(
                    dungeonEntries.recoverAll(), startupTimeout, "Dungeon reservation recovery gate");
            if (recoveredEntries.reservations() > 0) {
                getLogger().warning("Recovered " + recoveredEntries.reservations()
                        + " unfinished dungeon energy reservations (" + recoveredEntries.energy() + " energy restored).");
            }
            registerService(ProfileService.class, profiles, this, ServicePriority.Normal);
            registerService(FieldJournalService.class, fieldJournal, this, ServicePriority.Normal);
            registerService(EconomyService.class, economy, this, ServicePriority.Normal);
            registerService(ActivityEconomyService.class, activityEconomy, this, ServicePriority.Normal);
            registerService(
                    CommissionEvidenceService.class, commissionEvidence, this, ServicePriority.Normal);
            registerService(EconomyAnalyticsService.class, economyAnalytics, this, ServicePriority.Normal);
            registerService(CommerceService.class, commerce, this, ServicePriority.Normal);
            registerService(CurrencyExchangeService.class, currencyExchange,
                    this, ServicePriority.Normal);
            registerService(MessageService.class, messages, this, ServicePriority.Normal);
            registerService(MarketService.class, market, this, ServicePriority.Normal);
            registerService(
                    MarketBuyOrderService.class, buyOrders, this, ServicePriority.Normal);
            registerService(
                    ProgressionAnomalyService.class, progressionAnomalies, this, ServicePriority.Normal);
            registerService(
                    SupportCreditService.class, supportCredit, this, ServicePriority.Normal);
            registerService(
                    EquipmentOperationService.class, equipmentOperations, this, ServicePriority.Normal);
            registerService(
                    EquipmentLedgerAuditService.class, equipmentLedger, this, ServicePriority.Normal);
            registerService(SkillService.class, skills, this, ServicePriority.Normal);
            registerService(
                    AdminTestService.class, adminTests, this, ServicePriority.Normal);
            registerService(
                    AdminIncidentCompensationService.class, incidentCompensations, this, ServicePriority.Normal);
            registerService(MerchantService.class, merchant, this, ServicePriority.Normal);
            registerService(
                    PetProgressService.class, petProgress, this, ServicePriority.Normal);
            registerService(
                    PetRevivalService.class, petRevivals, this, ServicePriority.Normal);
            registerService(
                    ClassBranchService.class, classBranches, this, ServicePriority.Normal);
            registerService(
                    AscensionService.class, ascensions, this, ServicePriority.Normal);
            registerService(
                    HiddenPathService.class, hiddenPaths, this, ServicePriority.Normal);
            registerService(
                    DungeonEntryService.class, dungeonEntries, this, ServicePriority.Normal);
            registerService(
                    DungeonRewardService.class, dungeonRewards, this, ServicePriority.Normal);
            registerService(
                    SeasonExpeditionService.class, seasonExpeditions, this, ServicePriority.Normal);
            registerService(
                    SeasonMeritWalletService.class, seasonMerits, this, ServicePriority.Normal);
            registerService(
                    SeasonSettlementService.class, seasonSettlements, this, ServicePriority.Normal);
            registerService(
                    CommunityService.class, communities, this, ServicePriority.Normal);
            registerService(CommunityLifecycleService.class, communityLifecycle, this, ServicePriority.Normal);
            registerService(CommunityStorageService.class, communityStorage, this, ServicePriority.Normal);
            registerService(CivilizationHistoryService.class,
                    new JdbcCivilizationHistoryService(database.dataSource(), databaseExecutor),
                    this, ServicePriority.Normal);
            registerService(
                    CommunityGovernanceService.class, communities, this, ServicePriority.Normal);
            registerService(
                    CommunityRoleAssignmentService.class, communities, this, ServicePriority.Normal);
            registerService(
                    ChapterService.class, chapters, this, ServicePriority.Normal);
            PersistenceMetricsService persistenceMetrics = () -> database.metrics(databaseExecutor);
            registerService(PersistenceMetricsService.class, persistenceMetrics,
                    this, ServicePriority.Normal);
            var mechanismControl = new JdbcMechanismControlService(mechanisms, databaseExecutor);
            registerService(MechanismControlService.class, mechanismControl, this, ServicePriority.Normal);

            RpgCommand command = new RpgCommand(
                    this, profiles, activityEconomy, chapters, messages, seasonMerits, seasonSettlements,
                    supportCredit);
            PluginCommand rpg = Objects.requireNonNull(getCommand("rpg"), "rpg command");
            rpg.setExecutor(command);
            LanguageCommand languageCommand = new LanguageCommand(messages);
            PluginCommand language = Objects.requireNonNull(getCommand("language"), "language command");
            language.setExecutor(languageCommand);
            language.setTabCompleter(languageCommand);
            getServer().getPluginManager().registerEvents(command, this);
            startEnergyRegeneration(profiles);
            startChapterRefresh(chapters);
            startMechanismRefresh(mechanismControl);
            startNodeLeaseHeartbeat(nodeLeaseSeconds);
            startReservationRecovery(dungeonEntries);
            if (outboxEnabled && outboxDispatchEnabled) {
                completionNotifications = new DungeonCompletionNotificationListener(this, completionInbox,
                        profiles, messages);
                getServer().getPluginManager().registerEvents(completionNotifications, this);
                completionNotifications.start();
                startOutboxDispatch();
            }
            if (sessionLeaseEnabled) {
                registerService(PlayerSessionService.class, sessionLease, this, ServicePriority.Normal);
                sessionLeaseListener = new SessionLeaseListener(this, sessionLease, messages,
                        Duration.ofMillis(getConfig().getLong("session-lease.admission-timeout-ms", 5_000L)));
                getServer().getPluginManager().registerEvents(sessionLeaseListener, this);
                sessionLeaseListener.start();
            }
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

    private void startMechanismRefresh(MechanismControlService control) {
        // Submit onto the bounded gameplay executor; never occupy the tick thread
        // or the dedicated node-lease executor with registry JDBC reads.
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!mechanismRefreshInFlight.compareAndSet(false, true)) return;
            control.switches().whenComplete((state, error) -> {
                mechanismRefreshInFlight.set(false);
                if (error != null) getLogger().warning("Mechanism switch refresh failed; stale admission will close: "
                        + error.getClass().getSimpleName());
            });
        }, 40L, 40L);
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

    /**
     * 节点租约续期的真实消费者（C-014）：由 {@link NodeLeaseSupervisor} 调度
     * {@code renewAsync()}，失租时失败关闭。这里只做接线，不启用多服。
     */
    private void startNodeLeaseHeartbeat(long leaseSeconds) {
        long heartbeatSeconds = Math.max(5L, leaseSeconds / 3L);
        nodeLeaseSupervisor = new NodeLeaseSupervisor(
                nodeLease, Duration.ofSeconds(heartbeatSeconds), getLogger(),
                (reason, error) -> {
                    if (error != null) getLogger().log(Level.SEVERE, reason, error);
                    requestServerShutdown(reason);
                });
        // 刚取得的 token 必须立刻被数据库承认，否则说明启动窗口内已被围栏。
        nodeLeaseSupervisor.verifyAcquiredToken();
        try {
            nodeLeaseRenewal = nodeLeaseSupervisor.schedule(nodeLeaseExecutor);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Could not schedule the Paper node lease heartbeat", error);
        }
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

    /**
     * Outbox 后台投递轮询（G-102）。默认关闭；仅在 {@code outbox.dispatch.enabled=true}
     * 时启动。单实例下不启动，对现有玩法零影响。
     */
    private void startOutboxDispatch() {
        long intervalSeconds = Math.clamp(
                getConfig().getLong("outbox.dispatch-interval-seconds", 5L), 1L, 60L);
        int batchSize = Math.clamp(getConfig().getInt("outbox.dispatch-batch-size", 32), 1, 512);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (!outboxDispatchInFlight.compareAndSet(false, true)) return;
            try {
                outboxDispatcher.dispatchBatch(batchSize);
            } catch (RuntimeException error) {
                getLogger().warning("Outbox dispatch round failed closed: " + error.getMessage());
            } finally {
                outboxDispatchInFlight.set(false);
            }
        }, intervalSeconds * 20L, intervalSeconds * 20L);
    }

    /** Transactional Outbox 写入口；业务服务用它在自身事务里登记事件。 */
    public JdbcOutboxRegistry outbox() {
        return outbox;
    }

    /** Session Lease 读侧判定入口；关闭时返回 null（调用方须判空）。 */
    public JdbcSessionLease sessionLease() {
        return sessionLease;
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
        if (sessionLeaseListener != null) sessionLeaseListener.close();
        if (completionNotifications != null) completionNotifications.close();
        if (nodeLeaseRenewal != null) {
            // 先停止周期续期，否则最后一次心跳可能和干净释放竞争。
            try {
                nodeLeaseRenewal.close();
            } catch (Exception exception) {
                getLogger().warning("Could not stop the Paper node lease heartbeat: " + exception.getMessage());
            }
        }
        if (nodeLeaseExecutor != null) {
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

    private <T> void registerService(Class<T> type, T provider, Plugin owner, ServicePriority priority) {
        getServer().getServicesManager().register(type,
                SessionGuardedService.wrap(type, provider, sessionLease), owner, priority);
    }
}
