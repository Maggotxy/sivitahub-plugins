package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SeasonExpeditionCompletion;
import cn.originalrpg.api.model.SeasonExpeditionResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcSeasonExpeditionServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcSeasonExpeditionService service;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:season_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(24);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_season_rotation_state (
                        season_id VARCHAR(32) NOT NULL, rotation_id VARCHAR(32) NOT NULL,
                        dungeon_id VARCHAR(48) NOT NULL, first_run_id VARCHAR(36),
                        first_completed_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (season_id, rotation_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_runs (
                        run_id VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        rotation_id VARCHAR(32) NOT NULL, dungeon_id VARCHAR(48) NOT NULL,
                        party_size INTEGER NOT NULL, duration_ms BIGINT NOT NULL,
                        team_key VARCHAR(160) NOT NULL, member_ids VARCHAR(160) NOT NULL,
                        server_first BOOLEAN NOT NULL, completed_at TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_run_members (
                        run_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        PRIMARY KEY (run_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_player_progress (
                        season_id VARCHAR(32) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        honor BIGINT NOT NULL DEFAULT 0, collection_stamps INTEGER NOT NULL DEFAULT 0,
                        completed_runs INTEGER NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (season_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_rotation_claims (
                        season_id VARCHAR(32) NOT NULL, rotation_id VARCHAR(32) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, run_id VARCHAR(36) NOT NULL,
                        honor_awarded INTEGER NOT NULL,
                        claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (season_id, rotation_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_personal_bests (
                        season_id VARCHAR(32) NOT NULL, rotation_id VARCHAR(32) NOT NULL,
                        party_size INTEGER NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        run_id VARCHAR(36) NOT NULL, duration_ms BIGINT NOT NULL,
                        completed_at TIMESTAMP NOT NULL,
                        PRIMARY KEY (season_id, rotation_id, party_size, player_uuid)
                    )
                    """);
            connection.commit();
        }
        service = new JdbcSeasonExpeditionService(dataSource, executor, 100, 50);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void oneTransactionAwardsWeeklyStampFirstClearAndPersonalBest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        SeasonExpeditionCompletion completion = completion(
                UUID.randomUUID(), "week-2026-08-03", 2, 93_400L, List.of(first, second), 0);

        var settled = service.complete(completion).toCompletableFuture().join();
        assertEquals(SeasonExpeditionResult.Status.SUCCESS, settled.status());
        assertTrue(settled.serverFirst());
        assertEquals(2, settled.players().size());
        assertTrue(settled.players().stream().allMatch(player -> player.honorGranted() == 150));
        assertTrue(settled.players().stream().allMatch(player -> player.weeklyClaimedNow()));
        assertTrue(settled.players().stream().allMatch(player -> player.newPersonalBest()));

        var profile = service.profile(first, "launch-1", "week-2026-08-03", 2)
                .toCompletableFuture().join();
        assertEquals(150, profile.honor());
        assertEquals(1, profile.collectionStamps());
        assertEquals(1, profile.completedRuns());
        assertTrue(profile.currentRotationClaimed());
        assertEquals(93_400L, profile.personalBestMillis().orElseThrow());

        var firstClear = service.firstClear("launch-1", "week-2026-08-03")
                .toCompletableFuture().join().orElseThrow();
        assertEquals(completion.runId(), firstClear.runId());
        assertEquals(2, firstClear.memberIds().size());
    }

    @Test
    void retryAndRepeatRunsCannotRepeatWeeklyReward() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        SeasonExpeditionCompletion slow = completion(
                UUID.randomUUID(), "week-2026-08-03", 2, 100_000L, List.of(first, second), 0);
        assertEquals(SeasonExpeditionResult.Status.SUCCESS,
                service.complete(slow).toCompletableFuture().join().status());
        assertEquals(SeasonExpeditionResult.Status.DUPLICATE,
                service.complete(slow).toCompletableFuture().join().status());

        SeasonExpeditionCompletion fast = completion(
                UUID.randomUUID(), "week-2026-08-03", 2, 80_000L, List.of(first, second), 1);
        var improved = service.complete(fast).toCompletableFuture().join();
        assertFalse(improved.serverFirst());
        assertTrue(improved.players().stream().allMatch(player -> player.honorGranted() == 0));
        assertTrue(improved.players().stream().noneMatch(player -> player.weeklyClaimedNow()));
        assertTrue(improved.players().stream().allMatch(player -> player.newPersonalBest()));

        var profile = service.profile(first, "launch-1", "week-2026-08-03", 2)
                .toCompletableFuture().join();
        assertEquals(150, profile.honor());
        assertEquals(1, profile.collectionStamps());
        assertEquals(2, profile.completedRuns());
        assertEquals(80_000L, profile.personalBestMillis().orElseThrow());

        var leaderboard = service.leaderboard("launch-1", "week-2026-08-03", 2, 10)
                .toCompletableFuture().join();
        assertEquals(1, leaderboard.size(), "one team may occupy only its fastest leaderboard row");
        assertEquals(fast.runId(), leaderboard.getFirst().runId());
    }

    @Test
    void weeklyAndSeasonKeysDefineNonDestructiveResetBoundaries() {
        UUID player = UUID.randomUUID();
        service.complete(completion(UUID.randomUUID(), "week-2026-08-03", 1,
                90_000L, List.of(player), 0)).toCompletableFuture().join();
        service.complete(completion(UUID.randomUUID(), "week-2026-08-10", 1,
                88_000L, List.of(player), 7)).toCompletableFuture().join();

        var launch = service.profile(player, "launch-1", "week-2026-08-10", 1)
                .toCompletableFuture().join();
        assertEquals(300, launch.honor(), "each rotation also has its own server-first bonus");
        assertEquals(2, launch.collectionStamps());
        assertEquals(2, launch.completedRuns());

        var nextSeason = service.profile(player, "launch-2", "week-2026-08-10", 1)
                .toCompletableFuture().join();
        assertEquals(0, nextSeason.honor());
        assertEquals(0, nextSeason.collectionStamps());
        assertFalse(nextSeason.currentRotationClaimed());

        var archived = service.profile(player, "launch-1", "week-2026-08-03", 1)
                .toCompletableFuture().join();
        assertEquals(300, archived.honor(), "old season data remains queryable rather than being deleted");
    }

    @Test
    void concurrentCompletionsHaveExactlyOneFirstClearAndOnePlayerClaim() {
        UUID player = UUID.randomUUID();
        List<CompletableFuture<SeasonExpeditionResult>> runs = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            runs.add(service.complete(completion(UUID.randomUUID(), "week-2026-09-07", 1,
                    120_000L - index * 1_000L, List.of(player), 35 + index))
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, runs.stream().map(CompletableFuture::join)
                .filter(SeasonExpeditionResult::serverFirst).count());
        assertEquals(1, runs.stream().map(CompletableFuture::join)
                .flatMap(result -> result.players().stream()).filter(playerResult -> playerResult.honorGranted() > 0)
                .count());
        var profile = service.profile(player, "launch-1", "week-2026-09-07", 1)
                .toCompletableFuture().join();
        assertEquals(150, profile.honor());
        assertEquals(1, profile.collectionStamps());
        assertEquals(20, profile.completedRuns());
        assertEquals(101_000L, profile.personalBestMillis().orElseThrow());
    }

    @Test
    void permanentSeasonProgressLimitRejectsBeforeRunOrClaimAndCanBeRetried() throws Exception {
        UUID player = UUID.randomUUID();
        String season = "launch-1";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_season_player_progress "
                    + "(season_id, player_uuid, honor, collection_stamps, completed_runs) VALUES ('"
                    + season + "', '" + player + "', 1000000000000, 0, 0)");
            connection.commit();
        }
        UUID runId = UUID.randomUUID();
        SeasonExpeditionCompletion completion = completion(
                runId, "week-2026-09-14", 1, 90_000L, List.of(player), 42);

        SeasonExpeditionResult rejected = service.complete(completion).toCompletableFuture().join();
        assertEquals(SeasonExpeditionResult.Status.PROGRESS_LIMIT, rejected.status());
        assertEquals(0, count("SELECT COUNT(*) FROM rpg_season_runs WHERE run_id = '" + runId + "'"));
        assertEquals(0, count("SELECT COUNT(*) FROM rpg_season_rotation_claims WHERE player_uuid = '"
                + player + "'"));

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE rpg_season_player_progress SET honor = 999999999850 "
                    + "WHERE season_id = '" + season + "' AND player_uuid = '" + player + "'");
            connection.commit();
        }
        SeasonExpeditionResult retried = service.complete(completion).toCompletableFuture().join();
        assertEquals(SeasonExpeditionResult.Status.SUCCESS, retried.status());
        assertEquals(1_000_000_000_000L, retried.players().getFirst().totalHonor());
    }

    private int count(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static SeasonExpeditionCompletion completion(
            UUID runId, String rotation, int partySize, long duration, List<UUID> players, long seconds
    ) {
        String dungeon = switch (rotation) {
            case "week-2026-08-03" -> "ancient-ruins";
            case "week-2026-08-10" -> "sulfur-depths";
            default -> "storm-vault";
        };
        return new SeasonExpeditionCompletion(runId, "launch-1", rotation, dungeon, partySize,
                duration, players, Instant.parse("2026-08-03T01:00:00Z").plusSeconds(seconds));
    }
}
