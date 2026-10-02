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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Database-backed ownership for one stable Paper node id.
 *
 * <p>The random process token is a fencing token: a process whose lease has
 * expired can never revive the same token. A replacement process must acquire
 * a new token after expiry, and writes made by the old process then fail
 * closed. PostgreSQL remains the authority; Redis is not involved.</p>
 */
public final class JdbcNodeLease {
    private static final Pattern NODE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private final DataSource dataSource;
    private final Executor executor;
    private final String nodeId;
    private final String processToken;
    private final Duration leaseDuration;
    private final Clock clock;
    private final boolean allowExpiredSameProcessRenewal;
    private final AtomicReference<Instant> knownValidUntil = new AtomicReference<>(Instant.EPOCH);

    public JdbcNodeLease(DataSource dataSource, Executor executor, String nodeId, Duration leaseDuration) {
        this(dataSource, executor, nodeId, UUID.randomUUID(), leaseDuration, Clock.systemUTC(), false);
    }

    public JdbcNodeLease(
            DataSource dataSource, Executor executor, String nodeId, Duration leaseDuration,
            boolean allowExpiredSameProcessRenewal
    ) {
        this(dataSource, executor, nodeId, UUID.randomUUID(), leaseDuration, Clock.systemUTC(),
                allowExpiredSameProcessRenewal);
    }

    JdbcNodeLease(
            DataSource dataSource, Executor executor, String nodeId, UUID processToken,
            Duration leaseDuration, Clock clock
    ) {
        this(dataSource, executor, nodeId, processToken, leaseDuration, clock, false);
    }

    JdbcNodeLease(
            DataSource dataSource, Executor executor, String nodeId, UUID processToken,
            Duration leaseDuration, Clock clock, boolean allowExpiredSameProcessRenewal
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nodeId = validateNodeId(nodeId);
        this.processToken = Objects.requireNonNull(processToken, "processToken").toString();
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowExpiredSameProcessRenewal = allowExpiredSameProcessRenewal;
        if (leaseDuration.compareTo(Duration.ofSeconds(5)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("leaseDuration must be between 5 seconds and 5 minutes");
        }
    }

    public String nodeId() {
        return nodeId;
    }

    public String processToken() {
        return processToken;
    }

    Instant now() {
        return clock.instant();
    }

    /**
     * Acquires this stable node id, but never steals an unexpired process.
     */
    public boolean acquire() {
        return acquire(false);
    }

    /**
     * Acquires the lease, optionally replacing an unexpired token only when an
     * external single-process authority has already excluded the old owner.
     * This is safe for file-backed H2 because opening the database proves its
     * OS file lock is no longer held. PostgreSQL callers must pass false.
     */
    public boolean acquire(boolean permitExternallyFencedTakeover) {
        Instant now = now();
        Instant expiresAt = now.plus(leaseDuration);
        return inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_node_leases
                        (node_id, process_token, lease_expires_at)
                    VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, nodeId);
                insert.setString(2, processToken);
                insert.setTimestamp(3, Timestamp.from(expiresAt));
                insert.executeUpdate();
            }
            LeaseOwner current = ownerForUpdate(connection, nodeId);
            if (current == null) throw new IllegalStateException("Node lease disappeared during acquisition");
            if (!current.processToken().equals(processToken)
                    && current.expiresAt().isAfter(now)
                    && !permitExternallyFencedTakeover) return false;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_node_leases
                    SET process_token = ?, lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE node_id = ?
                    """)) {
                update.setString(1, processToken);
                update.setTimestamp(2, Timestamp.from(expiresAt));
                update.setString(3, nodeId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Node lease acquisition changed");
            }
            return true;
        }, acquired -> {
            if (acquired) knownValidUntil.set(expiresAt);
        });
    }

    /**
     * Renews only a still-live token. Once expiry is crossed, this token is
     * permanently fenced and a new process must acquire the node id.
     */
    public CompletionStage<Boolean> renewAsync() {
        return AsyncJdbc.supply(executor, this::renew);
    }

    public boolean renew() {
        Instant now = now();
        Instant expiresAt = now.plus(leaseDuration);
        boolean renewed = inTransaction(connection -> {
            String expiryFence = allowExpiredSameProcessRenewal ? "" : " AND lease_expires_at > ?";
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_node_leases
                    SET lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE node_id = ? AND process_token = ?
                    """ + expiryFence)) {
                update.setTimestamp(1, Timestamp.from(expiresAt));
                update.setString(2, nodeId);
                update.setString(3, processToken);
                if (!allowExpiredSameProcessRenewal) update.setTimestamp(4, Timestamp.from(now));
                return update.executeUpdate() == 1;
            }
        }, ignored -> { });
        if (renewed) knownValidUntil.set(expiresAt);
        else knownValidUntil.set(Instant.EPOCH);
        return renewed;
    }

    public boolean mayStillBeValid() {
        return knownValidUntil.get().isAfter(now());
    }

    /** True only when the current token outlives the complete retry budget. */
    public boolean mayStillBeValidFor(Duration retryBudget) {
        Objects.requireNonNull(retryBudget, "retryBudget");
        if (retryBudget.isNegative()) throw new IllegalArgumentException("retryBudget must not be negative");
        return knownValidUntil.get().isAfter(now().plus(retryBudget));
    }

    /** Releases only this process token; it cannot delete a replacement. */
    public boolean release() {
        knownValidUntil.set(Instant.EPOCH);
        return inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM rpg_node_leases WHERE node_id = ? AND process_token = ?
                    """)) {
                delete.setString(1, nodeId);
                delete.setString(2, processToken);
                return delete.executeUpdate() == 1;
            }
        }, ignored -> { });
    }

    /**
     * Locks and verifies the caller's fencing token inside a gameplay
     * transaction. This establishes the lock order lease -> profile/item.
     */
    void requireOwned(Connection connection, Instant now) throws SQLException {
        LeaseOwner owner = ownerForUpdate(connection, nodeId);
        if (owner == null || !owner.processToken().equals(processToken) || !owner.expiresAt().isAfter(now)) {
            knownValidUntil.set(Instant.EPOCH);
            throw new IllegalStateException("Paper node lease was lost for " + nodeId);
        }
    }

    /**
     * Checks another reservation owner while holding its node row lock. If it
     * is expired, a concurrent heartbeat cannot revive that fenced token.
     */
    boolean ownerIsActiveForUpdate(
            Connection connection, String ownerNode, String ownerToken, Instant now
    ) throws SQLException {
        if (ownerNode == null || ownerToken == null) return false;
        LeaseOwner owner = ownerForUpdate(connection, ownerNode);
        return owner != null && owner.processToken().equals(ownerToken) && owner.expiresAt().isAfter(now);
    }

    private LeaseOwner ownerForUpdate(Connection connection, String requestedNode) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT process_token, lease_expires_at
                FROM rpg_node_leases WHERE node_id = ? FOR UPDATE
                """)) {
            query.setString(1, requestedNode);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                Timestamp expiresAt = result.getTimestamp("lease_expires_at");
                return new LeaseOwner(result.getString("process_token"), expiresAt.toInstant());
            }
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation, java.util.function.Consumer<T> committed) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T value = operation.apply(connection);
                connection.commit();
                committed.accept(value);
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Node lease transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open node lease transaction", exception);
        }
    }

    private static String validateNodeId(String value) {
        String normalized = Objects.requireNonNull(value, "nodeId").trim().toLowerCase(java.util.Locale.ROOT);
        if (!NODE_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("nodeId must match " + NODE_ID.pattern());
        }
        return normalized;
    }

    private record LeaseOwner(String processToken, Instant expiresAt) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
