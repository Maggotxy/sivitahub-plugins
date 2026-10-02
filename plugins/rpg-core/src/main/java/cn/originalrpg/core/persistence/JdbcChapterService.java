package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.ChapterDefinition;
import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.ChapterTransitionResult;
import cn.originalrpg.api.service.ChapterService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Database-authoritative, monotonic chapter gate. Player progression only reads its published snapshot. */
public final class JdbcChapterService implements ChapterService {
    private final DataSource dataSource;
    private final Executor executor;
    private final String seasonId;
    private final ChapterDefinition initial;
    private final List<ChapterDefinition> definitions;
    private final Map<String, ChapterDefinition> byId;
    private volatile ChapterState current;

    public JdbcChapterService(
            DataSource dataSource,
            Executor executor,
            String seasonId,
            String initialChapterId,
            List<ChapterDefinition> configuredDefinitions
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.seasonId = normalizeId(seasonId);
        if (configuredDefinitions == null || configuredDefinitions.isEmpty()) {
            throw new IllegalArgumentException("At least one chapter definition is required");
        }
        ArrayList<ChapterDefinition> ordered = new ArrayList<>(configuredDefinitions);
        ordered.sort(Comparator.comparingInt(ChapterDefinition::sequence));
        HashMap<String, ChapterDefinition> indexed = new HashMap<>();
        int previousAscension = -1;
        for (int index = 0; index < ordered.size(); index++) {
            ChapterDefinition definition = ordered.get(index);
            if (definition.sequence() != index || indexed.put(definition.id(), definition) != null
                    || definition.maximumOpenAscension() < previousAscension
                    || definition.maximumOpenAscension() > previousAscension + 1) {
                throw new IllegalArgumentException("Chapters must have unique contiguous sequences and monotonic gates");
            }
            previousAscension = definition.maximumOpenAscension();
        }
        this.definitions = List.copyOf(ordered);
        this.byId = Map.copyOf(indexed);
        this.initial = byId.get(normalizeId(initialChapterId));
        if (initial == null || initial.sequence() != 0 || initial.maximumOpenAscension() != 0) {
            throw new IllegalArgumentException("Initial chapter must be sequence 0 with ascension 0 open");
        }
    }

