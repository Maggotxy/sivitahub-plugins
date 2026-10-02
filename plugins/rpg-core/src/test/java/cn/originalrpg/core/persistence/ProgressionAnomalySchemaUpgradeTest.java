package cn.originalrpg.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [H] 复核列在**存量库**上升级到位的证据（任务要求 ④）。
 *
 * <p>失败模式：新库由 {@code CREATE TABLE} 直接带上 {@code reviewer}/{@code review_note}/
 * {@code reviewed_at} 三列，而真实节点上的旧库早已经建过表 —— {@code CREATE TABLE IF NOT EXISTS}
 * 不会补列。没有显式 {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}，
 * 升级后 {@code /rpgadmin anomaly ack} 会以「列不存在」回滚。</p>
 *
 * <p>本测试因此**先造一张 Phase 1 形状的旧表**（没有复核三列），再跑真实的升级语句
 * （从 {@code Database.java} 里抽出来的三条 {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS}），
 * 断言三列真的被补上、旧行不被破坏，并且升级语句可重放。</p>
 */
final class ProgressionAnomalySchemaUpgradeTest {
    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:anomaly_upgrade_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setDriverClassName("org.h2.Driver");
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void legacyTableWithoutReviewColumnsIsUpgradedByTheRealMigrationStatements() throws Exception {
        String ddl = readSource("Database.java");
        // 只取本表相关的迁移语句：全库 migrate() 的顺序问题（CREATE INDEX 早于它自己的
        // CREATE TABLE）在真机 PostgreSQL 上是既有事实，不属于本任务范围，此处不复现。
        List<String> statements = new ArrayList<>();
        for (String line : ddl.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("ALTER TABLE rpg_progression_anomalies ADD COLUMN IF NOT EXISTS")) {
                statements.add(trimmed.replaceAll("^\"|\",?$", "").replace("\"", ""));
            }
        }
        assertEquals(3, statements.size(),
                "必须为 reviewer / review_note / reviewed_at 各登记一条升级语句：" + statements);
        assertTrue(statements.stream().allMatch(sql -> sql.contains("IF NOT EXISTS")),
                "升级语句必须幂等（IF NOT EXISTS），重放不报错：" + statements);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            // 旧形状：Phase 1 建表时的六列，没有 [H] 复核三列。
            statement.execute("""
                    CREATE TABLE rpg_progression_anomalies (
                        player_uuid VARCHAR(36) PRIMARY KEY, ascension INTEGER NOT NULL,
                        level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        reason VARCHAR(128) NOT NULL,
                        detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("INSERT INTO rpg_progression_anomalies "
                    + "(player_uuid, ascension, level, experience, reason) "
                    + "VALUES ('00000000-0000-0000-0000-000000000001', 1, 31, 0, 'local-level-out-of-range:31')");
            connection.commit();
        }
        assertEquals(List.of("player_uuid", "ascension", "level", "experience", "reason", "detected_at"),
                columns());

        // 跑真实的升级语句，并跑两遍（幂等：第二次也必须成功）。
        for (int pass = 0; pass < 2; pass++) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (String sql : statements) statement.execute(sql);
                connection.commit();
            }
        }

        assertTrue(columns().containsAll(List.of("reviewer", "review_note", "reviewed_at")),
                "存量库必须被补上 [H] 复核三列，实际列：" + columns());
        // 旧行不被迁移破坏，且默认仍未复核。
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM rpg_progression_anomalies WHERE reviewed_at IS NULL")) {
            result.next();
            assertEquals(1L, result.getLong(1));
        }
    }

    @Test
    void migrationNeverAltersTheAnomalyTableBeforeCreatingIt() throws Exception {
        String ddl = readSource("Database.java");
        int create = ddl.indexOf("CREATE TABLE IF NOT EXISTS rpg_progression_anomalies");
        int alter = ddl.indexOf("ALTER TABLE rpg_progression_anomalies");
        assertTrue(create > 0, "DDL 里必须有 rpg_progression_anomalies 的建表语句");
        assertTrue(alter > create,
                "列追加必须排在 CREATE TABLE 之后，否则全新库上 migrate() 会以 Table not found 失败");
    }

    /** 从 H2 元数据读列名（大小写归一），用于断言「列真的存在」。 */
    private List<String> columns() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet columns = metadata.getColumns(null, null, "rpg_progression_anomalies", null)) {
                while (columns.next()) names.add(columns.getString("COLUMN_NAME").toLowerCase(java.util.Locale.ROOT));
            }
        }
        return names;
    }

    /** 工作目录随调用方（IDE vs Gradle）而异，因此从 CWD 向上找仓根。 */
    private static String readSource(String fileName) throws Exception {
        java.nio.file.Path current = java.nio.file.Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++) {
            java.nio.file.Path candidate = current.resolve(java.nio.file.Path.of(
                    "plugins", "rpg-core", "src", "main", "java",
                    "cn", "originalrpg", "core", "persistence", fileName));
            if (java.nio.file.Files.exists(candidate)) return java.nio.file.Files.readString(candidate);
            current = current.getParent();
        }
        throw new IllegalStateException("Database.java not found from " + java.nio.file.Path.of("").toAbsolutePath());
    }
}
