package cn.originalrpg.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class JdbcCivilizationHistoryServiceTest {
    private Database database;
    private CommunityDatabaseFixture fixture;
    private JdbcCivilizationHistoryService history;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new CommunityDatabaseFixture();
        database = fixture.database;
        history = new JdbcCivilizationHistoryService(database.dataSource(), Runnable::run);
    }

    @AfterEach
    void close() throws Exception { if (fixture != null) fixture.close(); }

    @Test
    void emptyArchiveDoesNotCreateProgressAccountsOrRewards() throws Exception {
        assertTrue(history.completedProjects(1).toCompletableFuture().join().isEmpty());
        assertTrue(history.completedSeasons(1).toCompletableFuture().join().isEmpty());
        assertEquals(0, count("rpg_accounts"));
        assertEquals(0, count("rpg_ledger"));
        assertEquals(0, count("rpg_community_project_rewards"));
    }

    @Test
    void completedDissolvedProjectsRemainVisibleAndAggregationDoesNotMultiplyRewards() throws Exception {
        UUID community = community(true);
        project(community, "2026-W44", true);
        project(community, "2026-W45", false);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        contribution(community, first, "2026-W44");
        contribution(community, first, "2026-W44");
        contribution(community, second, "2026-W44");
        contribution(community, UUID.randomUUID(), "2026-W45");
        execute("INSERT INTO rpg_community_project_rewards (player_uuid,period_key,community_id,"
                        + "player_contribution,minimum_contribution,honor_awarded) VALUES (?,?,?,?,?,?)",
                first.toString(), "2026-W44", community.toString(), 100, 100, 40);
        execute("INSERT INTO rpg_community_project_rewards (player_uuid,period_key,community_id,"
                        + "player_contribution,minimum_contribution,honor_awarded) VALUES (?,?,?,?,?,?)",
                second.toString(), "2026-W44", community.toString(), 100, 100, 60);
        var rows = history.completedProjects(1).toCompletableFuture().join();
        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertEquals(community, row.communityId());
        assertEquals("2026-W44", row.periodKey());
        assertEquals(new BigDecimal("200.00"), row.goldContributed());
        assertEquals(2, row.contributors());
        assertEquals(100, row.honorAwarded());
        assertTrue(row.communityDissolved());
        assertEquals(rows, history.completedProjects(1).toCompletableFuture().join());
        assertEquals(2, count("rpg_community_project_rewards"));
        assertEquals(0, count("rpg_ledger"));
    }

    @Test
    void equalCompletionTimesHaveDeterministicBoundedPages() throws Exception {
        UUID community = community(false);
        for (int i = 0; i < 11; i++) project(community, "period-" + String.format("%02d", i), true);
        var first = history.completedProjects(1).toCompletableFuture().join();
        var second = history.completedProjects(2).toCompletableFuture().join();
        assertEquals(8, first.size());
        assertEquals(3, second.size());
        List<String> periods = new ArrayList<>();
        first.forEach(row -> periods.add(row.periodKey()));
        second.forEach(row -> periods.add(row.periodKey()));
        assertEquals(11, periods.stream().distinct().count());
        assertEquals(periods.stream().sorted().toList(), periods);
        assertTrue(history.completedProjects(3).toCompletableFuture().join().isEmpty());
    }

    @Test
    void onlySuccessfullyCompletedSeasonsEnterTheExhibition() throws Exception {
        for (String status : List.of("DRAFT", "PREVIEWED", "APPROVED", "FREEZING", "SETTLING",
                "VERIFYING", "FAILED", "ROLLED_BACK", "COMPLETED")) {
            execute("INSERT INTO rpg_season_settlements (id,season_id,status) VALUES (?,?,?)",
                    UUID.randomUUID().toString(), "season-" + status.toLowerCase(), status);
        }
        var seasons = history.completedSeasons(1).toCompletableFuture().join();
        assertEquals(1, seasons.size());
        assertEquals("season-completed", seasons.getFirst().seasonId());
        assertEquals(9, count("rpg_season_settlements"));
    }

    @Test
    void invalidPaginationFailsBeforeSubmittingDatabaseWork() {
        assertThrows(IllegalArgumentException.class, () -> history.completedProjects(0));
        assertThrows(IllegalArgumentException.class, () -> history.completedProjects(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> history.completedSeasons(-1));
        assertThrows(IllegalArgumentException.class, () -> history.completedSeasons(1001));
    }

    private UUID community(boolean dissolved) throws Exception {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO rpg_communities (id,name,normalized_name,tag,normalized_tag,owner_uuid,capacity,dissolved_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)", id.toString(), "Archive", id.toString(), "ARC",
                id.toString().substring(0, 8), UUID.randomUUID().toString(), 40,
                dissolved ? Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")) : null);
        return id;
    }

    private void project(UUID community, String period, boolean complete) throws Exception {
        execute("INSERT INTO rpg_community_projects (community_id,period_key,gold_contributed,verified_recruits,completed_at)"
                        + " VALUES (?,?,?,?,?)", community.toString(), period, new BigDecimal("200.00"), 2,
                complete ? Timestamp.from(Instant.parse("2026-10-31T00:00:00Z")) : null);
    }

    private void contribution(UUID community, UUID player, String period) throws Exception {
        execute("INSERT INTO rpg_community_contributions (id,idempotency_key,community_id,player_uuid,period_key,amount)"
                        + " VALUES (?,?,?,?,?,?)", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                community.toString(), player.toString(), period, new BigDecimal("100.00"));
    }

    private void execute(String sql, Object... args) throws Exception {
        try (Connection connection = database.dataSource().getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
            connection.commit();
        }
    }

    private long count(String table) throws Exception {
        try (Connection connection = database.dataSource().getConnection();
             var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}