    public CompletionStage<ChapterState> initialize() {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_chapter_state (
                        singleton_id, season_id, chapter_id, chapter_sequence, max_open_ascension,
                        revision, changed_by, reason
                    ) VALUES (1, ?, ?, ?, ?, 0, 'bootstrap', 'initial chapter')
                    ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, seasonId);
                insert.setString(2, initial.id());
                insert.setInt(3, initial.sequence());
                insert.setInt(4, initial.maximumOpenAscension());
                insert.executeUpdate();
            }
            return load(connection, false);
        })).thenApply(this::publish);
    }

    @Override
    public ChapterState current() {
        ChapterState snapshot = current;
        if (snapshot == null) throw new IllegalStateException("Chapter service is not initialized");
        return snapshot;
    }

    public int maximumOpenAscension() {
        return current().maximumOpenAscension();
    }

    @Override
    public List<ChapterDefinition> definitions() {
        return definitions;
    }

    @Override
    public Optional<ChapterDefinition> next() {
        int sequence = current().sequence() + 1;
        return sequence < definitions.size() ? Optional.of(definitions.get(sequence)) : Optional.empty();
    }

    @Override
    public CompletionStage<ChapterState> refresh() {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> load(connection, false)))
                .thenApply(this::publish);
    }

    @Override
    public CompletionStage<ChapterTransitionResult> advance(
            String targetChapterId, String actor, String reason
    ) {
        String targetId;
        try {
            targetId = normalizeId(targetChapterId);
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(new ChapterTransitionResult(
                    ChapterTransitionResult.Status.INVALID_REQUEST, current()));
        }
        ChapterDefinition target = byId.get(targetId);
        if (target == null) {
            return CompletableFuture.completedFuture(new ChapterTransitionResult(
                    ChapterTransitionResult.Status.UNKNOWN_CHAPTER, current()));
        }
        if (actor == null || actor.isBlank() || actor.length() > 64
                || reason == null || reason.isBlank() || reason.length() < 3 || reason.length() > 128) {
            return CompletableFuture.completedFuture(new ChapterTransitionResult(
                    ChapterTransitionResult.Status.INVALID_REQUEST, current()));
        }
        String safeActor = actor.strip();
        String safeReason = reason.strip();
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            ChapterState existing = load(connection, true);
            if (existing.chapterId().equals(target.id())) {
                return new ChapterTransitionResult(ChapterTransitionResult.Status.ALREADY_CURRENT, existing);
            }
            if (target.sequence() != existing.sequence() + 1
                    || target.maximumOpenAscension() < existing.maximumOpenAscension()) {
                return new ChapterTransitionResult(ChapterTransitionResult.Status.NOT_NEXT_CHAPTER, existing);
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_chapter_state SET chapter_id = ?, chapter_sequence = ?, max_open_ascension = ?,
                        revision = revision + 1, changed_by = ?, reason = ?, changed_at = CURRENT_TIMESTAMP
                    WHERE singleton_id = 1
                    """)) {
                update.setString(1, target.id());
                update.setInt(2, target.sequence());
                update.setInt(3, target.maximumOpenAscension());
                update.setString(4, safeActor);
                update.setString(5, safeReason);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Chapter state disappeared");
            }
            ChapterState updated = load(connection, false);
            try (PreparedStatement history = connection.prepareStatement("""
                    INSERT INTO rpg_chapter_history (
                        id, season_id, chapter_id, chapter_sequence, max_open_ascension,
                        revision, changed_by, reason, changed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                history.setString(1, UUID.randomUUID().toString());
                history.setString(2, updated.seasonId());
                history.setString(3, updated.chapterId());
                history.setInt(4, updated.sequence());
                history.setInt(5, updated.maximumOpenAscension());
                history.setLong(6, updated.revision());
                history.setString(7, safeActor);
                history.setString(8, safeReason);
                history.setTimestamp(9, java.sql.Timestamp.from(updated.changedAt()));
                history.executeUpdate();
            }
            return new ChapterTransitionResult(ChapterTransitionResult.Status.SUCCESS, updated);
        })).thenApply(result -> {
            publish(result.state());
            return result;
        });
    }

    private ChapterState load(Connection connection, boolean lock) throws SQLException {
        String sql = """
                SELECT season_id, chapter_id, chapter_sequence, max_open_ascension, revision, changed_at
                FROM rpg_chapter_state WHERE singleton_id = 1
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) throw new IllegalStateException("Chapter state is missing");
            ChapterState state = new ChapterState(
                    result.getString("season_id"), result.getString("chapter_id"),
                    result.getInt("chapter_sequence"), result.getInt("max_open_ascension"),
                    result.getLong("revision"), result.getTimestamp("changed_at").toInstant());
            validate(state);
            return state;
        }
    }

    private void validate(ChapterState state) {
        ChapterDefinition definition = byId.get(state.chapterId());
        if (!seasonId.equals(state.seasonId()) || definition == null
                || definition.sequence() != state.sequence()
                || definition.maximumOpenAscension() != state.maximumOpenAscension()) {
            throw new IllegalStateException("Database chapter state does not match the configured catalog");
        }
    }

    private ChapterState publish(ChapterState state) {
        validate(state);
        ChapterState previous = current;
        // A stale shard read may never roll the in-memory gate backwards.
        if (previous == null || state.revision() >= previous.revision()) current = state;
        return current;
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
                throw new IllegalStateException("Chapter transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open chapter transaction", exception);
        }
    }

    private static String normalizeId(String value) {
        if (value == null) throw new IllegalArgumentException("id is required");
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{1,31}")) throw new IllegalArgumentException("invalid id");
        return normalized;
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws Exception;
    }
}
