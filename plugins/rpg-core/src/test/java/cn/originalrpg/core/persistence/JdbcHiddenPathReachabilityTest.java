package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.rules.HiddenPathRules;
import cn.originalrpg.api.rules.HiddenPathReachability;
import cn.originalrpg.api.rules.ProgressionRules;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end reachability for the shipped hidden routes. The schema is created
 * through the production additive migration, route facts come from
 * {@link HiddenPathRules}, and every assertion goes through the real
 * {@link JdbcHiddenPathService.eligibility} SQL path.
 */
final class JdbcHiddenPathReachabilityTest {
    private final Executor direct = Runnable::run;
    private JdbcDataSource dataSource;
    private JdbcHiddenPathService paths;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:hidden-reachability-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        try (Connection connection = dataSource.getConnection()) {
            Database.executeMigrations(connection, java.util.List.of(Database.schemaMigrations()), false, 30_000L);
        }
        paths = new JdbcHiddenPathService(dataSource, direct);
    }

    @AfterEach
    void close() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("SHUTDOWN");
        }
    }

    @Test
    void aBloodpactAssassinAtTheStageThreeGateIsEligible() throws Exception {
        // The documented build: 3rd ascension, local 20, Agi 32 / Sta 18,
        // vanished-horizon learned, storm-vault-hidden cleared, credential book
        // redeemed. G-106 shipped this exact player as permanently ineligible.
        UUID player = UUID.randomUUID();
        seedAssassin(player, 3, 20, 32, 18, true);
        assertTrue(eligibility(player, HiddenPath.BLOODPACT).eligible(),
                "the authored bloodpact build must be able to accept the route");
        assertEquals(3, HiddenPathRules.minimumAscension(HiddenPath.BLOODPACT));
    }

    @Test
    void anAssassinBelowTheStageThreeGateIsRejectedOnTheRightRequirement() throws Exception {
        UUID player = UUID.randomUUID();
        seedAssassin(player, 2, 20, 32, 18, true);
        var eligibility = eligibility(player, HiddenPath.BLOODPACT);
        assertFalse(eligibility.eligible());
        assertTrue(eligibility.missingRequirements().contains("ascension"),
                eligibility.missingRequirements().toString());
    }

    @Test
    void theLevelGateIsStillALocalLevelInsideTheRouteAscension() throws Exception {
        UUID player = UUID.randomUUID();
        seedAssassin(player, 3, 11, 32, 18, true);
        var eligibility = eligibility(player, HiddenPath.BLOODPACT);
        assertFalse(eligibility.eligible());
        assertTrue(eligibility.missingRequirements().contains("level"),
                "a stage-3 local 11 player is below the local-20 gate: "
                        + eligibility.missingRequirements());
    }

    @Test
    void everyAuthoredRouteGateIsSatisfiableInsideItsOwnAscension() {
        // The rules-level companion to the plugin startup check: whatever the
        // route's ascension is, its own local level gate must fit that stage.
        for (HiddenPath path : HiddenPath.values()) {
            int ascension = HiddenPathRules.minimumAscension(path);
            assertTrue(ascension <= ProgressionRules.MAX_ASCENSION, path.toString());
            assertTrue(HiddenPathRules.minimumLevel(path) <= ProgressionRules.LOCAL_LEVEL_CAP, path.toString());
            assertTrue(HiddenPathReachability.unattainable(path, HiddenPathReachability.authored(path)).isEmpty(),
                    path.toString());
        }
    }

    @Test
    void theTalismanRouteStillOpensAtTheSecondAscension() throws Exception {
        UUID player = UUID.randomUUID();
        // Authored gate: ascension 2, local 20, Int 18 / Str 13 / Agi 9 / Sta 6.
        seedFreeform(player, 2, 20);
        var eligibility = eligibility(player, HiddenPath.TALISMAN);
        assertTrue(eligibility.eligible(), eligibility.missingRequirements().toString());
        assertEquals(2, HiddenPathRules.minimumAscension(HiddenPath.TALISMAN));
    }

    private cn.originalrpg.api.model.HiddenPathEligibility eligibility(UUID player, HiddenPath path) {
        return paths.eligibility(player, path).toCompletableFuture().join();
    }

    private void seedAssassin(UUID player, int ascension, int localLevel, int agility, int stamina, boolean book)
            throws Exception {
        seedProfile(player, "ASSASSIN", ascension, localLevel, agility, stamina, 0);
        seedRouteFacts(player, "vanished-horizon", "storm-vault-hidden", book ? "silent-thunder" : null);
    }

    private void seedFreeform(UUID player, int ascension, int localLevel) throws Exception {
        seedProfile(player, "FREEFORM", ascension, localLevel, 9, 6, 18);
        seedRouteFacts(player, "rune-reversal", "sulfur-depths-hidden", "cinnabar-revelation");
    }

    private void seedProfile(
            UUID player, String archetype, int ascension, int localLevel,
            int agility, int stamina, int intelligence
    ) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    INSERT INTO rpg_profiles (
                        player_uuid, archetype, ascension, level, experience, energy, unspent_attribute_points,
                        skill_points, defense, strength, agility, intelligence, stamina
                    ) VALUES ('%s', '%s', %d, %d, 0, 100, 0, 0, 0, 13, %d, %d, %d)
                    """.formatted(player, archetype, ascension, localLevel, agility, intelligence, stamina));
            connection.commit();
        }
    }

    private void seedRouteFacts(UUID player, String skill, String dungeon, String book) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost) VALUES ('"
                    + player + "', '" + skill + "', 1, 0)");
            sql.execute("""
                    INSERT INTO rpg_dungeon_reward_receipts (
                        run_id, player_uuid, dungeon_id, experience_awarded, queued_deliveries,
                        ticket_dropped, ticket_failures
                    ) VALUES ('%s', '%s', '%s', 1, 0, FALSE, 0)
                    """.formatted(UUID.randomUUID(), player, dungeon));
            if (book != null) {
                sql.execute("""
                        INSERT INTO rpg_skill_book_uses (
                            instance_id, player_uuid, book_id, limit_group, points_granted
                        ) VALUES ('%s', '%s', '%s', NULL, 3)
                        """.formatted(UUID.randomUUID(), player, book));
            }
            connection.commit();
        }
    }
}
