package cn.originalrpg.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-005 门禁：注册表里登记了机制却没有任何调用点，视为 Kill Switch 是死开关。
 *
 * <p>本测试用反射读取 {@link MechanismRegistry} 的机制常量，再用反射逐个检查
 * {@code FAUCET_ENTRY_POINTS} 指向的消费者类里是否真的有「接 Kill Switch」的构造器
 * （字节码层面），最后用真库（H2）验证关闭开关后资金动作 fail closed。</p>
 *
 * <p>行为化替换说明（G-302）：原实现 {@code Files.readString} 读 rpg-core/src/main 的
 * 全部源码再 {@code contains("requireEnabled(MechanismRegistry.X)")}，这是字符串匹配，
 * 与「机制能不能被关停」无关。现在改为：消费者的构造器必须接受
 * {@link MechanismRegistry}（编译期接线），且 {@code JdbcSeasonSettlementService}
 * 关闭机制后必须拒绝迁移且不落库（运行期行为）。</p>
 */
final class MechanismKillSwitchContractTest {

    @Test
    void everyRegisteredMechanismHasAWiredConsumerClass() throws Exception {
        Map<String, String> mechanisms = registeredMechanismValues();
        assertFalse(mechanisms.isEmpty(), "Mechanism registry must declare at least one mechanism");

        Map<String, String> dead = new TreeMap<>();
        for (Map.Entry<String, String> entry : mechanisms.entrySet()) {
            String consumer = MechanismRegistry.FAUCET_ENTRY_POINTS.get(entry.getValue());
            if (consumer == null || !consumerAcceptsTheKillSwitch(consumer)) {
                dead.put(entry.getKey() + "=" + entry.getValue(),
                        "consumer " + consumer + " does not accept a MechanismRegistry");
            }
        }
        assertTrue(dead.isEmpty(),
                "C-005 违规：以下机制已注册但其消费者没接 Kill Switch（开关关不掉任何路径）: " + dead);
    }

