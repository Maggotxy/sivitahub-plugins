package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.rules.ProgressionRules;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcAdminTestServiceTest {
    private final Executor direct = Runnable::run;
    private JdbcDataSource dataSource;
    private JdbcProfileService profiles;
    private JdbcSkillService skills;
    private JdbcAdminTestService tests;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admin-test-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE rpg_profiles (
                        player_uuid VARCHAR(36) PRIMARY KEY, archetype VARCHAR(24) NOT NULL,
                        ascension INTEGER NOT NULL, level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        energy INTEGER NOT NULL, unspent_attribute_points INTEGER NOT NULL,
                        skill_points INTEGER NOT NULL, defense INTEGER NOT NULL, strength INTEGER NOT NULL,
                        agility INTEGER NOT NULL, intelligence INTEGER NOT NULL, stamina INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_skills (
                        player_uuid VARCHAR(36) NOT NULL, skill_id VARCHAR(64) NOT NULL,
                        level INTEGER NOT NULL, point_cost INTEGER NOT NULL, variant VARCHAR(64),
                        PRIMARY KEY (player_uuid, skill_id))
                    """);
            sql.execute("""
                    CREATE TABLE rpg_admin_test_profiles (
                        player_uuid VARCHAR(36) PRIMARY KEY, archetype VARCHAR(24) NOT NULL,
                        ascension INTEGER NOT NULL, level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        energy INTEGER NOT NULL, unspent_attribute_points INTEGER NOT NULL,
                        skill_points INTEGER NOT NULL, defense INTEGER NOT NULL, strength INTEGER NOT NULL,
                        agility INTEGER NOT NULL, intelligence INTEGER NOT NULL, stamina INTEGER NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_admin_test_skills (
                        player_uuid VARCHAR(36) NOT NULL, skill_id VARCHAR(64) NOT NULL,
                        level INTEGER NOT NULL, point_cost INTEGER NOT NULL, variant VARCHAR(64),
                        PRIMARY KEY (player_uuid, skill_id))
                    """);
            connection.commit();
        }
        profiles = new JdbcProfileService(dataSource, direct, 100, 2, 3);
        skills = new JdbcSkillService(dataSource, direct);
        tests = new JdbcAdminTestService(dataSource, direct, profiles, skills, 100, 2);
    }

    @AfterEach
    void close() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("SHUTDOWN");
        }
    }

    @Test
    void maxClassSwitchAndRestoreRoundTripWithoutLeakingTestProgress() {
        UUID player = UUID.randomUUID();
        profiles.get(player).toCompletableFuture().join();
        profiles.chooseArchetype(player, Archetype.MAGE).toCompletableFuture().join();
        skills.learn(player, "fireball", 1).toCompletableFuture().join();

        var active = tests.activate(player, Archetype.WARRIOR,
                Map.of("fireball", 5, "war-cry", 5), 64, 10_000).toCompletableFuture().join();
        assertTrue(active.active());
        assertEquals(Archetype.WARRIOR, active.profile().archetype());
        assertEquals(ProgressionRules.LOCAL_LEVEL_CAP, active.profile().level());
        assertEquals(120, active.profile().effectiveLevel());
        assertEquals(64, active.profile().attribute(CoreAttribute.STRENGTH));
        assertEquals(2, active.learnedSkills());

        var switched = tests.switchArchetype(player, Archetype.HEALER).toCompletableFuture().join();
        assertEquals(Archetype.HEALER, switched.profile().archetype());

        // Re-activation must retain the very first snapshot rather than
        // snapshotting already-maxed test data.
        tests.activate(player, Archetype.RANGER, Map.of("power-shot", 5), 64, 10_000)
                .toCompletableFuture().join();
        var restored = tests.restore(player).toCompletableFuture().join();
        assertFalse(restored.active());
        assertEquals(Archetype.MAGE, restored.profile().archetype());
        assertEquals(1, restored.profile().level());
        assertEquals(1, restored.learnedSkills());
        assertTrue(skills.skill(player, "fireball").toCompletableFuture().join().isPresent());
        assertTrue(skills.skill(player, "power-shot").toCompletableFuture().join().isEmpty());
    }
}
