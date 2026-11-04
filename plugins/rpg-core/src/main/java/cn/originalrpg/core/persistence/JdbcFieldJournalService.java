package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.FieldJournalEntry;
import cn.originalrpg.api.model.FieldJournalRecord;
import cn.originalrpg.api.model.FieldJournalUpdate;
import cn.originalrpg.api.service.FieldJournalService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** PostgreSQL/H2 journal receipts; completion facts never enter an economic ledger. */
public final class JdbcFieldJournalService implements FieldJournalService {
    private final DataSource dataSource;
    private final Executor executor;

    public JdbcFieldJournalService(DataSource dataSource, Executor executor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<List<FieldJournalRecord>> records(UUID playerId) {
        if (playerId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerId is required"));
        return AsyncJdbc.supply(executor, () -> transaction(connection -> load(connection, playerId)));
    }

    @Override
    public CompletionStage<FieldJournalUpdate> recordCompleted(UUID playerId, Set<FieldJournalEntry> completed) {
        if (playerId == null || completed == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("playerId and completed are required"));
        }
        final Set<FieldJournalEntry> verified;
        try {
            verified = Set.copyOf(completed);
        } catch (NullPointerException exception) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Completed entries must not contain null", exception));
        }
        return AsyncJdbc.supply(executor, () -> transaction(connection -> {
            Set<FieldJournalEntry> inserted = EnumSet.noneOf(FieldJournalEntry.class);
            // Enum order gives concurrent multi-entry transactions one consistent lock order.
            for (FieldJournalEntry entry : FieldJournalEntry.values()) {
                if (!verified.contains(entry)) continue;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO rpg_field_journal_entries (player_uuid, entry_id, advancement_key)
                        VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                        """)) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, entry.id());
                    statement.setString(3, entry.advancementKey());
                    if (statement.executeUpdate() == 1) inserted.add(entry);
                }
            }
            return new FieldJournalUpdate(load(connection, playerId), inserted);
        }));
    }

    private static List<FieldJournalRecord> load(Connection connection, UUID playerId) throws SQLException {
        List<FieldJournalRecord> records = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT entry_id, advancement_key, recorded_at
                FROM rpg_field_journal_entries WHERE player_uuid = ?
                ORDER BY recorded_at, entry_id
                """)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString("entry_id");
                    FieldJournalEntry entry = FieldJournalEntry.fromId(id)
                            .orElseThrow(() -> new IllegalStateException("Unknown journal receipt: " + id));
                    if (!entry.advancementKey().equals(result.getString("advancement_key"))) {
                        throw new IllegalStateException("Journal evidence key does not match entry " + id);
                    }
                    records.add(new FieldJournalRecord(entry, result.getTimestamp("recorded_at").toInstant()));
                }
            }
        }
        return List.copyOf(records);
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Field journal persistence failed", exception);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
