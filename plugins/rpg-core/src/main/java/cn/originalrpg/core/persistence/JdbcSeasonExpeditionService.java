package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SeasonExpeditionCompletion;
import cn.originalrpg.api.model.SeasonExpeditionLeaderboardEntry;
import cn.originalrpg.api.model.SeasonExpeditionPlayerResult;
import cn.originalrpg.api.model.SeasonExpeditionProfile;
import cn.originalrpg.api.model.SeasonExpeditionResult;
import cn.originalrpg.api.service.SeasonExpeditionService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Transactional settlement for the repeatable dungeon endgame. Honor is an
 * isolated, non-convertible season score: this service never touches gold,
 * merit, Starlight, inventories or combat progression.
 */
public final class JdbcSeasonExpeditionService implements SeasonExpeditionService {
    private static final Pattern SEASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");
    private static final Pattern ROTATION_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");
    private static final Pattern DUNGEON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,47}");
    private static final long MAXIMUM_DURATION_MILLIS = 4L * 60L * 60L * 1000L;
    private static final long MAX_HONOR = 1_000_000_000_000L;
    private static final int MAX_COLLECTION_STAMPS = 1_000_000_000;
    private static final int MAX_COMPLETED_RUNS = 1_000_000_000;

    private final DataSource dataSource;
    private final Executor executor;
    private final int weeklyHonor;
    private final int serverFirstBonus;

    public JdbcSeasonExpeditionService(
            DataSource dataSource, Executor executor, int weeklyHonor, int serverFirstBonus
    ) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        if (weeklyHonor < 0 || weeklyHonor > 1_000 || serverFirstBonus < 0 || serverFirstBonus > 1_000) {
            throw new IllegalArgumentException("Season honor rewards must remain between 0 and 1000");
        }
        this.weeklyHonor = weeklyHonor;
        this.serverFirstBonus = serverFirstBonus;
    }

    @Override
    public CompletionStage<SeasonExpeditionResult> complete(SeasonExpeditionCompletion completion) {
        CanonicalCompletion request = canonical(completion);
        if (request == null) return CompletableFuture.completedFuture(invalid());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> settle(connection, request)));
    }

    @Override
    public CompletionStage<SeasonExpeditionProfile> profile(
            UUID playerId, String seasonId, String rotationId, int partySize
    ) {
        if (playerId == null || !valid(SEASON_ID, seasonId) || !valid(ROTATION_ID, rotationId)
                || partySize < 1 || partySize > 4) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid season profile query"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Progress progress = loadProgress(connection, playerId, seasonId, false);
            boolean claimed = claimExists(connection, playerId, seasonId, rotationId);
            Long personalBest = personalBest(connection, playerId, seasonId, rotationId, partySize, false);
            return new SeasonExpeditionProfile(playerId, seasonId,
                    progress == null ? 0L : progress.honor(),
                    progress == null ? 0 : progress.stamps(),
                    progress == null ? 0 : progress.completedRuns(),
                    claimed,
                    personalBest == null ? OptionalLong.empty() : OptionalLong.of(personalBest));
        }));
    }

    @Override
    public CompletionStage<List<SeasonExpeditionLeaderboardEntry>> leaderboard(
            String seasonId, String rotationId, int partySize, int limit
    ) {
        if (!valid(SEASON_ID, seasonId) || !valid(ROTATION_ID, rotationId)
                || partySize < 1 || partySize > 4 || limit < 1 || limit > 50) {
            return CompletableFuture.completedFuture(List.of());
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            List<SeasonExpeditionLeaderboardEntry> result = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT run_id, duration_ms, member_ids, completed_at, server_first
                    FROM rpg_season_runs candidate
                    WHERE season_id = ? AND rotation_id = ? AND party_size = ?
                      AND NOT EXISTS (
                        SELECT 1 FROM rpg_season_runs faster
                        WHERE faster.season_id = candidate.season_id
                          AND faster.rotation_id = candidate.rotation_id
                          AND faster.party_size = candidate.party_size
                          AND faster.team_key = candidate.team_key
                          AND (faster.duration_ms < candidate.duration_ms
                            OR (faster.duration_ms = candidate.duration_ms
                              AND faster.run_id < candidate.run_id))
                      )
                    ORDER BY duration_ms ASC, completed_at ASC, run_id ASC
                    LIMIT ?
                    """)) {
                query.setString(1, seasonId);
                query.setString(2, rotationId);
                query.setInt(3, partySize);
                query.setInt(4, limit);
                try (ResultSet rows = query.executeQuery()) {
                    int rank = 1;
                    while (rows.next()) {
                        result.add(new SeasonExpeditionLeaderboardEntry(
                                rank++, UUID.fromString(rows.getString("run_id")),
                                rows.getLong("duration_ms"), parseMembers(rows.getString("member_ids")),
                                rows.getTimestamp("completed_at").toInstant(), rows.getBoolean("server_first")));
                    }
                }
            }
            return List.copyOf(result);
        }));
    }

    @Override
    public CompletionStage<Optional<SeasonExpeditionLeaderboardEntry>> firstClear(
            String seasonId, String rotationId
    ) {
        if (!valid(SEASON_ID, seasonId) || !valid(ROTATION_ID, rotationId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT run.run_id, run.duration_ms, run.member_ids, run.completed_at
                    FROM rpg_season_rotation_state rotation
                    JOIN rpg_season_runs run ON run.run_id = rotation.first_run_id
                    WHERE rotation.season_id = ? AND rotation.rotation_id = ?
                    """)) {
                query.setString(1, seasonId);
                query.setString(2, rotationId);
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next()) return Optional.empty();
                    return Optional.of(new SeasonExpeditionLeaderboardEntry(
                            0, UUID.fromString(row.getString("run_id")), row.getLong("duration_ms"),
                            parseMembers(row.getString("member_ids")),
                            row.getTimestamp("completed_at").toInstant(), true));
                }
            }
        }));
    }

    private SeasonExpeditionResult settle(Connection connection, CanonicalCompletion request) throws SQLException {
        ensureRotation(connection, request);
        RotationState rotation = lockRotation(connection, request.seasonId(), request.rotationId());
        if (rotation == null || !rotation.dungeonId().equals(request.dungeonId())) return invalid();

        ExistingRun existing = loadRun(connection, request.runId());
        if (existing != null) {
            return existing.matches(request)
                    ? new SeasonExpeditionResult(SeasonExpeditionResult.Status.DUPLICATE, false, List.of())
                    : invalid();
        }

        List<Progress> progressRows = new ArrayList<>(request.participants().size());
        for (UUID playerId : request.participants()) {
            ensureProgress(connection, playerId, request.seasonId());
            Progress progress = loadProgress(connection, playerId, request.seasonId(), true);
            if (progress == null) throw new IllegalStateException("Season progress disappeared");
            if (progress.completedRuns() >= MAX_COMPLETED_RUNS) {
                return new SeasonExpeditionResult(SeasonExpeditionResult.Status.PROGRESS_LIMIT, false, List.of());
            }
            progressRows.add(progress);
        }

        boolean serverFirst = rotation.firstRunId() == null;
        for (int index = 0; index < request.participants().size(); index++) {
            UUID playerId = request.participants().get(index);
            Progress progress = progressRows.get(index);
            boolean alreadyClaimed = claimExists(
                    connection, playerId, request.seasonId(), request.rotationId());
            if (alreadyClaimed) continue;
            int honor = weeklyHonor + (serverFirst ? serverFirstBonus : 0);
            if (progress.honor() > MAX_HONOR - honor
                    || progress.stamps() >= MAX_COLLECTION_STAMPS) {
                return new SeasonExpeditionResult(SeasonExpeditionResult.Status.PROGRESS_LIMIT, false, List.of());
            }
        }
        insertRun(connection, request, serverFirst);
        if (serverFirst) markServerFirst(connection, request);

        List<SeasonExpeditionPlayerResult> players = new ArrayList<>(request.participants().size());
        for (int index = 0; index < request.participants().size(); index++) {
            UUID playerId = request.participants().get(index);
            insertRunMember(connection, request.runId(), playerId);
            Progress progress = progressRows.get(index);

            boolean claimedNow = insertClaim(connection, playerId, request, serverFirst);
            int honorGranted = claimedNow ? weeklyHonor + (serverFirst ? serverFirstBonus : 0) : 0;
            boolean newBest = updatePersonalBest(connection, playerId, request);
            Long best = personalBest(connection, playerId, request.seasonId(),
                    request.rotationId(), request.partySize(), false);
            if (best == null) throw new IllegalStateException("Season personal best disappeared");

            Progress updated = updateProgress(connection, playerId, request.seasonId(), honorGranted, claimedNow);
            players.add(new SeasonExpeditionPlayerResult(playerId, honorGranted, updated.honor(), updated.stamps(),
                    updated.completedRuns(), claimedNow, newBest, best));
        }
        return new SeasonExpeditionResult(SeasonExpeditionResult.Status.SUCCESS, serverFirst, players);
    }

    private static CanonicalCompletion canonical(SeasonExpeditionCompletion completion) {
        if (completion == null || completion.runId() == null
                || !valid(SEASON_ID, completion.seasonId())
                || !valid(ROTATION_ID, completion.rotationId())
                || !valid(DUNGEON_ID, completion.dungeonId())
                || completion.partySize() < 1 || completion.partySize() > 4
                || completion.durationMillis() < 1_000L
                || completion.durationMillis() > MAXIMUM_DURATION_MILLIS
                || completion.completedAt() == null || completion.participantIds() == null
                || completion.participantIds().size() != completion.partySize()
                || completion.participantIds().stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(completion.participantIds()).size() != completion.participantIds().size()) {
            return null;
        }
        List<UUID> participants = completion.participantIds().stream().sorted().toList();
        String members = participants.stream().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        return new CanonicalCompletion(completion.runId(), completion.seasonId(), completion.rotationId(),
                completion.dungeonId(), completion.partySize(), completion.durationMillis(), participants,
                members, members, Instant.ofEpochMilli(completion.completedAt().toEpochMilli()));
    }

    private static void ensureRotation(Connection connection, CanonicalCompletion request) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_rotation_state
                    (season_id, rotation_id, dungeon_id)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, request.seasonId());
            insert.setString(2, request.rotationId());
            insert.setString(3, request.dungeonId());
            insert.executeUpdate();
        }
    }

    private static RotationState lockRotation(Connection connection, String seasonId, String rotationId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT dungeon_id, first_run_id FROM rpg_season_rotation_state
                WHERE season_id = ? AND rotation_id = ? FOR UPDATE
                """)) {
            query.setString(1, seasonId);
            query.setString(2, rotationId);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                String firstRun = row.getString("first_run_id");
                return new RotationState(row.getString("dungeon_id"),
                        firstRun == null ? null : UUID.fromString(firstRun));
            }
        }
    }

    private static ExistingRun loadRun(Connection connection, UUID runId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT season_id, rotation_id, dungeon_id, party_size, duration_ms, member_ids, completed_at
                FROM rpg_season_runs WHERE run_id = ?
                """)) {
            query.setString(1, runId.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                return new ExistingRun(row.getString("season_id"), row.getString("rotation_id"),
                        row.getString("dungeon_id"), row.getInt("party_size"), row.getLong("duration_ms"),
                        row.getString("member_ids"), row.getTimestamp("completed_at").toInstant());
            }
        }
    }

    private static void insertRun(Connection connection, CanonicalCompletion request, boolean serverFirst)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_runs
                    (run_id, season_id, rotation_id, dungeon_id, party_size, duration_ms,
                     team_key, member_ids, server_first, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, request.runId().toString());
            insert.setString(2, request.seasonId());
            insert.setString(3, request.rotationId());
            insert.setString(4, request.dungeonId());
            insert.setInt(5, request.partySize());
            insert.setLong(6, request.durationMillis());
            insert.setString(7, request.teamKey());
            insert.setString(8, request.memberIds());
            insert.setBoolean(9, serverFirst);
            insert.setTimestamp(10, Timestamp.from(request.completedAt()));
            insert.executeUpdate();
        }
    }

    private static void markServerFirst(Connection connection, CanonicalCompletion request) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_rotation_state
                SET first_run_id = ?, first_completed_at = ?
                WHERE season_id = ? AND rotation_id = ? AND first_run_id IS NULL
                """)) {
            update.setString(1, request.runId().toString());
            update.setTimestamp(2, Timestamp.from(request.completedAt()));
            update.setString(3, request.seasonId());
            update.setString(4, request.rotationId());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Server-first lock was lost");
        }
    }

    private static void insertRunMember(Connection connection, UUID runId, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_run_members (run_id, player_uuid) VALUES (?, ?)
                """)) {
            insert.setString(1, runId.toString());
            insert.setString(2, playerId.toString());
            insert.executeUpdate();
        }
    }

    private static void ensureProgress(Connection connection, UUID playerId, String seasonId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_player_progress
                    (season_id, player_uuid, honor, collection_stamps, completed_runs)
                VALUES (?, ?, 0, 0, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, seasonId);
            insert.setString(2, playerId.toString());
            insert.executeUpdate();
        }
    }

    private static Progress loadProgress(Connection connection, UUID playerId, String seasonId, boolean lock)
            throws SQLException {
        String sql = """
                SELECT honor, collection_stamps, completed_runs FROM rpg_season_player_progress
                WHERE season_id = ? AND player_uuid = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, seasonId);
            query.setString(2, playerId.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new Progress(row.getLong("honor"), row.getInt("collection_stamps"),
                        row.getInt("completed_runs")) : null;
            }
        }
    }

    private boolean insertClaim(
            Connection connection, UUID playerId, CanonicalCompletion request, boolean serverFirst
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_rotation_claims
                    (season_id, rotation_id, player_uuid, run_id, honor_awarded)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, request.seasonId());
            insert.setString(2, request.rotationId());
            insert.setString(3, playerId.toString());
            insert.setString(4, request.runId().toString());
            insert.setInt(5, weeklyHonor + (serverFirst ? serverFirstBonus : 0));
            return insert.executeUpdate() == 1;
        }
    }

    private static boolean claimExists(Connection connection, UUID playerId, String seasonId, String rotationId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM rpg_season_rotation_claims
                WHERE season_id = ? AND rotation_id = ? AND player_uuid = ?
                """)) {
            query.setString(1, seasonId);
            query.setString(2, rotationId);
            query.setString(3, playerId.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next();
            }
        }
    }

    private static Progress updateProgress(
            Connection connection, UUID playerId, String seasonId, int honor, boolean stamp
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_player_progress
                SET honor = honor + ?, collection_stamps = collection_stamps + ?,
                    completed_runs = completed_runs + 1, updated_at = CURRENT_TIMESTAMP
                WHERE season_id = ? AND player_uuid = ?
                """)) {
            update.setInt(1, honor);
            update.setInt(2, stamp ? 1 : 0);
            update.setString(3, seasonId);
            update.setString(4, playerId.toString());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Season progress update was lost");
        }
        Progress progress = loadProgress(connection, playerId, seasonId, false);
        if (progress == null) throw new IllegalStateException("Season progress disappeared after update");
        return progress;
    }

    private static boolean updatePersonalBest(Connection connection, UUID playerId, CanonicalCompletion request)
            throws SQLException {
        Long existing = personalBest(connection, playerId, request.seasonId(),
                request.rotationId(), request.partySize(), true);
        if (existing == null) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_season_personal_bests
                        (season_id, rotation_id, party_size, player_uuid, run_id, duration_ms, completed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bindBest(insert, playerId, request);
                insert.executeUpdate();
            }
            return true;
        }
        if (request.durationMillis() >= existing) return false;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_personal_bests
                SET run_id = ?, duration_ms = ?, completed_at = ?
                WHERE season_id = ? AND rotation_id = ? AND party_size = ? AND player_uuid = ?
                """)) {
            update.setString(1, request.runId().toString());
            update.setLong(2, request.durationMillis());
            update.setTimestamp(3, Timestamp.from(request.completedAt()));
            update.setString(4, request.seasonId());
            update.setString(5, request.rotationId());
            update.setInt(6, request.partySize());
            update.setString(7, playerId.toString());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Personal best update was lost");
        }
        return true;
    }

    private static void bindBest(PreparedStatement insert, UUID playerId, CanonicalCompletion request)
            throws SQLException {
        insert.setString(1, request.seasonId());
        insert.setString(2, request.rotationId());
        insert.setInt(3, request.partySize());
        insert.setString(4, playerId.toString());
        insert.setString(5, request.runId().toString());
        insert.setLong(6, request.durationMillis());
        insert.setTimestamp(7, Timestamp.from(request.completedAt()));
    }

    private static Long personalBest(
            Connection connection, UUID playerId, String seasonId, String rotationId, int partySize, boolean lock
    ) throws SQLException {
        String sql = """
                SELECT duration_ms FROM rpg_season_personal_bests
                WHERE season_id = ? AND rotation_id = ? AND party_size = ? AND player_uuid = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, seasonId);
            query.setString(2, rotationId);
            query.setInt(3, partySize);
            query.setString(4, playerId.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getLong("duration_ms") : null;
            }
        }
    }

    private static List<UUID> parseMembers(String members) {
        if (members == null || members.isBlank()) return List.of();
        return java.util.Arrays.stream(members.split(","))
                .map(UUID::fromString).sorted(Comparator.naturalOrder()).toList();
    }

    private static boolean valid(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static SeasonExpeditionResult invalid() {
        return new SeasonExpeditionResult(SeasonExpeditionResult.Status.INVALID, false, List.of());
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Season expedition transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open season expedition transaction", exception);
        }
    }

    private record CanonicalCompletion(
            UUID runId, String seasonId, String rotationId, String dungeonId, int partySize,
            long durationMillis, List<UUID> participants, String teamKey, String memberIds, Instant completedAt
    ) {
    }

    private record RotationState(String dungeonId, UUID firstRunId) {
    }

    private record ExistingRun(
            String seasonId, String rotationId, String dungeonId, int partySize,
            long durationMillis, String memberIds, Instant completedAt
    ) {
        private boolean matches(CanonicalCompletion request) {
            return seasonId.equals(request.seasonId()) && rotationId.equals(request.rotationId())
                    && dungeonId.equals(request.dungeonId()) && partySize == request.partySize()
                    && durationMillis == request.durationMillis() && memberIds.equals(request.memberIds())
                    && completedAt.equals(request.completedAt());
        }
    }

    private record Progress(long honor, int stamps, int completedRuns) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