    /**
     * 反射检查 {@code FAUCET_ENTRY_POINTS} 指向的类是否在构造期就拿到 Kill Switch。
     * 只看「类能否加载 + 构造器是否接受 MechanismRegistry」，不看源码文本：
     * 消费者一旦被换成不接开关的实现，这里立即失败。
     */
    private static boolean consumerAcceptsTheKillSwitch(String simpleName) {
        Class<?> consumer;
        try {
            consumer = Class.forName("cn.originalrpg.core.persistence." + simpleName);
        } catch (ClassNotFoundException notInThisPackage) {
            try {
                consumer = Class.forName(simpleName);
            } catch (ClassNotFoundException missing) {
                return false;
            }
        }
        for (Constructor<?> constructor : consumer.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (parameter == MechanismRegistry.class) return true;
            }
        }
        return false;
    }

    @Test
    void everyMechanismIsMappedToADeclaredFaucetEntryPoint() throws Exception {
        Map<String, String> mechanisms = registeredMechanismValues();
        for (Map.Entry<String, String> entry : mechanisms.entrySet()) {
            assertTrue(MechanismRegistry.FAUCET_ENTRY_POINTS.containsKey(entry.getValue()),
                    "机制缺少 FAUCET_ENTRY_POINTS 映射: " + entry.getValue());
        }
        assertEquals(mechanisms.size(), MechanismRegistry.FAUCET_ENTRY_POINTS.size(),
                "FAUCET_ENTRY_POINTS 与机制常量数量不一致");
    }

    @Test
    void emptyRegistryFailsClosedAndStillReportsMetrics() throws Exception {
        // 空缓存等价于「从未播种/未登记」：C-005 要求 fail closed。
        MechanismRegistry registry = new MechanismRegistry(null);
        assertFalse(registry.requireEnabled(MechanismRegistry.HUNT_GOLD));
        MechanismRegistry.MechanismMetric metric = registry.metrics().get(MechanismRegistry.HUNT_GOLD);
        assertNotNull(metric, "指标必须覆盖每个已注册机制");
        assertFalse(metric.enabled());
        assertEquals(1L, metric.denied());
        assertEquals(0L, metric.allowed());
        assertEquals(registeredMechanismValues().values().stream().distinct().count(),
                registry.mechanismIds().size(), "指标/清单必须覆盖全部机制常量");
    }

    /** 反射读取全部 public static final String 机制常量名 -> 值，值形如 {@code domain.scope.flag}。 */
    private static Map<String, String> registeredMechanismValues() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (Field field : MechanismRegistry.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
                    || field.getType() != String.class) continue;
            String value = (String) field.get(null);
            if (value != null && value.matches("[a-z0-9_.]+")) {
                result.put(field.getName(), value);
            }
        }
        return result;
    }

    private HikariDataSource dataSource;
    private java.util.concurrent.ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:killswitch_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(SETTLEMENTS_DDL);
            statement.execute(TRANSITIONS_DDL);
            statement.execute(GATE_DDL);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void disabledSeasonSettlementMechanismFailsClosedOnRealDatabase() throws Exception {
        // 运行期行为断言：开关关掉后换季迁移必须被拒绝，且不落任何状态行。
        MechanismRegistry registry = new MechanismRegistry(dataSource);
        Field cache = MechanismRegistry.class.getDeclaredField("cache");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> cacheValues = (Map<String, Boolean>) cache.get(registry);
        cacheValues.put(MechanismRegistry.SEASON_SETTLEMENT, false);

        JdbcSeasonSettlementService settlement =
                new JdbcSeasonSettlementService(dataSource, executor, registry);
        var result = settlement.advance("launch-1", null,
                cn.originalrpg.api.rules.SeasonSettlementState.PREVIEWED, "console",
                "kill switch closed").toCompletableFuture().join();

        assertEquals(cn.originalrpg.api.model.SeasonSettlementResult.Status.BLOCKED, result.status(),
                "关闭机制后必须返回 BLOCKED 而不是照常结算");
        assertEquals(cn.originalrpg.api.rules.SeasonSettlementState.DRAFT,
                settlement.state("launch-1").toCompletableFuture().join(),
                "被拒绝的迁移不得推进状态");
        // 拒绝必须计入指标：advance 自身已经问过一次开关，requireEnabled 再问一次即为 2。
        assertFalse(registry.requireEnabled(MechanismRegistry.SEASON_SETTLEMENT));
        assertEquals(2L, registry.metrics().get(MechanismRegistry.SEASON_SETTLEMENT).denied(),
                "拒绝必须逐次计入指标（advance 一次 + 本次一次）");
        assertEquals(0L, registry.metrics().get(MechanismRegistry.SEASON_SETTLEMENT).allowed(),
                "被关停的机制不得有任何 allowed 计数");
    }

    private static final String SETTLEMENTS_DDL = "CREATE TABLE rpg_season_settlements ("
            + " id VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,"
            + " status VARCHAR(24) NOT NULL, preview_hash VARCHAR(64), fact_digest VARCHAR(64),"
            + " approved_by VARCHAR(64),"
            + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)";

    private static final String TRANSITIONS_DDL = "CREATE TABLE rpg_season_settlement_transitions ("
            + " id VARCHAR(36) PRIMARY KEY, settlement_id VARCHAR(36) NOT NULL,"
            + " from_state VARCHAR(24) NOT NULL, to_state VARCHAR(24) NOT NULL,"
            + " actor VARCHAR(64) NOT NULL, reason VARCHAR(128) NOT NULL,"
            + " fact_digest VARCHAR(64), request_hash VARCHAR(64) NOT NULL,"
            + " transaction_version BIGINT NOT NULL, committed_at TIMESTAMP NULL,"
            + " created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)";

    private static final String GATE_DDL = "CREATE TABLE rpg_season_settlement_gate ("
            + " singleton_id INTEGER PRIMARY KEY, settlement_id VARCHAR(36),"
            + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)";
}
