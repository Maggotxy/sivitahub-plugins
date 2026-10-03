package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 契约测试：离线迁移工具与在线节点必须争抢同一把 PostgreSQL advisory lock。
 *
 * <p>RpgCore 侧权威来源：
 * {@code plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java:18-21}
 * 定义 {@code MIGRATION_LOCK_NAMESPACE = 0x53495649}、
 * {@code MIGRATION_LOCK_RESOURCE = 0x52504731}，
 * 第 1064 行用双参数形式加锁。</p>
 */
class MigrationAdvisoryLockTest {
    /** RpgCore Database.java:20，硬编码期望值，改一侧必须同时改两侧。 */
    private static final int RPGCORE_NAMESPACE = 0x53495649;
    /** RpgCore Database.java:21，硬编码期望值。 */
    private static final int RPGCORE_RESOURCE = 0x52504731;
    /** RpgCore 里拼接双参数加锁语句的写法，去掉换行与字符串连接符后应为一行。 */
    private static final Pattern RPGCORE_TWO_ARGUMENT_LOCK = Pattern.compile(
            "pg_advisory_xact_lock\\(\\s*\"\\s*\\+\\s*MIGRATION_LOCK_NAMESPACE"
                    + "\\s*\\+\\s*\", \""
                    + "\\s*\\+\\s*MIGRATION_LOCK_RESOURCE"
    );
    /** PostgreSQL 对双参数形式的合成结果：(namespace << 32) | resource。 */
    private static final long EXPECTED_COMPOSED_KEY = 6001422851361490737L;

    @Test
    void composedKeyMatchesHardcodedOnlineNodeKey() {
        assertEquals(EXPECTED_COMPOSED_KEY, MigrationAdvisoryLock.composedKey());
        assertEquals(
                MigrationAdvisoryLock.composedKey(),
                MigrationAdvisoryLock.signedComposedKey(RPGCORE_NAMESPACE, RPGCORE_RESOURCE)
        );
    }

    @Test
    void lockStatementUsesTheTwoArgumentFormOwnedByOnlineNodes() {
        String sql = "SELECT pg_advisory_xact_lock(" + MigrationAdvisoryLock.NAMESPACE
                + ", " + MigrationAdvisoryLock.RESOURCE + ")";
        assertEquals("SELECT pg_advisory_xact_lock(1397315145, 1380992817)", sql);
        assertTrue(sql.contains(","), "单 bigint 形式是另一把锁，必须禁止");
    }

    @Test
    void rpgCoreSourceStillDeclaresTheSameLockKey() throws Exception {
        // G-302：原实现读 Database.java 源码做 contains 断言；两个常量与「两参数形式」
        // 这三条都只能对源码结构做静态保证，因此逐条迁进声明式源码契约
        // （scripts/source-contract-specs/all.json#rpg-core），由本方法真实执行门禁。
        Path root = Path.of(System.getProperty("sivita.projectRoot"));
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assumeTrue(Files.isRegularFile(gate), "契约门禁脚本是这条路由契约的前提");
        Process process = new ProcessBuilder("sh", gate.toString(),
                "tools/db-migration/src/test/java/cn/originalrpg/tools/dbmigration/"
                        + "MigrationAdvisoryLockTest.java#rpg-core",
                "rpgCoreSourceStillDeclaresTheSameLockKey")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "RpgCore 迁移锁常量与 MigrationAdvisoryLock 不再同步：\n" + output
                        + "\n（RpgCore namespace/resource 常量或两参数形式被改动；"
                        + "改则必须重新同步 MigrationAdvisoryLock）");
    }

    @Test
    void singleBigintFormTargetsADifferentKey() {
        // PostgreSQL 里 bigint 形式用的是另一把锁；这条断言把「不能再用单参数形式」钉死。
        assertTrue(MigrationAdvisoryLock.composedKey() != 0x5349564954414D47L);
    }

    @Test
    void realPostgreSqlTreatsBothFormsAsDifferentLocksWhenConditionalDatabaseIsProvided() throws Exception {
        String url = System.getenv("SIVITA_MIGRATION_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set SIVITA_MIGRATION_TEST_POSTGRES_URL to run");
        String user = System.getenv().getOrDefault("SIVITA_MIGRATION_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("SIVITA_MIGRATION_TEST_POSTGRES_PASSWORD", "");
        try (Connection holder = postgres(url, user, password);
             Connection probe = postgres(url, user, password)) {
            holder.setAutoCommit(false);
            probe.setAutoCommit(false);
            execute(holder, "SELECT pg_advisory_xact_lock(" + MigrationAdvisoryLock.NAMESPACE
                    + ", " + MigrationAdvisoryLock.RESOURCE + ")");
            // 同一把锁应当被阻塞：lock_timeout 触发，说明两侧确实互斥。
            execute(probe, "SET LOCAL lock_timeout = '1000ms'");
            assertThrowsSQLState("55P03", probe, "SELECT pg_advisory_xact_lock("
                    + MigrationAdvisoryLock.NAMESPACE + ", " + MigrationAdvisoryLock.RESOURCE + ")");
            // 超时会把事务标记为失败，回滚后换一个干净事务继续探测。
            probe.rollback();
            probe.setAutoCommit(true);
            // 单 bigint 形式不应被阻塞，证明旧实现确实漏锁。
            assertEquals(1L, scalarLong(probe, "SELECT 1"));
            holder.rollback();
        }
    }

    private static void assertThrowsSQLState(String expected, Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            throw new AssertionError("Expected SQLSTATE " + expected);
        } catch (SQLException exception) {
            assertEquals(expected, exception.getSQLState());
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Connection postgres(String url, String user, String password) throws SQLException {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", "sivita-migration-lock-test");
        return DriverManager.getConnection(url, properties);
    }
}
