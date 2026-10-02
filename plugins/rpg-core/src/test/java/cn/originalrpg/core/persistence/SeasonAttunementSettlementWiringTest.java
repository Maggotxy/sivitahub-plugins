package cn.originalrpg.core.persistence;

import cn.originalrpg.api.rules.EquipmentLifecycle;
import cn.originalrpg.api.rules.SeasonAttunementRules;
import cn.originalrpg.api.service.SeasonAttunementService;
import cn.originalrpg.api.service.SeasonSettlementService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W6-A2 接线合同：C-008 第五条退出路径（赛季调谐）必须
 * <ol>
 *   <li>有真实的类型级触发者 —— 换季结算的构造器接受 {@link SeasonAttunementService}；</li>
 *   <li>真的在结算事务里被调用（用计入数的假服务断言调用次数，不是读源码）；</li>
 *   <li>默认关闭（L-009）：配置解析出来就是 false；</li>
 *   <li>状态机之外没有第二条路径产出 SEASON_ATTUNED。</li>
 * </ol>
 */
final class SeasonAttunementSettlementWiringTest {
    private static final String SEASON = "launch-1";
    private static final String NEXT = "launch-2";

    private HikariDataSource dataSource;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:season_attune_wire_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_progression_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, merit BIGINT NOT NULL DEFAULT 0,
                        season_id VARCHAR(32), updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_merit_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        season_merit BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_merit_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, amount BIGINT NOT NULL,
                        direction VARCHAR(8) NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlements (
                        id VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL,
                        preview_hash VARCHAR(64), fact_digest VARCHAR(64), approved_by VARCHAR(64),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_gate (
                        singleton_id INTEGER PRIMARY KEY, settlement_id VARCHAR(36),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_transitions (
                        id VARCHAR(36) PRIMARY KEY, settlement_id VARCHAR(36) NOT NULL,
                        from_state VARCHAR(24) NOT NULL, to_state VARCHAR(24) NOT NULL,
                        actor VARCHAR(64) NOT NULL, reason VARCHAR(128) NOT NULL,
                        fact_digest VARCHAR(64), request_hash VARCHAR(64) NOT NULL,
                        transaction_version BIGINT NOT NULL, committed_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_progress (
                        settlement_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        merit_debited BIGINT NOT NULL, committed_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (settlement_id, player_uuid))
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_player_progress (
                        season_id VARCHAR(32) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        honor BIGINT NOT NULL DEFAULT 0, collection_stamps INTEGER NOT NULL DEFAULT 0,
                        completed_runs INTEGER NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (season_id, player_uuid))
                    """);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void settlementConsumesTheAttunementServiceThroughACompileTimeDependency() {
        boolean accepts = false;
        for (Constructor<?> constructor : JdbcSeasonSettlementService.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (parameter == SeasonAttunementService.class) accepts = true;
            }
        }
        assertTrue(accepts,
                "C-008 违规：换季结算与赛季调谐之间没有编译期依赖，调谐永远不会有触发者");
        assertTrue(SeasonAttunementRules.TRIGGER.equals("SeasonSettlementService"),
                "文档依据：调谐的触发者是换季结算，不是玩家指令");
    }

    @Test
    void settlementReallyInvokesAttunementOnce() throws Exception {
        UUID player = UUID.randomUUID();
        seedPlayer(player);
        CountingAttunement attunements = new CountingAttunement();
        SeasonSettlementService service = new JdbcSeasonSettlementService(
                dataSource, executor, mechanisms(true), attunements);

        // DRAFT -> ... -> SETTLING 才会触发资金与调谐动作。
        for (String target : List.of("previewed", "approved", "freezing", "settling")) {
            var result = service.advance(SEASON, NEXT, cn.originalrpg.api.rules.SeasonSettlementState.valueOf(
                            target.toUpperCase(java.util.Locale.ROOT)), "console", "criterion accepted")
                    .toCompletableFuture().join();
            assertEquals(cn.originalrpg.api.model.SeasonSettlementResult.Status.APPLIED,
                    result.status(), "state " + target);
        }
        assertTrue(attunements.batchCalls.get() >= 1, "结算必须在 SETTLING 时调用赛季调谐");
        assertTrue(attunements.seasonIds.contains(SEASON), "调谐收口的是被结算的旧赛季："
                + attunements.seasonIds);
        assertEquals(java.util.Set.of(player), new java.util.LinkedHashSet<>(attunements.owners),
                "只调谐被结算玩家的身份");
    }

    @Test
    void withoutTheServiceTheSettlementBehavesExactlyAsBefore() throws Exception {
        // 未接线（或开关关）时结算零影响：不装配调谐服务时结算照旧完成，且没有任何
        // 调谐表访问入口（本测试的 schema 里连 rpg_season_attunements 都没有建）。
        UUID player = UUID.randomUUID();
        seedPlayer(player);
        SeasonSettlementService service = new JdbcSeasonSettlementService(
                dataSource, executor, mechanisms(true), null);
        for (String target : List.of("previewed", "approved", "freezing", "settling")) {
            var result = service.advance(SEASON, NEXT, cn.originalrpg.api.rules.SeasonSettlementState.valueOf(
                            target.toUpperCase(java.util.Locale.ROOT)), "console", "criterion accepted")
                    .toCompletableFuture().join();
            assertEquals(cn.originalrpg.api.model.SeasonSettlementResult.Status.APPLIED,
                    result.status(), "state " + target);
        }
        assertEquals(1L, count("rpg_season_settlement_progress"));
    }

    @Test
    void killSwitchOffMeansTheSettlementNeverReachesAttunement() throws Exception {
        UUID player = UUID.randomUUID();
        seedPlayer(player);
        CountingAttunement attunements = new CountingAttunement();
        SeasonSettlementService service = new JdbcSeasonSettlementService(
                dataSource, executor, mechanisms(false), attunements);

        var result = service.advance(SEASON, NEXT,
                        cn.originalrpg.api.rules.SeasonSettlementState.SETTLING, "console", "criterion accepted")
                .toCompletableFuture().join();
        assertEquals(cn.originalrpg.api.model.SeasonSettlementResult.Status.BLOCKED, result.status(),
                "Kill Switch 关闭时换季必须 fail closed");
        assertEquals(0, attunements.batchCalls.get(), "被熔断的结算不得触发任何调谐");
        assertEquals(0L, count("rpg_season_settlements"));
    }

    @Test
    void theLifecycleIsTheOnlyProducerOfSeasonAttuned() throws Exception {
        // 状态机自己是唯一权威：迁移表里有 SEASON_ATTUNED 的目标位，
        // 但没有任何玩家操作 kind 会产出它（产出者是换季结算）。
        assertTrue(EquipmentLifecycle.isValidTransition(
                EquipmentLifecycle.State.BOUND, EquipmentLifecycle.State.SEASON_ATTUNED));
        assertTrue(EquipmentLifecycle.isExitPath(EquipmentLifecycle.State.SEASON_ATTUNED));
        assertFalse(EquipmentLifecycle.isTerminal(EquipmentLifecycle.State.SEASON_ATTUNED),
                "调谐是退出路径但不是终态：装备还能被交还、分解或隔离");
        for (cn.originalrpg.api.model.EquipmentOperationKind kind
                : cn.originalrpg.api.model.EquipmentOperationKind.values()) {
            assertFalse(EquipmentLifecycle.afterOperation(kind, EquipmentLifecycle.State.BOUND)
                            == EquipmentLifecycle.State.SEASON_ATTUNED,
                    kind + " 不得经玩家操作产出 SEASON_ATTUNED");
        }
        assertNull(EquipmentLifecycle.seedForOperation(
                cn.originalrpg.api.model.EquipmentOperationKind.SEASON_ATTUNEMENT));
        // 调谐的消费者在 rpg-core（状态机之外没有第二套状态机）。
        assertTrue(productionReferences("attune").stream()
                        .anyMatch(path -> path.contains("EquipmentStateLedger.java")),
                "状态机必须有真实消费者：" + productionReferences("attune"));
    }

    @Test
    void seasonAttunementShipsDisabledByDefaultAndHasNoPlayerCommand() throws Exception {
        Map<?, ?> config = yaml(repositoryRoot().resolve("plugins/rpg-core/src/main/resources/config.yml"));
        Object section = config.get("season-attunement");
        assertTrue(section instanceof Map<?, ?>, "rpg-core/config.yml 必须含 season-attunement 段");
        assertEquals(Boolean.FALSE, ((Map<?, ?>) section).get("enabled"),
                "赛季调谐必须默认关闭（L-009：能力接线但不启用）");

        // 没有玩家指令：装备插件与核心插件的 plugin.yml 都不得注册调谐命令。
        for (String module : List.of("rpg-items", "rpg-core")) {
            String pluginYml = Files.readString(repositoryRoot().resolve(
                    "plugins/" + module + "/src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
            String lowered = pluginYml.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lowered.contains("attune"),
                    module + "/plugin.yml 不得为赛季调谐注册玩家指令（触发者是换季结算）");
        }
        // 指令分发链里也不得有调谐分支。
        String core = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-core/src/main/java/cn/originalrpg/core/command/RpgCommand.java"), StandardCharsets.UTF_8);
        assertFalse(core.contains("ttun"), "玩家命令分发链里不得有调谐子命令");
    }

    @Test
    void mechanismRegistrationIsUntouchedByThisBridging() throws Exception {
        // 调谐复用既有 Kill Switch（season.settlement.enabled），不新增机制 id：
        // 新增机制会让 C-005 的清单与 DEFAULTS/FAUCET_ENTRY_POINTS 三处必须同步。
        Field defaults = MechanismRegistry.class.getDeclaredField("DEFAULTS");
        defaults.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> values = (Map<String, Boolean>) defaults.get(null);
        assertFalse(values.keySet().stream().anyMatch(key -> key.contains("attun")),
                "不得为调谐新增机制 id（复用 season.settlement.enabled）");
    }

    private MechanismRegistry mechanisms(boolean enabled) throws Exception {
        MechanismRegistry mechanisms = new MechanismRegistry(dataSource);
        Field cache = MechanismRegistry.class.getDeclaredField("cache");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> values = (Map<String, Boolean>) cache.get(mechanisms);
        values.put(MechanismRegistry.SEASON_SETTLEMENT, enabled);
        return mechanisms;
    }

    private void seedPlayer(UUID player) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit, season_id)"
                    + " VALUES ('" + player + "', 100, '" + SEASON + "')");
            statement.execute("INSERT INTO rpg_season_merit_wallets (player_uuid, season_id, season_merit)"
                    + " VALUES ('" + player + "', '" + SEASON + "', 40)");
            connection.commit();
        }
    }

    private long count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Map<?, ?> yaml(Path path) throws Exception {
        org.yaml.snakeyaml.LoaderOptions options = new org.yaml.snakeyaml.LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = Files.newInputStream(path)) {
            return (Map<?, ?>) new org.yaml.snakeyaml.Yaml(
                    new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(input);
        }
    }

    private static List<String> productionReferences(String identifier) throws Exception {
        List<String> hits = new ArrayList<>();
        try (var files = Files.walk(repositoryRoot().resolve("plugins/rpg-core/src/main"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains(identifier)) {
                    hits.add(file.getFileName().toString());
                }
            }
        }
        Collections.sort(hits);
        return hits;
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }

    /** 只记调用次数与参数：断言的是「结算有没有真的把调谐动作转发出去」。 */
    private static final class CountingAttunement implements SeasonAttunementService {
        private final AtomicInteger batchCalls = new AtomicInteger();
        private final List<String> seasonIds = new ArrayList<>();
        private final List<UUID> owners = new ArrayList<>();

        @Override
        public java.util.concurrent.CompletionStage<cn.originalrpg.api.model.SeasonAttunementRecord> attune(
                String seasonId, UUID itemId, UUID ownerId, String idempotencyKey) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new cn.originalrpg.api.model.SeasonAttunementRecord(seasonId, itemId, ownerId, "REFUSED"));
        }

        @Override
        public java.util.concurrent.CompletionStage<List<cn.originalrpg.api.model.SeasonAttunementRecord>>
        attuneForSettlement(String seasonId, UUID ownerId, String idempotencyKey) {
            batchCalls.incrementAndGet();
            seasonIds.add(seasonId);
            owners.add(ownerId);
            return java.util.concurrent.CompletableFuture.completedFuture(List.of());
        }
    }
}
