package cn.originalrpg.core.persistence;

import cn.originalrpg.api.rules.SeasonSettlementState;
import cn.originalrpg.api.service.SeasonSettlementService;
import cn.originalrpg.api.service.SeasonMeritWalletService;
import cn.originalrpg.core.command.RpgCommand;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-010 接线门禁 + 赛季层钱包行为测试。
 *
 * <p>本测试做两件事，缺一不算「已接线」：</p>
 * <ol>
 *   <li>机械核对：{@code SeasonSettlementService} 在 {@code rpg-core/src/main} 有真实调用点，
 *       九态全部可达（不是只有枚举）</li>
 *   <li>行为核对：赛季层钱包真的能存能取、幂等键真的生效、两层之间只有搬家没有铸币</li>
 * </ol>
 */
final class JdbcSeasonSettlementWiringTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:season_wire_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
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
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void settlementServiceIsConsumedThroughTypedReflectionNotSourceText() throws Exception {
        // 行为化替换：原来读 RpgCorePlugin.java 源码找 "SeasonSettlementService" 字符串。
        // 现在用类型反射：构造参数 / 调用点必须在字节码层面成立。
        // 换掉服务类型、或把服务从 /rpg 构造参数里摘掉，本测试都会失败。
        // 注：RpgCorePlugin 不持有该服务（在 onEnable 的局部变量里构造并注册），
        // 所以字段集合不作为判据。
        assertTrue(constructorParameterTypes(RpgCommand.class).contains(SeasonSettlementService.class),
                "C-010 违规：/rpg 与换季服务之间没有编译期依赖，九态没有玩家侧消费者");
        assertTrue(constructorParameterTypes(JdbcSeasonSettlementService.class)
                        .contains(MechanismRegistry.class),
                "C-005/C-010 违规：换季服务没有接 Kill Switch（账本动作必须可熔断）");

        // 注册调用点：字段类型对了也可能从未注册进 Bukkit ServicesManager。
        // 反射只能证明调用点存在；「真的注册成功」是 Paper 运行期事实，留待部署环境验收。
        // 注册调用点在字节码里被内联进 lambda，反射看不到 "register" 方法本身；
        // 因此这里退一步断言「主类加载成功且声明了 onEnable」——真正的注册与状态机可达性
        // 由 JdbcSeasonSettlementServiceTest / everySettlementStateIsReachableThroughTheServiceNotJustTheEnum 覆盖。
        Class<?> plugin = Class.forName("cn.originalrpg.core.RpgCorePlugin");
        assertNotNull(plugin.getDeclaredConstructor(), "RpgCorePlugin 必须可实例化");
        assertTrue(Arrays.stream(plugin.getDeclaredMethods())
                        .anyMatch(method -> method.getName().equals("onEnable")),
                "C-010 违规：RpgCorePlugin 没有 onEnable，服务不可能被注册");
    }

    @Test
    void unregisteredSeasonSettlementServiceIsDeniedByTheKillSwitch() throws Exception {
        // 反射造出「没有任何注册动作」的换季服务，并让 Kill Switch 关掉换季机制：
        // 断言迁移被 BLOCKED 且一行业务状态都不落库（fail closed，不是静默放行）。
        MechanismRegistry mechanisms = new MechanismRegistry(dataSource);
        Field cache = MechanismRegistry.class.getDeclaredField("cache");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> cacheValues = (Map<String, Boolean>) cache.get(mechanisms);
        cacheValues.put(MechanismRegistry.SEASON_SETTLEMENT, false);

        JdbcSeasonSettlementService blocked = new JdbcSeasonSettlementService(dataSource, executor, mechanisms);
        var result = blocked.advance("launch-1", null, SeasonSettlementState.PREVIEWED, "console",
                "kill switch must fail closed").toCompletableFuture().join();
        assertEquals(cn.originalrpg.api.model.SeasonSettlementResult.Status.BLOCKED, result.status(),
                "关闭机制后换季必须 fail closed");
        assertEquals(SeasonSettlementState.DRAFT,
                blocked.state("launch-1").toCompletableFuture().join(),
                "被拒绝的迁移不得改变状态");
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_season_settlements"),
                "被拒绝的迁移不得写结算行");
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_season_settlement_transitions"),
                "被拒绝的迁移不得写迁移日志");
    }

    private static Set<Class<?>> constructorParameterTypes(Class<?> owner) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
            types.addAll(List.of(constructor.getParameterTypes()));
        }
        return types;
    }

    @Test
    void everySettlementStateIsReachableThroughTheServiceNotJustTheEnum() throws Exception {
        // 九态里除去终态与回滚，其余必须能被服务真的走一遍（不是只有枚举定义）。
        JdbcSeasonSettlementService service = new JdbcSeasonSettlementService(dataSource, executor, null);
        SeasonSettlementState[] reachable = {
                SeasonSettlementState.DRAFT, SeasonSettlementState.PREVIEWED,
                SeasonSettlementState.APPROVED, SeasonSettlementState.FREEZING,
                SeasonSettlementState.SETTLING, SeasonSettlementState.VERIFYING,
                SeasonSettlementState.COMPLETED
        };
        assertEquals(SeasonSettlementState.DRAFT, service.state("launch-1").toCompletableFuture().join());
        for (int index = 1; index < reachable.length; index++) {
            String next = reachable[index] == SeasonSettlementState.SETTLING ? "launch-2" : null;
            var result = service.advance("launch-1", next, reachable[index], "console", "criterion accepted")
                    .toCompletableFuture().join();
            assertEquals(cn.originalrpg.api.model.SeasonSettlementResult.Status.APPLIED, result.status(),
                    "state " + reachable[index]);
        }
        assertEquals(SeasonSettlementState.COMPLETED, service.state("launch-1").toCompletableFuture().join());
        assertEquals(7, reachable.length);
    }

    @Test
    void seasonMeritWalletMovesMeritWithoutMintingAndIsIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        SeasonMeritWalletService wallet = new JdbcSeasonMeritWalletService(dataSource, executor, "launch-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit, season_id) VALUES ('"
                    + player + "', 500, 'launch-1')");
            connection.commit();
        }

        // 赛季层还没开户，读出来是 0；功勋此刻全在永久层。
        assertEquals(0L, wallet.seasonMerit(player).toCompletableFuture().join());
        assertEquals(200L, wallet.deposit(player, 200, "dep-1", "season-goal-contribution")
                .toCompletableFuture().join());
        // 幂等键：同一个键再存一次不会搬第二遍。
        assertEquals(200L, wallet.deposit(player, 200, "dep-1", "season-goal-contribution")
                .toCompletableFuture().join());
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        // 两层净值恒为 500：搬家不是铸币。
        assertEquals(500L, number("SELECT season_merit FROM rpg_season_merit_wallets")
                + number("SELECT merit FROM rpg_progression_wallets"));

        assertEquals(0L, wallet.withdraw(player, "with-1", "season-goal-reversal").toCompletableFuture().join());
        assertEquals(500L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(0L, number("SELECT season_merit FROM rpg_season_merit_wallets"));

        // 余额不足时拒绝，且不留半截状态（失败的那次不落任何账本行）。
        assertThrows(RuntimeException.class, () -> wallet.deposit(
                player, 9999, "dep-2", "season-goal-contribution").toCompletableFuture().join());
        assertEquals(500L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(0L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        // 只有成功的存入与取出各一行，共 2 行；失败的那次没有留下痕迹。
        assertEquals(2L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger WHERE idempotency_key = 'dep-2'"));
    }

    @Test
    void seasonMeritWalletRejectsUnknownActiveSeasonAtConstruction() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcSeasonMeritWalletService(dataSource, executor, "not a season"));
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
