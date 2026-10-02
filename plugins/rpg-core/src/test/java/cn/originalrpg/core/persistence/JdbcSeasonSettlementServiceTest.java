package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SeasonSettlementPreview;
import cn.originalrpg.api.model.SeasonSettlementResult;
import cn.originalrpg.api.rules.SeasonSettlementState;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-010 换季事务的行为级测试。判据是「真的执行行为并断言结果」：
 * 每个用例都真的推进状态机、真的动账本、真的在故障后重跑。
 */
final class JdbcSeasonSettlementServiceTest {
    private static final String SEASON = "launch-1";
    private static final String NEXT_SEASON = "launch-2";
    private static final String ACTOR = "console";
    private static final String REASON = "criterion accepted";
    /** 每条新增的 DDL 都必须真的被这些 DDL 使用方消费，这里只建换季事务需要的表。 */
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcSeasonSettlementService service;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:season_le_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_progression_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, merit BIGINT NOT NULL DEFAULT 0,
                        season_id VARCHAR(32),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
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
                        id VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        status VARCHAR(24) NOT NULL, preview_hash VARCHAR(64), fact_digest VARCHAR(64),
                        approved_by VARCHAR(64),
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
        service = new JdbcSeasonSettlementService(dataSource, executor, null);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void legalChainRunsOneStepAtATimeAndSettlesSeasonalMerit() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);

        assertEquals(SeasonSettlementState.DRAFT, state());
        applied(SeasonSettlementState.PREVIEWED);
        applied(SeasonSettlementState.APPROVED);
        applied(SeasonSettlementState.FREEZING);
        // 结算：旧赛季赛季层结清进账本，钱包赛季层归零并换上新赛季标记。
        applied(SeasonSettlementState.SETTLING, NEXT_SEASON);
        applied(SeasonSettlementState.VERIFYING);
        applied(SeasonSettlementState.COMPLETED);

        assertEquals(SeasonSettlementState.COMPLETED, state());
        assertEquals(0L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        assertEquals(NEXT_SEASON, text("SELECT season_id FROM rpg_season_merit_wallets"));
        // 永久层不受换季影响。
        assertEquals(500L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(300L, number("SELECT COALESCE(SUM(amount), 0) FROM rpg_merit_ledger"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_merit_ledger WHERE idempotency_key = 'season-settlement:"
                + NEXT_SEASON + ":" + player + "'"));
        // 清扫把本次结算的进度行全部标成已提交。
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_season_settlement_progress"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_season_settlement_progress WHERE committed_at IS NOT NULL"));
    }

    @Test
    void illegalTransitionsAreRejectedAndWriteNothing() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 200, SEASON, 100);

        // 跳态：DRAFT 不能直接 APPROVED，也不能直接结算，也不能倒着走。
        assertRejected(SeasonSettlementState.APPROVED, SeasonSettlementResult.Status.ILLEGAL_TRANSITION);
        assertRejected(SeasonSettlementState.SETTLING, SeasonSettlementResult.Status.ILLEGAL_TRANSITION);
        assertRejected(SeasonSettlementState.ROLLED_BACK, SeasonSettlementResult.Status.ILLEGAL_TRANSITION);
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_season_settlements"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_season_settlement_transitions"));
        assertEquals(100L, number("SELECT season_merit FROM rpg_season_merit_wallets"));

        applied(SeasonSettlementState.PREVIEWED);
        // 只有相邻一格可走：PREVIEWED 不能跳到 SETTLING。
        assertRejected(SeasonSettlementState.SETTLING, SeasonSettlementResult.Status.ILLEGAL_TRANSITION);
        // 终态封闭：COMPLETED 之后不能再迁移。
        applied(SeasonSettlementState.APPROVED);
        applied(SeasonSettlementState.FREEZING);
        applied(SeasonSettlementState.SETTLING, NEXT_SEASON);
        applied(SeasonSettlementState.VERIFYING);
        applied(SeasonSettlementState.COMPLETED);
        // 终态封闭：COMPLETED 连 FAILED 都进不去，更不可能回滚。
        assertRejected(SeasonSettlementState.FAILED, SeasonSettlementResult.Status.ILLEGAL_TRANSITION);
        assertFalse(SeasonSettlementState.COMPLETED.canTransitionTo(SeasonSettlementState.FAILED));
    }

    @Test
    void midSettlementCrashLeavesNoPartialPayoutAndRetryDoesNotDoubleGrant() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        wallet(first, 500, SEASON, 300);
        wallet(second, 500, SEASON, 200);
        applied(SeasonSettlementState.PREVIEWED);
        applied(SeasonSettlementState.APPROVED);
        applied(SeasonSettlementState.FREEZING);

        // 故障注入：第 1 条赛季层归零之后立刻抛异常（等价于「资金写到一半、原子提交前进程挂掉」）。
        JdbcSeasonSettlementService failing = new JdbcSeasonSettlementService(
                crashingDataSource(1), Runnable::run, null);
        assertThrows(RuntimeException.class, () -> failing.advance(
                SEASON, NEXT_SEASON, SeasonSettlementState.SETTLING, ACTOR, REASON)
                .toCompletableFuture().join());

        // 崩溃后：整个事务回滚，没有半截发放，钱包与账本都保持原样，状态仍是 FREEZING。
        assertEquals(500L, number("SELECT COALESCE(SUM(season_merit), 0) FROM rpg_season_merit_wallets"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(SeasonSettlementState.FREEZING, state());

        // 重启后重跑同一步：正常提交，每条只结算一次，没有重复发放。
        assertEquals(SeasonSettlementResult.Status.APPLIED, service.advance(
                SEASON, NEXT_SEASON, SeasonSettlementState.SETTLING, ACTOR, REASON)
                .toCompletableFuture().join().status());
        assertEquals(2L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(500L, number("SELECT COALESCE(SUM(amount), 0) FROM rpg_merit_ledger"));
        assertEquals(0L, number("SELECT COALESCE(SUM(season_merit), 0) FROM rpg_season_merit_wallets"));
        assertEquals(2L, number("SELECT COUNT(*) FROM rpg_season_settlement_progress"));

        // 已经推进过的那一步再执行：请求指纹不匹配既有日志 -> 拒绝，不会二次归零。
        SeasonSettlementResult replay = service.advance(
                SEASON, NEXT_SEASON, SeasonSettlementState.SETTLING, ACTOR, REASON)
                .toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.UNCHANGED, replay.status());
        assertEquals(500L, number("SELECT COALESCE(SUM(amount), 0) FROM rpg_merit_ledger"));
        assertEquals(2L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));

        // 清扫把进度行标成已提交，再次执行的资金动作没有目标行可发。
        assertEquals(SeasonSettlementResult.Status.APPLIED, service.advance(
                SEASON, SeasonSettlementState.VERIFYING, ACTOR, REASON).toCompletableFuture().join().status());
        assertEquals(SeasonSettlementResult.Status.APPLIED, service.advance(
                SEASON, SeasonSettlementState.COMPLETED, ACTOR, REASON).toCompletableFuture().join().status());
        assertEquals(2L, number("SELECT COUNT(*) FROM rpg_season_settlement_progress WHERE committed_at IS NOT NULL"));
        assertEquals(500L, number("SELECT COALESCE(SUM(amount), 0) FROM rpg_merit_ledger"));
    }

    @Test
    void settlementFactsChangedAfterApprovalFailsClosedInsteadOfSettling() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);
        applied(SeasonSettlementState.PREVIEWED);
        applied(SeasonSettlementState.APPROVED);
        applied(SeasonSettlementState.FREEZING);

        // 预览之后资金又变了：结算必须先失败，而不是照旧执行。
        try (Connection connection = dataSource.getConnection(); PreparedStatement update =
                     connection.prepareStatement("UPDATE rpg_season_merit_wallets SET season_merit = 999")) {
            update.executeUpdate();
            connection.commit();
        }
        SeasonSettlementResult blocked = service.advance(
                SEASON, NEXT_SEASON, SeasonSettlementState.SETTLING, ACTOR, REASON)
                .toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.BLOCKED, blocked.status());
        assertEquals(SeasonSettlementState.FAILED, blocked.state());
        assertEquals(999L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
    }

    @Test
    void replayOfTheSameTransitionIsDuplicateAndNeverSettlesTwice() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);
        SeasonSettlementResult preview = service.advance(
                SEASON, SeasonSettlementState.PREVIEWED, ACTOR, REASON).toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.APPLIED, preview.status());

        // 同指纹重放：返回 DUPLICATE 而不是再走一次。
        SeasonSettlementResult replay = service.advance(
                SEASON, SeasonSettlementState.PREVIEWED, ACTOR, REASON).toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.UNCHANGED, replay.status());
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_season_settlements"));
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_season_settlement_transitions"));
    }

    @Test
    void invalidRequestAndSeasonIdAreRefusedBeforeAnyDatabaseWork() {
        assertThrows(IllegalArgumentException.class, () -> service.state("Launch 1"));
        SeasonSettlementResult tooShort = service.advance(
                SEASON, SeasonSettlementState.PREVIEWED, ACTOR, "x").toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.INVALID, tooShort.status());
        SeasonSettlementResult noActor = service.advance(
                SEASON, SeasonSettlementState.PREVIEWED, "", REASON).toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.INVALID, noActor.status());
    }

    @Test
    void killSwitchRefusesSettlementWithoutTouchingTheLedger() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);
        // 真的关掉开关（走注册表自己的持久化路径），再看换季是否被熔断。
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_mechanism_switches (
                        mechanism_id VARCHAR(64) PRIMARY KEY, enabled BOOLEAN NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_by VARCHAR(64) NOT NULL)
                    """);
            statement.execute("INSERT INTO rpg_mechanism_switches (mechanism_id, enabled, updated_by) "
                    + "VALUES ('" + MechanismRegistry.SEASON_SETTLEMENT + "', FALSE, 'test')");
            connection.commit();
        }
        MechanismRegistry mechanisms = new MechanismRegistry(dataSource);
        mechanisms.initialize();
        JdbcSeasonSettlementService blocked = new JdbcSeasonSettlementService(dataSource, Runnable::run, mechanisms);

        SeasonSettlementResult result = blocked.advance(
                SEASON, SeasonSettlementState.PREVIEWED, ACTOR, REASON).toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.BLOCKED, result.status());
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_season_settlements"));
        assertEquals(300L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_season_settlement_transitions"));
    }

    @Test
    void settlingSettlesTheWholeBacklogOfEveryOldSeasonNotJustTheRetiredOne() throws Exception {
        // 多个旧赛季的积压必须一次结清，否则换季会把中间赛季的功勋变成无主余额。
        // 钱包一玩家一行，因此积压用三个不同玩家表达：两个陈年旧赛季 + 一个被收口的赛季。
        UUID staleA = UUID.randomUUID();
        UUID staleB = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        wallet(staleA, 0, "2000-01-01", 120);
        wallet(staleB, 0, "2000-02-01", 80);
        wallet(current, 700, SEASON, 50);

        SeasonSettlementPreview preview = service.preview(SEASON).toCompletableFuture().join();
        assertEquals(250L, preview.seasonMerit());
        assertEquals(700L, preview.retainedMerit());
        assertEquals(64, preview.digest().length());
        assertEquals(3L, preview.players());
        assertEquals(3L, preview.settledPlayers());

        applied(SeasonSettlementState.PREVIEWED);
        applied(SeasonSettlementState.APPROVED);
        applied(SeasonSettlementState.FREEZING);
        applied(SeasonSettlementState.SETTLING, NEXT_SEASON);

        assertEquals(0L, number("SELECT COALESCE(SUM(season_merit), 0) FROM rpg_season_merit_wallets"));
        // 全部钱包都被打到新赛季；三个旧赛季各写一条账本行，互不覆盖。
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_season_merit_wallets WHERE season_id <> '" + NEXT_SEASON + "'"));
        assertEquals(3L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(250L, number("SELECT COALESCE(SUM(amount), 0) FROM rpg_merit_ledger"));
        // 清扫把全部三条进度标成已提交。
        assertEquals(3L, number("SELECT COUNT(*) FROM rpg_season_settlement_progress"));
        assertEquals(3L, number("SELECT COUNT(*) FROM rpg_season_settlement_progress"
                + " WHERE committed_at IS NOT NULL"));
        assertEquals(700L, number("SELECT merit FROM rpg_progression_wallets WHERE player_uuid = '"
                + current + "'"));
    }

    @Test
    void settlingToTheSameSeasonIsRefusedSoMeritIsNeverWipedWithoutARotation() throws Exception {
        UUID player = UUID.randomUUID();
        wallet(player, 500, SEASON, 300);
        applied(SeasonSettlementState.PREVIEWED);
        applied(SeasonSettlementState.APPROVED);
        applied(SeasonSettlementState.FREEZING);

        // 目标赛季 == 当前赛季（在仓里就是「只改 season-id 假装换季」）必须被拒绝。
        SeasonSettlementResult result = service.advance(
                SEASON, SEASON, SeasonSettlementState.SETTLING, ACTOR, REASON).toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.INVALID, result.status());
        assertEquals(300L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        // 缺少目标赛季同理：不允许「无目标结算」把赛季层清零。
        SeasonSettlementResult missing = service.advance(
                SEASON, SeasonSettlementState.SETTLING, ACTOR, REASON).toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.INVALID, missing.status());
        assertEquals(300L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
    }

    private void seasons(UUID playerId, String seasonId, long amount) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement insert =
                     connection.prepareStatement("""
                             INSERT INTO rpg_season_merit_wallets (player_uuid, season_id, season_merit)
                             VALUES (?, ?, ?)
                             """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, seasonId);
            insert.setLong(3, amount);
            insert.executeUpdate();
            connection.commit();
        }
    }

    private SeasonSettlementState state() {
        return service.state(SEASON).toCompletableFuture().join();
    }

    private void applied(SeasonSettlementState target) {
        applied(target, null);
    }

    private void applied(SeasonSettlementState target, String nextSeasonId) {
        SeasonSettlementResult result = service.advance(SEASON, nextSeasonId, target, ACTOR, REASON)
                .toCompletableFuture().join();
        assertEquals(SeasonSettlementResult.Status.APPLIED, result.status(), "expected " + target);
        assertEquals(target, result.state());
    }

    private void assertRejected(SeasonSettlementState target, SeasonSettlementResult.Status expected) {
        SeasonSettlementResult result = service.advance(SEASON, target, ACTOR, REASON).toCompletableFuture().join();
        assertEquals(expected, result.status(), "expected rejection for " + target);
    }

    private void wallet(UUID playerId, long permanent, String seasonId, long seasonal) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO rpg_progression_wallets (player_uuid, merit, season_id) VALUES (?, ?, ?)
                     """)) {
            insert.setString(1, playerId.toString());
            insert.setLong(2, permanent);
            insert.setString(3, seasonId);
            insert.executeUpdate();
            connection.commit();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO rpg_season_merit_wallets (player_uuid, season_id, season_merit) VALUES (?, ?, ?)
                     """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, seasonId);
            insert.setLong(3, seasonal);
            insert.executeUpdate();
            connection.commit();
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    /**
     * 在指定条数的赛季层归零语句之后注入故障，模拟「资金写到一半、原子提交前进程挂掉」。
     * 只劫持写语句，读路径与事务管理保持真实，因此回滚行为是真的。
     */
    private javax.sql.DataSource crashingDataSource(int failOnUpdate) {
        AtomicInteger updates = new AtomicInteger();
        return proxyDataSource(sql -> {
            if (sql.startsWith("UPDATE rpg_season_merit_wallets SET season_merit = 0")
                    && updates.incrementAndGet() > failOnUpdate) {
                throw new IllegalStateException("injected crash: atomic commit never happened");
            }
        });
    }

    /** 把 DataSource 包一层，只在写路径上做故障注入，事务语义保持原样。 */
    private javax.sql.DataSource proxyDataSource(Consumer<String> beforeWrite) {
        javax.sql.DataSource real = dataSource;
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (proxy, method, args) -> {
                    Object value = method.invoke(real, args);
                    if (value instanceof Connection connection) {
                        return wrapConnection(connection, beforeWrite);
                    }
                    return value;
                });
    }

    private Connection wrapConnection(Connection real, Consumer<String> beforeWrite) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("prepareStatement") && args != null && args.length > 0
                            && args[0] instanceof String sql) {
                        Object statement = invokeUnwrapped(real, method, args);
                        return wrapStatement((java.sql.PreparedStatement) statement, sql, beforeWrite);
                    }
                    return invokeUnwrapped(real, method, args);
                });
    }

    private java.sql.PreparedStatement wrapStatement(
            java.sql.PreparedStatement real, String sql, Consumer<String> beforeWrite
    ) {
        String normalized = sql.replaceAll("\\s+", " ").strip();
        return (java.sql.PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("executeUpdate")) {
                        beforeWrite.accept(normalized);
                    }
                    return invokeUnwrapped(real, method, args);
                });
    }

    private static Object invokeUnwrapped(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
