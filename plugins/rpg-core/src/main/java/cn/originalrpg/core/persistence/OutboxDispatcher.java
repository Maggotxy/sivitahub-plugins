package cn.originalrpg.core.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bounded, recoverable transactional-outbox worker. Run it off the Paper main
 * thread. Claims use row locks with SKIP LOCKED, so competing processes never
 * deliberately handle the same live claim. A crashed claim becomes eligible
 * after its deadline; every retry receives a different token.
 *
 * <p>A transactional handler's SQL effects and processed marker commit together.
 * External side effects, including chat or remote HTTP calls, still require an
 * idempotent recipient: a database cannot atomically commit a network response.</p>
 */
public final class OutboxDispatcher {
    private static final Logger LOGGER = Logger.getLogger(OutboxDispatcher.class.getName());
    private final DataSource dataSource;
    private final Map<String, OutboxHandler> handlers;
    private final Duration claimDuration;
    private final Duration initialBackoff;
    private final Clock testClock;
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deferred = new AtomicLong();

    public OutboxDispatcher(DataSource dataSource, List<OutboxHandler> handlers) {
        this(dataSource, handlers, Duration.ofSeconds(30), Duration.ofSeconds(2), null);
    }

    OutboxDispatcher(DataSource dataSource, List<OutboxHandler> handlers,
                     Duration claimDuration, Duration initialBackoff, Clock testClock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.claimDuration = Objects.requireNonNull(claimDuration, "claimDuration");
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
        this.testClock = testClock;
        if (claimDuration.compareTo(Duration.ofSeconds(1)) < 0
                || claimDuration.compareTo(Duration.ofMinutes(5)) > 0
                || initialBackoff.compareTo(Duration.ofMillis(100)) < 0
                || initialBackoff.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid outbox claim or retry duration");
        }
        Map<String, OutboxHandler> byType = new LinkedHashMap<>();
        for (OutboxHandler handler : Objects.requireNonNull(handlers, "handlers")) {
            Objects.requireNonNull(handler, "handler");
            if (byType.put(handler.aggregateType(), handler) != null) {
                throw new IllegalArgumentException("Duplicate outbox handler for " + handler.aggregateType());
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    public Map<String, OutboxHandler> handlers() { return handlers; }
    public Metrics metrics() { return new Metrics(delivered.get(), failed.get(), deferred.get()); }

    /** Claims at most 512 events. A slow/failed event cannot monopolize the queue's first page. */
    public DispatchResult dispatchBatch(int maximumEvents) {
        if (maximumEvents <= 0) return new DispatchResult(0, 0, 0, 0);
        List<Claim> claims = claimBatch(Math.min(maximumEvents, 512));
        int sent = 0;
        int retried = 0;
        int skipped = 0;
        for (Claim claim : claims) {
            OutboxHandler handler = handlers.get(claim.event().aggregateType());
            try {
                if (handler == null) throw new IllegalStateException(
                        "no handler for aggregate type " + claim.event().aggregateType());
                Outcome outcome = process(claim, handler);
                if (outcome == Outcome.DELIVERED) {
                    delivered.incrementAndGet();
                    sent++;
                } else {
                    if (outcome == Outcome.DEFERRED) retry(claim, "handler deferred");
                    deferred.incrementAndGet();
                    skipped++;
                }
            } catch (RuntimeException exception) {
                failed.incrementAndGet();
                retried++;
                try { retry(claim, summarize(exception)); }
                catch (RuntimeException retryFailure) { exception.addSuppressed(retryFailure); }
                LOGGER.log(Level.WARNING, "Outbox delivery failed for event " + claim.event().eventId()
                        + " (" + claim.event().aggregateType() + ")", exception);
            }
        }
        return new DispatchResult(claims.size(), sent, retried, skipped);
    }

    // Package-visible for fault tests that simulate a worker dying after claim commit.
    List<Claim> claimBatch(int limit) {
        if (limit < 1 || limit > 512) throw new IllegalArgumentException("Outbox batch must be 1..512");
        return transaction(connection -> {
            Instant now = now(connection);
            List<OutboxEvent> events = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT event_id, aggregate_type, aggregate_id, idempotency_key, payload, attempts, created_at
                    FROM rpg_outbox
                    WHERE processed_at IS NULL AND next_attempt_at <= ?
                      AND (claim_expires_at IS NULL OR claim_expires_at <= ?)
                    ORDER BY next_attempt_at, created_at, event_id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                    """)) {
                query.setTimestamp(1, Timestamp.from(now));
                query.setTimestamp(2, Timestamp.from(now));
                query.setInt(3, limit);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) events.add(new OutboxEvent(result.getString("event_id"),
                            result.getString("aggregate_type"), result.getString("aggregate_id"),
                            result.getString("idempotency_key"), result.getString("payload"),
                            result.getInt("attempts"), result.getTimestamp("created_at").toInstant(), null));
                }
            }
            List<Claim> claims = new ArrayList<>();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_outbox SET claim_token = ?, claim_expires_at = ?, attempts = attempts + 1
                    WHERE event_id = ? AND processed_at IS NULL
                    """)) {
                for (OutboxEvent event : events) {
                    UUID token = UUID.randomUUID();
                    Instant expires = now.plus(claimDuration);
                    update.setString(1, token.toString());
                    update.setTimestamp(2, Timestamp.from(expires));
                    update.setString(3, event.eventId());
                    if (update.executeUpdate() != 1) throw new SQLException("Claimed outbox row disappeared");
                    claims.add(new Claim(event, token, expires));
                }
            }
            return List.copyOf(claims);
        });
    }

    Outcome process(Claim claim, OutboxHandler handler) {
        return transaction(connection -> {
            Instant deadline;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT claim_expires_at FROM rpg_outbox
                    WHERE event_id = ? AND claim_token = ? AND processed_at IS NULL FOR UPDATE
                    """)) {
                query.setString(1, claim.event().eventId());
                query.setString(2, claim.token().toString());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) return Outcome.STALE;
                    deadline = result.getTimestamp(1).toInstant();
                }
            }
            if (!deadline.isAfter(now(connection))) return Outcome.STALE;
            if (!handler.handle(connection, claim.event())) {
                // A deferring handler may have written provisionally. It is not
                // allowed to commit those writes without the processed marker.
                connection.rollback();
                return Outcome.DEFERRED;
            }
            Instant completed = now(connection);
            if (!deadline.isAfter(completed)) throw new IllegalStateException("Outbox claim expired during handling");
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_outbox SET processed_at = ?, last_error = NULL,
                        claim_token = NULL, claim_expires_at = NULL
                    WHERE event_id = ? AND claim_token = ? AND processed_at IS NULL
                    """)) {
                update.setTimestamp(1, Timestamp.from(completed));
                update.setString(2, claim.event().eventId());
                update.setString(3, claim.token().toString());
                if (update.executeUpdate() != 1) throw new SQLException("Outbox claim changed during handling");
            }
            return Outcome.DELIVERED;
        });
    }

    private void retry(Claim claim, String reason) {
        transaction(connection -> {
            long multiplier = 1L << Math.min(claim.event().attempts(), 16);
            long delayMillis = Math.min(Duration.ofMinutes(5).toMillis(),
                    Math.multiplyExact(initialBackoff.toMillis(), multiplier));
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_outbox SET next_attempt_at = ?, last_error = ?,
                        claim_token = NULL, claim_expires_at = NULL
                    WHERE event_id = ? AND claim_token = ? AND processed_at IS NULL
                    """)) {
                update.setTimestamp(1, Timestamp.from(now(connection).plusMillis(delayMillis)));
                String message = reason == null ? "" : reason;
                update.setString(2, message.substring(0, Math.min(message.length(), 240)));
                update.setString(3, claim.event().eventId());
                update.setString(4, claim.token().toString());
                update.executeUpdate(); // A replaced token belongs to another worker; leave it alone.
            }
            return null;
        });
    }

    private Instant now(Connection connection) throws SQLException {
        if (testClock != null) return testClock.instant();
        String database = connection.getMetaData().getDatabaseProductName();
        if (database.equals("H2")) return Instant.now();
        if (!database.equals("PostgreSQL")) throw new SQLException("Unsupported outbox database: " + database);
        try (PreparedStatement query = connection.prepareStatement("SELECT clock_timestamp()");
             ResultSet result = query.executeQuery()) {
            if (!result.next()) throw new SQLException("Database clock returned no timestamp");
            return result.getTimestamp(1).toInstant();
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Outbox transaction failed", error);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not open outbox transaction", error);
        }
    }

    private static String summarize(RuntimeException exception) {
        return exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    record Claim(OutboxEvent event, UUID token, Instant expiresAt) { }
    enum Outcome { DELIVERED, DEFERRED, STALE }
    public record DispatchResult(int claimed, int delivered, int retried, int deferred) { }
    public record Metrics(long delivered, long failed, long deferred) { }

    /** A bounded background consumer. Never call Bukkit/player APIs from these methods. */
    public interface OutboxHandler {
        String aggregateType();

        /**
         * Legacy external-effect adapter. The recipient MUST deduplicate by
         * event ID because an ambiguous commit can cause delivery again.
         */
        default boolean handle(OutboxEvent event) {
            throw new UnsupportedOperationException("This handler requires the dispatcher transaction");
        }

        /**
         * Apply SQL effects using only this connection; never commit, change its
         * auto-commit mode, close it, or start another database transaction.
         * false rolls back provisional effects and schedules a later retry.
         */
        default boolean handle(Connection connection, OutboxEvent event) throws SQLException {
            return handle(event);
        }
    }
}
