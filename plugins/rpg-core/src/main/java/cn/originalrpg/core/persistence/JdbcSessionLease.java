package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PlayerSessionHandoff;
import cn.originalrpg.api.model.PlayerSessionLease;
import cn.originalrpg.api.service.PlayerSessionService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Database authority for one live connection per UUID, including a monotonic
 * fence and an explicit source-fenced handoff. No inventory or wallet is copied.
 * PostgreSQL checks use its actual clock after row locks, not transaction-start
 * CURRENT_TIMESTAMP or a remote JVM's wall clock. H2 is a local-only authority.
 */
public final class JdbcSessionLease implements PlayerSessionService {
    private final DataSource dataSource;
    private final Executor executor;
    private final String nodeId;
    private final String processToken;
    private final Duration duration;
    private final Clock testClock;
    private final Map<UUID, PlayerSessionLease> local = new ConcurrentHashMap<>();

    /** Read-only compatibility constructor; lifecycle writes require a bounded executor. */
    public JdbcSessionLease(DataSource dataSource, String nodeId, String processToken) {
        this(dataSource, null, nodeId, processToken, Duration.ofSeconds(60), null);
    }

    /** Read-only constructor with a deterministic clock for historical admission tests. */
    public JdbcSessionLease(DataSource dataSource, String nodeId, String processToken, Clock clock) {
        this(dataSource, null, nodeId, processToken, Duration.ofSeconds(60), clock);
    }

    public JdbcSessionLease(DataSource dataSource, Executor executor, String nodeId,
                            String processToken, Duration duration) {
        this(dataSource, executor, nodeId, processToken, duration, null);
    }

    JdbcSessionLease(DataSource dataSource, Executor executor, String nodeId,
                     String processToken, Duration duration, Clock testClock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = executor;
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.processToken = Objects.requireNonNull(processToken, "processToken");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.testClock = testClock;
        if (!nodeId.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || duration.compareTo(Duration.ofSeconds(5)) < 0
                || duration.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid node ID or session lease duration");
        }
        if (executor != null) UUID.fromString(processToken);
    }

    public String nodeId() { return nodeId; }
    public Duration duration() { return duration; }

    @Override
    public Optional<PlayerSessionLease> current(UUID playerId) {
        return Optional.ofNullable(local.get(Objects.requireNonNull(playerId, "playerId")));
    }

