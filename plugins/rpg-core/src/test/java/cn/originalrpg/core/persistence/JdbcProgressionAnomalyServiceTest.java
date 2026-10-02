package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.ProgressionAnomaly;
import cn.originalrpg.api.model.ProgressionAnomalyResult;
import cn.originalrpg.api.rules.ProgressionRules;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 成长异常清单的行为级测试。
 *
 * <p>判据是「异常档案真的落进表、正常档案真的不落、[H] 复核出口真的能清掉」，
 * 并覆盖 {@link JdbcProfileService} 读路径自动落库这条真实调用链。</p>
 */
final class JdbcProgressionAnomalyServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcProgressionAnomalyService service;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:anomalies_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_progression_anomalies (
                        player_uuid VARCHAR(36) PRIMARY KEY, ascension INTEGER NOT NULL,
                        level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        reason VARCHAR(128) NOT NULL,
                        detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            // 读路径调用链需要的最小档案表。
            statement.execute("""
                    CREATE TABLE rpg_profiles (
                        player_uuid VARCHAR(36) PRIMARY KEY, archetype VARCHAR(24) NOT NULL,
                        ascension INTEGER NOT NULL, level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        energy INTEGER NOT NULL, unspent_attribute_points INTEGER NOT NULL,
                        skill_points INTEGER NOT NULL, defense INTEGER NOT NULL, strength INTEGER NOT NULL,
                        agility INTEGER NOT NULL, intelligence INTEGER NOT NULL, stamina INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_skill_point_grants (
                        milestone_key VARCHAR(160) PRIMARY KEY, receipt VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL, milestone_index INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_energy_recovery_quotas (
                        player_uuid VARCHAR(36) NOT NULL, source_id VARCHAR(64) NOT NULL,
                        period_key VARCHAR(32) NOT NULL, uses INTEGER NOT NULL,
                        energy_restored INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, source_id, period_key))
                    """);
            connection.commit();
        }
        service = new JdbcProgressionAnomalyService(dataSource, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void legacyLocalLevelAboveCapIsRecordedInsteadOfBeingGuessed() throws Exception {
        UUID player = UUID.randomUUID();

        // 老口径存档：1 转 level=31。禁止 level%30 猜测，必须落人工清单。
        ProgressionAnomalyResult result = await(service.inspect(player, 1, 31, 0));

        assertEquals(ProgressionAnomalyResult.Status.RECORDED, result.status());
        assertEquals("local-level-out-of-range:31", result.anomaly().reason());
        assertEquals(1, countAnomalies());

        ProgressionAnomaly stored = await(service.pending(player)).orElseThrow();
        assertEquals(1, stored.ascension());
        assertEquals(31, stored.level());
        assertEquals("local-level-out-of-range:31", stored.reason());
    }

    @Test
    void ascensionAndNegativeExperienceOutOfRangeAreRecorded() throws Exception {
        UUID ascension = UUID.randomUUID();
        UUID negative = UUID.randomUUID();
        UUID jump = UUID.randomUUID();

        assertEquals("ascension-out-of-range:9",
                await(service.inspect(ascension, 9, 5, 0)).anomaly().reason());
        assertEquals("negative-experience:-1",
                await(service.inspect(negative, 0, 5, -1)).anomaly().reason());
        assertEquals(ProgressionAnomalyResult.Status.RECORDED,
                await(service.inspect(jump, 3, 30, 999_999_999L)).status());
        assertTrue(await(service.pending(jump)).orElseThrow().reason().startsWith("experience-jump:"));
        assertEquals(3, countAnomalies());
    }

    @Test
    void healthyProfileIsNeverRecorded() throws Exception {
        UUID player = UUID.randomUUID();

        ProgressionAnomalyResult result = await(service.inspect(player, 0, 12, 3_400));

        assertEquals(ProgressionAnomalyResult.Status.HEALTHY, result.status());
        assertEquals(0, countAnomalies(), "正常档案不得落异常行");
        assertTrue(await(service.pending(player)).isEmpty());
    }

    @Test
    void repeatedInspectionRefreshesTheSingleRowInsteadOfStacking() throws Exception {
        UUID player = UUID.randomUUID();

        assertEquals(ProgressionAnomalyResult.Status.RECORDED,
                await(service.inspect(player, 0, 31, 5)).status());
        assertEquals(ProgressionAnomalyResult.Status.UPDATED,
                await(service.inspect(player, 0, 40, 9)).status());

        assertEquals(1, countAnomalies(), "主键是 player_uuid：只保留一条最新快照");
        assertEquals(40, await(service.pending(player)).orElseThrow().level());
    }

    @Test
    void acknowledgeRequiresAReviewerAndActuallyClearsTheList() throws Exception {
        UUID player = UUID.randomUUID();
        await(service.inspect(player, 2, 31, 0));

        // 没有署名的复核不算复核（[H] 门禁）。
        assertFalse(await(service.acknowledge(player, "  ")));
        assertFalse(await(service.acknowledge(player, null)));
        assertEquals(1, countAnomalies(), "被拒的复核不得删行");

        assertTrue(await(service.acknowledge(player, "operator-zhang")));
        assertEquals(0, countAnomalies());
        assertTrue(await(service.pending(player)).isEmpty());
    }

    @Test
    void pendingListIsBoundedAndOrdered() throws Exception {
        for (int i = 0; i < 5; i++) {
            await(service.inspect(UUID.randomUUID(), 0, 31 + i, 0));
        }
        assertEquals(3, await(service.pending(3)).size());
        assertEquals(5, await(service.pending(100)).size());
    }

    @Test
    void profileReadPathRecordsAnomaliesForRealFromDatabaseRows() throws Exception {
        UUID player = UUID.randomUUID();
        insertProfile(player, 1, 31, 0);

        JdbcProfileService profiles = new JdbcProfileService(dataSource, executor, 100, 2);
        PlayerProfile loaded = await(profiles.get(player));

        assertEquals(31, loaded.level(), "读路径不得篡改原始档案");
        assertEquals(1, countAnomalies(), "读路径必须把不可解释的档案落进人工清单");
        assertEquals("local-level-out-of-range:31",
                await(service.pending(player)).orElseThrow().reason());
    }

    @Test
    void classifyAnomalyLeavesTheCapBoundaryHealthy() {
        assertEquals(null, ProgressionRules.classifyAnomaly(3, 30, 0));
        assertEquals(null, ProgressionRules.classifyAnomaly(0, 1, 0));
        assertEquals("local-level-out-of-range:0", ProgressionRules.classifyAnomaly(0, 0, 0));
    }

    private void insertProfile(UUID player, int ascension, int level, long experience) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO rpg_profiles (
                         player_uuid, archetype, ascension, level, experience, energy,
                         unspent_attribute_points, skill_points, defense, strength, agility,
                         intelligence, stamina
                     ) VALUES (?, 'UNCHOSEN', ?, ?, ?, 100, 5, 1, 0, 0, 0, 0, 0)
                     """)) {
            insert.setString(1, player.toString());
            insert.setInt(2, ascension);
            insert.setInt(3, level);
            insert.setLong(4, experience);
            insert.executeUpdate();
            connection.commit();
        }
    }

    private long countAnomalies() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM rpg_progression_anomalies")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
