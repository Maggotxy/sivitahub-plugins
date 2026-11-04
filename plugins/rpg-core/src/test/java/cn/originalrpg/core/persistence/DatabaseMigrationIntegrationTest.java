package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.ProgressionRules;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts from the real migration, rather than hand-creating the shape expected by a service. */
final class DatabaseMigrationIntegrationTest {
    @Test
    void emptyDatabaseAndRestartSupportActualProgressionAndMechanismWrites() throws Exception {
        try (RuntimeDatabaseFixture fixture = new RuntimeDatabaseFixture()) {
            fixture.database.migrate();
            MechanismRegistry switches = new MechanismRegistry(fixture.dataSource);
            switches.initialize();
            switches.setEnabled(MechanismRegistry.HUNT_GOLD, false, "operator");
            UUID player = UUID.randomUUID();
            UUID grant = UUID.randomUUID();
            JdbcProfileService profiles = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            PlayerProfile awarded = profiles.grantExperienceOnce(player, grant, firstMilestoneXp()).toCompletableFuture().join();
            assertEquals(3, awarded.level());
            assertEquals(2, awarded.skillPoints());
            assertEquals(17L, awarded.experience());

            fixture.database.migrate();
            MechanismRegistry restarted = new MechanismRegistry(fixture.dataSource);
            restarted.initialize();
            assertEquals(false, restarted.isEnabled(MechanismRegistry.HUNT_GOLD));
            JdbcProfileService reloaded = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            assertEquals(awarded, reloaded.grantExperienceOnce(player, grant, firstMilestoneXp()).toCompletableFuture().join());
            assertEquals(1L, fixture.count("rpg_skill_point_grants"));
            assertEquals(1L, fixture.count("rpg_experience_grants"));
            assertEquals(0L, fixture.count("rpg_season_settlement_transitions"));
        }
    }

    @Test
    void emptyLegacyGrantTableIsRebuiltWithoutChangingExistingProfiles() throws Exception {
        try (RuntimeDatabaseFixture fixture = new RuntimeDatabaseFixture()) {
            fixture.database.migrate();
            UUID player = UUID.randomUUID();
            JdbcProfileService profiles = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            PlayerProfile before = profiles.get(player).toCompletableFuture().join();
            replaceWithLegacyTable(fixture);

            fixture.database.migrate();
            fixture.database.migrate();

            JdbcProfileService reloaded = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            assertEquals(before, reloaded.get(player).toCompletableFuture().join());
            PlayerProfile after = reloaded.grantExperience(player, firstMilestoneXp()).toCompletableFuture().join();
            assertEquals(3, after.level());
            assertEquals(2, after.skillPoints());
            assertEquals(1L, fixture.count("rpg_skill_point_grants"));
        }
    }

    @Test
    void populatedLegacyGrantTableStopsUpgradeAndPreservesEveryOriginalFact() throws Exception {
        try (RuntimeDatabaseFixture fixture = new RuntimeDatabaseFixture()) {
            fixture.database.migrate();
            UUID player = UUID.randomUUID();
            JdbcProfileService profiles = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            PlayerProfile before = profiles.get(player).toCompletableFuture().join();
            replaceWithLegacyTable(fixture);
            try (Connection connection = fixture.dataSource.getConnection();
                 var insert = connection.prepareStatement("""
                         INSERT INTO rpg_skill_point_grants
                         (source, idempotency_key, player_uuid, points, ascension, from_local_level, to_local_level)
                         VALUES ('historical-reward', 'old-range-receipt', ?, 2, 0, 1, 6)
                         """)) {
                insert.setString(1, player.toString());
                insert.executeUpdate();
                connection.commit();
            }

            IllegalStateException failure = assertThrows(IllegalStateException.class, fixture.database::migrate);

            assertTrue(failure.getCause().getMessage().contains("historical grants"));
            assertEquals(1L, fixture.count("rpg_skill_point_grants"));
            try (Connection connection = fixture.dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT * FROM rpg_skill_point_grants")) {
                assertTrue(result.next());
                assertEquals("historical-reward", result.getString("source"));
                assertEquals("old-range-receipt", result.getString("idempotency_key"));
                assertEquals(player.toString(), result.getString("player_uuid"));
                assertEquals(2, result.getInt("points"));
                assertEquals(1, result.getInt("from_local_level"));
                assertEquals(6, result.getInt("to_local_level"));
            }
            JdbcProfileService reloaded = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
            assertEquals(before, reloaded.get(player).toCompletableFuture().join());
        }
    }

    private static long firstMilestoneXp() {
        return ProgressionRules.experienceToNextLocalLevel(0, 1)
                + ProgressionRules.experienceToNextLocalLevel(0, 2) + 17L;
    }

    private static void replaceWithLegacyTable(RuntimeDatabaseFixture fixture) throws Exception {
        try (Connection connection = fixture.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE rpg_skill_point_grants");
            statement.execute("""
                    CREATE TABLE rpg_skill_point_grants (
                        source VARCHAR(96) NOT NULL, idempotency_key VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL, from_local_level INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE (source, idempotency_key)
                    )
                    """);
            connection.commit();
        }
    }
}