    @Override
    public CompletionStage<Optional<PlayerSessionLease>> acquire(UUID playerId, UUID connectionToken) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionToken, "connectionToken");
        return submit(() -> {
            Optional<PlayerSessionLease> acquired = ownedTransaction(connection -> {
                requireLocalProcess(connection);
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO rpg_session_leases
                            (player_uuid, node_id, node_process_token, session_token, fence,
                             session_state, lease_expires_at)
                        VALUES (?, ?, ?, ?, 0, 'RELEASED', ?) ON CONFLICT DO NOTHING
                        """)) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, nodeId);
                    insert.setString(3, processToken);
                    insert.setString(4, connectionToken.toString());
                    insert.setTimestamp(5, Timestamp.from(Instant.EPOCH));
                    insert.executeUpdate();
                }
                Row row = row(connection, playerId, true);
                Instant now = now(connection);
                if (row == null) throw new IllegalStateException("Player session row disappeared");
                boolean sameConnection = nodeId.equals(row.node()) && processToken.equals(row.process())
                        && connectionToken.toString().equals(row.session());
                if (row.expiresAt().isAfter(now) && !row.state().equals("RELEASED")) {
                    return sameConnection && row.state().equals("ACTIVE") && row.fence() > 0
                            ? Optional.of(row.lease(playerId)) : Optional.empty();
                }
                // Never revive an expired or released connection token, even on
                // the same process. A fresh connection must receive a new epoch.
                if (row.fence() > 0 && connectionToken.toString().equals(row.session())) return Optional.empty();
                long fence = Math.addExact(row.fence(), 1L);
                Instant expires = leaseExpiry(now);
                assign(connection, playerId, connectionToken, fence, expires, null);
                return Optional.of(new PlayerSessionLease(playerId, nodeId, processToken,
                        connectionToken, fence, expires));
            });
            acquired.ifPresent(this::remember);
            return acquired;
        });
    }

    @Override
    public CompletionStage<Optional<PlayerSessionLease>> renew(PlayerSessionLease lease) {
        requireLocalToken(lease);
        return submit(() -> {
            Optional<PlayerSessionLease> result = ownedTransaction(connection -> {
                requireLocalProcess(connection);
                Row row = row(connection, lease.playerId(), true);
                Instant now = now(connection);
                if (!matches(row, lease) || !row.state().equals("ACTIVE") || !row.expiresAt().isAfter(now)) {
                    return Optional.empty();
                }
                Instant expires = leaseExpiry(now);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE rpg_session_leases SET lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE player_uuid = ?
                        """)) {
                    update.setTimestamp(1, Timestamp.from(expires));
                    update.setString(2, lease.playerId().toString());
                    requireOne(update.executeUpdate());
                }
                return Optional.of(new PlayerSessionLease(lease.playerId(), nodeId, processToken,
                        lease.sessionToken(), lease.fence(), expires));
            });
            if (result.isEmpty()) forget(lease);
            else local.computeIfPresent(lease.playerId(), (id, current) ->
                    current.sameAuthority(lease) ? result.orElseThrow() : current);
            return result;
        });
    }

    @Override
    public CompletionStage<Boolean> release(PlayerSessionLease lease) {
        requireLocalToken(lease);
        forget(lease);
        return submit(() -> transaction(connection -> {
            // Release may run after the node itself is fenced. It is a conditional
            // revocation, cannot mint authority, and cannot touch a replacement.
            Row row = row(connection, lease.playerId(), true);
            if (row != null && sameIdentity(row, lease) && row.state().equals("RELEASED")
                    && row.fence() == lease.fence() + 1) return true;
            if (!matches(row, lease) || !row.state().equals("ACTIVE")) return false;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_session_leases
                    SET session_state = 'RELEASED', fence = ?, lease_expires_at = ?,
                        handoff_id = NULL, handoff_target = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ?
                    """)) {
                update.setLong(1, Math.addExact(row.fence(), 1L));
                update.setTimestamp(2, Timestamp.from(now(connection)));
                update.setString(3, lease.playerId().toString());
                requireOne(update.executeUpdate());
            }
            return true;
        }));
    }

    @Override
    public CompletionStage<Optional<PlayerSessionHandoff>> prepareHandoff(
            PlayerSessionLease lease, String targetNodeId, UUID handoffId) {
        requireLocalToken(lease);
        Objects.requireNonNull(handoffId, "handoffId");
        if (targetNodeId == null || !targetNodeId.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || targetNodeId.equals(nodeId)) throw new IllegalArgumentException("Invalid handoff target");
        // Quiesce this process before the database reservation can be observed.
        forget(lease);
        return submit(() -> ownedTransaction(connection -> {
            requireLocalProcess(connection);
            Row row = row(connection, lease.playerId(), true);
            Instant now = now(connection);
            if (row != null && sameIdentity(row, lease) && row.state().equals("HANDOFF")
                    && handoffId.toString().equals(row.handoff()) && targetNodeId.equals(row.target())
                    && row.fence() == lease.fence() + 1 && row.expiresAt().isAfter(now)) {
                return Optional.of(row.handoff(lease.playerId()));
            }
            if (!matches(row, lease) || !row.state().equals("ACTIVE") || !row.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            long fence = Math.addExact(row.fence(), 1L);
            Instant expires = leaseExpiry(now);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_session_leases
                    SET session_state = 'HANDOFF', fence = ?, handoff_id = ?, handoff_target = ?,
                        lease_expires_at = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?
                    """)) {
                update.setLong(1, fence);
                update.setString(2, handoffId.toString());
                update.setString(3, targetNodeId);
                update.setTimestamp(4, Timestamp.from(expires));
                update.setString(5, lease.playerId().toString());
                requireOne(update.executeUpdate());
            }
            return Optional.of(new PlayerSessionHandoff(lease.playerId(), handoffId, nodeId,
                    processToken, targetNodeId, fence, expires));
        }));
    }

    @Override
    public CompletionStage<Optional<PlayerSessionLease>> acceptHandoff(
            PlayerSessionHandoff handoff, UUID connectionToken) {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(connectionToken, "connectionToken");
        if (!nodeId.equals(handoff.targetNodeId())) return CompletableFuture.completedFuture(Optional.empty());
        return transitionHandoff(handoff, connectionToken, false);
    }

    @Override
    public CompletionStage<Optional<PlayerSessionLease>> cancelHandoff(
            PlayerSessionHandoff handoff, UUID newConnectionToken) {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(newConnectionToken, "newConnectionToken");
        if (!nodeId.equals(handoff.sourceNodeId()) || !processToken.equals(handoff.sourceProcessToken())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return transitionHandoff(handoff, newConnectionToken, true);
    }

    private CompletionStage<Optional<PlayerSessionLease>> transitionHandoff(
            PlayerSessionHandoff handoff, UUID connectionToken, boolean cancel) {
        return submit(() -> {
            Optional<PlayerSessionLease> result = ownedTransaction(connection -> {
                requireLocalProcess(connection);
                Row row = row(connection, handoff.playerId(), true);
                Instant now = now(connection);
                if (row == null || !handoff.handoffId().toString().equals(row.handoff())) return Optional.empty();
                // A lost response is retried with exactly the same new token.
                if (row.state().equals("ACTIVE") && row.fence() == handoff.fence() + 1
                        && nodeId.equals(row.node()) && processToken.equals(row.process())
                        && connectionToken.toString().equals(row.session()) && row.expiresAt().isAfter(now)) {
                    return Optional.of(row.lease(handoff.playerId()));
                }
                if (!row.state().equals("HANDOFF") || row.fence() != handoff.fence()
                        || !handoff.sourceNodeId().equals(row.node())
                        || !handoff.sourceProcessToken().equals(row.process())
                        || !handoff.targetNodeId().equals(row.target())
                        || (!cancel && !row.expiresAt().isAfter(now))
                        || connectionToken.toString().equals(row.session())) return Optional.empty();
                long fence = Math.addExact(row.fence(), 1L);
                Instant expires = leaseExpiry(now);
                assign(connection, handoff.playerId(), connectionToken, fence, expires, handoff.handoffId());
                return Optional.of(new PlayerSessionLease(handoff.playerId(), nodeId, processToken,
                        connectionToken, fence, expires));
            });
            result.ifPresent(this::remember);
            return result;
        });
    }

    @Override
    public <T> CompletionStage<T> fenced(PlayerSessionLease actor, Supplier<CompletionStage<T>> invocation) {
        Objects.requireNonNull(invocation, "invocation");
        try {
            requireLocalToken(actor);
            SessionWriteContext.Scope inherited = SessionWriteContext.current();
            if (inherited != null && !inherited.actor().sameAuthority(actor)) {
                throw new IllegalStateException("A nested invocation cannot replace captured session authority");
            }
            if (!current(actor.playerId()).map(actor::sameAuthority).orElse(false)) {
                throw new IllegalStateException("The original player connection is no longer admitted");
            }
            return SessionWriteContext.with(new SessionWriteContext.Scope(this, actor), invocation);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * Admits an already acquired connection into the nonblocking local cache.
     * The authentication adapter calls this only after authentication succeeds.
     * This cache grants no database authority: every protected transaction still
     * locks and checks the persisted token, fence, process, and expiration.
     * An older asynchronous response can never replace a newer connection.
     */
    public void remember(PlayerSessionLease lease) {
        requireLocalToken(lease);
        local.compute(lease.playerId(), (id, current) -> current == null
                || lease.fence() > current.fence()
                || (current.sameAuthority(lease) && lease.expiresAt().isAfter(current.expiresAt()))
                ? lease : current);
    }

    /** Nonblocking local revocation for a lost connection; SQL release may follow asynchronously. */
    public void forget(PlayerSessionLease lease) {
        local.computeIfPresent(lease.playerId(), (id, current) -> current.sameAuthority(lease) ? null : current);
    }

    /** Snapshot used by the lifecycle supervisor, never as transaction authorization. */
    public Map<UUID, PlayerSessionLease> localLeases() { return Map.copyOf(local); }

    /** Same-connection guard; caller must keep the row lock until commit/rollback. */
    void requireOwned(Connection connection, PlayerSessionLease lease) throws SQLException {
        requireLocalToken(lease);
        if (connection.getAutoCommit()) throw new SQLException("Session fencing requires an explicit transaction");
        requireLocalProcess(connection);
        Row row = row(connection, lease.playerId(), true);
        if (!matches(row, lease) || !row.state().equals("ACTIVE") || !row.expiresAt().isAfter(now(connection))) {
            throw new IllegalStateException("Player session authority was lost for " + lease.playerId());
        }
    }

    /** Read-only snapshot retained for administration and compatibility. */
    public Optional<Holder> holder(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return transaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT node_id, node_process_token, lease_expires_at, session_state
                    FROM rpg_session_leases WHERE player_uuid = ?
                    """)) {
                query.setString(1, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next() || !"ACTIVE".equals(result.getString("session_state"))) return Optional.empty();
                    Instant expires = result.getTimestamp("lease_expires_at").toInstant();
                    if (!expires.isAfter(now(connection))) return Optional.empty();
                    return Optional.of(new Holder(result.getString("node_id"),
                            result.getString("node_process_token"), expires));
                }
            }
        });
    }

    public boolean isLocalNodeHolder(UUID playerId) {
        return holder(playerId).map(value -> nodeId.equals(value.nodeId())
                && processToken.equals(value.processToken())).orElse(false);
    }

    public Admission admit(UUID playerId) {
        Optional<Holder> holder = holder(playerId);
        if (holder.isEmpty()) return Admission.NO_VALID_LEASE;
        if (!nodeId.equals(holder.orElseThrow().nodeId())) return Admission.HELD_BY_OTHER_NODE;
        return processToken.equals(holder.orElseThrow().processToken()) ? Admission.ALLOWED : Admission.STALE_PROCESS_TOKEN;
    }

    private void assign(Connection connection, UUID playerId, UUID token, long fence,
                        Instant expires, UUID handoffId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_session_leases
                SET node_id = ?, node_process_token = ?, session_token = ?, fence = ?, session_state = 'ACTIVE',
                    lease_expires_at = ?, handoff_id = ?, handoff_target = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ?
                """)) {
            update.setString(1, nodeId);
            update.setString(2, processToken);
            update.setString(3, token.toString());
            update.setLong(4, fence);
            update.setTimestamp(5, Timestamp.from(expires));
            update.setString(6, handoffId == null ? null : handoffId.toString());
            update.setString(7, playerId.toString());
            requireOne(update.executeUpdate());
        }
    }

    private Row row(Connection connection, UUID playerId, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT node_id, node_process_token, session_token, fence, session_state,
                       lease_expires_at, handoff_id, handoff_target
                FROM rpg_session_leases WHERE player_uuid = ?
                """ + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new Row(result.getString("node_id"), result.getString("node_process_token"),
                        result.getString("session_token"), result.getLong("fence"), result.getString("session_state"),
                        result.getTimestamp("lease_expires_at").toInstant(), result.getString("handoff_id"),
                        result.getString("handoff_target"));
            }
        }
    }

    private void requireLocalProcess(Connection connection) throws SQLException {
        // SHARE allows independent player transactions on PostgreSQL. H2 has a
        // single local authority and uses its supported exclusive row lock.
        String lock = postgres(connection) ? " FOR SHARE" : " FOR UPDATE";
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT process_token, lease_expires_at FROM rpg_node_leases WHERE node_id = ?
                """ + lock)) {
            query.setString(1, nodeId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !processToken.equals(result.getString("process_token"))
                        || !result.getTimestamp("lease_expires_at").toInstant().isAfter(now(connection))) {
                    throw new IllegalStateException("Paper process no longer owns node " + nodeId);
                }
            }
        }
    }

    private Instant now(Connection connection) throws SQLException {
        if (testClock != null) return testClock.instant();
        if (!postgres(connection)) return Instant.now();
        try (PreparedStatement query = connection.prepareStatement("SELECT clock_timestamp()");
             ResultSet result = query.executeQuery()) {
            if (!result.next()) throw new SQLException("Database clock returned no timestamp");
            return result.getTimestamp(1).toInstant();
        }
    }

    private Instant leaseExpiry(Instant now) {
        // PostgreSQL and the portable TIMESTAMP schema preserve microseconds.
        // Return exactly what is written so retries cannot change the public
        // lease/handoff value merely by reading it back from the database.
        return now.plus(duration).truncatedTo(ChronoUnit.MICROS);
    }

    private boolean postgres(Connection connection) throws SQLException {
        String name = connection.getMetaData().getDatabaseProductName();
        if (name.equals("PostgreSQL")) return true;
        if (name.equals("H2")) return false;
        throw new SQLException("Unsupported session lease database: " + name);
    }

    private void requireLocalToken(PlayerSessionLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (!nodeId.equals(lease.nodeId()) || !processToken.equals(lease.processToken())) {
            throw new IllegalArgumentException("Session token belongs to another node/process");
        }
    }

    private static boolean sameIdentity(Row row, PlayerSessionLease lease) {
        return row != null && lease.nodeId().equals(row.node()) && lease.processToken().equals(row.process())
                && lease.sessionToken().toString().equals(row.session());
    }

    private static boolean matches(Row row, PlayerSessionLease lease) {
        return sameIdentity(row, lease) && row.fence() == lease.fence();
    }

    private static void requireOne(int updated) throws SQLException {
        if (updated != 1) throw new SQLException("Player session row changed while locked");
    }

    private <T> CompletionStage<T> submit(Supplier<T> work) {
        if (executor == null) return CompletableFuture.failedFuture(new IllegalStateException(
                "Lifecycle writes require an explicitly configured bounded executor"));
        return AsyncJdbc.supply(executor, work);
    }

    private <T> T ownedTransaction(SqlWork<T> work) {
        return transaction(connection -> {
            T result = work.run(connection);
            // Waiting for a player's row may outlive the node's lease. Its
            // shared lock prevents replacement, but cannot make time stop.
            requireLocalProcess(connection);
            if (result instanceof Optional<?> optional && optional.isPresent()) {
                Instant expiry = switch (optional.orElseThrow()) {
                    case PlayerSessionLease lease -> lease.expiresAt();
                    case PlayerSessionHandoff handoff -> handoff.expiresAt();
                    default -> null;
                };
                if (expiry != null && !expiry.isAfter(now(connection))) {
                    throw new IllegalStateException("Player session expired before its authority transaction committed");
                }
            }
            return result;
        });
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T value = work.run(connection);
                connection.commit();
                return value;
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Player session transaction failed", error);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not open player session transaction", error);
        }
    }

    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
    private record Row(String node, String process, String session, long fence, String state,
                       Instant expiresAt, String handoff, String target) {
        PlayerSessionLease lease(UUID playerId) {
            return new PlayerSessionLease(playerId, node, process, UUID.fromString(session), fence, expiresAt);
        }
        PlayerSessionHandoff handoff(UUID playerId) {
            return new PlayerSessionHandoff(playerId, UUID.fromString(handoff), node, process, target, fence, expiresAt);
        }
    }
    public record Holder(String nodeId, String processToken, Instant expiresAt) { }
    public enum Admission {
        ALLOWED, NO_VALID_LEASE, STALE_PROCESS_TOKEN, HELD_BY_OTHER_NODE;
        public boolean allowed() { return this == ALLOWED; }
    }
}
