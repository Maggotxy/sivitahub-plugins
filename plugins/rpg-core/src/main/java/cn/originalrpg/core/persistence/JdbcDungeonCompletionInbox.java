package cn.originalrpg.core.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Materializes committed dungeon-completion events into a durable notification
 * inbox. The reward receipt is the authority; this projection cannot mint XP,
 * currency, or item deliveries. Consumer effects and the outbox processed marker
 * share one transaction. Notification reads/acks use the bounded SQL executor.
 */
public final class JdbcDungeonCompletionInbox implements OutboxDispatcher.OutboxHandler {
    private final DataSource dataSource;
    private final Executor executor;

    public JdbcDungeonCompletionInbox(DataSource dataSource, Executor executor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override public String aggregateType() { return "dungeon.completion"; }

    @Override
    public boolean handle(Connection connection, OutboxEvent event) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("Completion projection requires a transaction");
        DungeonCompletionPayload payload = DungeonCompletionPayload.parse(event);
        int deliveries;
        try (PreparedStatement receipt = connection.prepareStatement("""
                SELECT dungeon_id, queued_deliveries FROM rpg_dungeon_reward_receipts
                WHERE run_id = ? AND player_uuid = ?
                """)) {
            receipt.setString(1, payload.runId().toString());
            receipt.setString(2, payload.playerId().toString());
            try (ResultSet result = receipt.executeQuery()) {
                if (!result.next() || !payload.dungeonId().equals(result.getString("dungeon_id"))) {
                    throw new SQLException("Dungeon event has no matching committed reward receipt");
                }
                deliveries = result.getInt("queued_deliveries");
                if (deliveries < 0) throw new SQLException("Invalid dungeon reward delivery count");
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_dungeon_completion_inbox
                    (event_id, run_id, player_uuid, dungeon_id, queued_deliveries)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, event.eventId());
            insert.setString(2, payload.runId().toString());
            insert.setString(3, payload.playerId().toString());
            insert.setString(4, payload.dungeonId());
            insert.setInt(5, deliveries);
            if (insert.executeUpdate() == 1) return true;
        }
        // The same run/player may exist under a legacy event ID. It is a safe
        // replay only if the durable projection agrees with the reward receipt.
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT dungeon_id, queued_deliveries FROM rpg_dungeon_completion_inbox
                WHERE run_id = ? AND player_uuid = ?
                """)) {
            query.setString(1, payload.runId().toString());
            query.setString(2, payload.playerId().toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !payload.dungeonId().equals(result.getString("dungeon_id"))
                        || deliveries != result.getInt("queued_deliveries")) {
                    throw new SQLException("Dungeon notification identity was reused with different content");
                }
            }
        }
        return true;
    }

    public CompletionStage<List<Notice>> unread(UUID playerId, int limit) {
        Objects.requireNonNull(playerId, "playerId");
        if (limit < 1 || limit > 32) throw new IllegalArgumentException("Inbox page must be 1..32");
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement query = connection.prepareStatement("""
                         SELECT event_id, run_id, dungeon_id, queued_deliveries
                         FROM rpg_dungeon_completion_inbox
                         WHERE player_uuid = ? AND notified_at IS NULL
                         ORDER BY created_at, event_id LIMIT ?
                         """)) {
                query.setString(1, playerId.toString());
                query.setInt(2, limit);
                List<Notice> notices = new ArrayList<>();
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) notices.add(new Notice(UUID.fromString(result.getString("event_id")),
                            UUID.fromString(result.getString("run_id")), playerId,
                            result.getString("dungeon_id"), result.getInt("queued_deliveries")));
                }
                connection.rollback();
                return List.copyOf(notices);
            } catch (SQLException error) { throw new IllegalStateException("Could not read dungeon notifications", error); }
        });
    }

    /** Acks only a notice already sent to the same authenticated connection. */
    public CompletionStage<Boolean> acknowledge(UUID eventId, UUID playerId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(playerId, "playerId");
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE rpg_dungeon_completion_inbox SET notified_at = CURRENT_TIMESTAMP
                        WHERE event_id = ? AND player_uuid = ? AND notified_at IS NULL
                        """)) {
                    update.setString(1, eventId.toString());
                    update.setString(2, playerId.toString());
                    boolean changed = update.executeUpdate() == 1;
                    connection.commit();
                    return changed;
                } catch (SQLException | RuntimeException error) {
                    connection.rollback();
                    throw error;
                }
            } catch (SQLException error) { throw new IllegalStateException("Could not acknowledge dungeon notice", error); }
        });
    }

    public record Notice(UUID eventId, UUID runId, UUID playerId, String dungeonId, int queuedDeliveries) { }
}
