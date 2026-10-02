package cn.originalrpg.core.persistence;

import cn.originalrpg.api.rules.EquipmentLifecycle;
import cn.originalrpg.api.service.EquipmentLedgerAuditService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Phase 2 (C-013): operator window onto the lifecycle ledger.
 *
 * <p>Reads never take row locks (watchlists must not stall the market hot
 * path). Writes run on a JDBC transaction under {@code SELECT ... FOR UPDATE},
 * so a freeze or thaw serializes against the escrow and operation gates and a
 * concurrent purchase settlement cannot slip between the check and the write.
 * The state row keeps a short operation token — its column is 24 wide — while
 * the actor and the full audit note land in {@code rpg_equipment_lifecycle_audit}
 * in the same transaction: a state change without its paper trail can never be
 * committed.</p>
 */
public final class JdbcEquipmentLedgerAuditService implements EquipmentLedgerAuditService {
    private static final int MAX_WATCHLIST = 200;
    private static final int MAX_TRAIL = 100;
    private static final int MAX_ACTOR_LENGTH = 64;
    private static final int MAX_NOTE_LENGTH = 500;
    private static final String QUARANTINE_TOKEN = "LEDGER_QUARANTINE";
    private static final String RESTORE_TOKEN = "LEDGER_RESTORE";

    private final DataSource dataSource;
    private final Executor executor;

    public JdbcEquipmentLedgerAuditService(DataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletionStage<Optional<Entry>> entry(UUID itemId) {
        if (itemId == null) return completed(Optional.empty());
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                return readEntry(connection, itemId);
            } catch (SQLException exception) {
                throw new IllegalStateException("lifecycle entry read failed", exception);
            }
        });
    }

    @Override
    public CompletionStage<List<Entry>> watchlist(int limit) {
        if (limit < 1) return completed(List.of());
        int bounded = Math.min(limit, MAX_WATCHLIST);
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                List<Entry> entries = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT item_id, state, owner_uuid, last_operation FROM rpg_equipment_states
                        WHERE state IN ('QUARANTINED', 'RESTORED', 'SALVAGED', 'LEGACY_EXTRACTED', 'DESTROYED')
                        ORDER BY updated_at DESC LIMIT ?
                        """)) {
                    query.setInt(1, bounded);
                    try (ResultSet result = query.executeQuery()) {
                        while (result.next()) entries.add(toEntry(result));
                    }
                }
                connection.commit();
                return List.copyOf(entries);
            } catch (SQLException exception) {
                throw new IllegalStateException("lifecycle watchlist read failed", exception);
            }
        });
    }

    @Override
    public CompletionStage<AuditResult> quarantine(UUID itemId, String actor, String auditNote) {
        if (itemId == null) return completed(new AuditResult(AuditResult.Status.NOT_FOUND, null));
        if (!validText(actor, MAX_ACTOR_LENGTH) || !validText(auditNote, MAX_NOTE_LENGTH)) {
            return completed(new AuditResult(AuditResult.Status.INVALID_NOTE, null));
        }
        return AsyncJdbc.supply(executor, () -> apply(
                itemId, actor.trim(), auditNote.trim(),
                EquipmentLifecycle.State.QUARANTINED, QUARANTINE_TOKEN, "QUARANTINE"));
    }

    @Override
    public CompletionStage<AuditResult> restore(UUID itemId, String actor, String auditNote) {
        if (itemId == null) return completed(new AuditResult(AuditResult.Status.NOT_FOUND, null));
        if (!validText(actor, MAX_ACTOR_LENGTH) || !validText(auditNote, MAX_NOTE_LENGTH)) {
            return completed(new AuditResult(AuditResult.Status.INVALID_NOTE, null));
        }
        return AsyncJdbc.supply(executor, () -> apply(
                itemId, actor.trim(), auditNote.trim(),
                EquipmentLifecycle.State.RESTORED, RESTORE_TOKEN, "RESTORE"));
    }

    @Override
    public CompletionStage<List<AuditRecord>> auditTrail(UUID itemId, int limit) {
        if (itemId == null || limit < 1) return completed(List.of());
        int bounded = Math.min(limit, MAX_TRAIL);
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                List<AuditRecord> records = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT action, from_state, to_state, actor, note, created_at
                        FROM rpg_equipment_lifecycle_audit WHERE item_id = ?
                        ORDER BY created_at DESC LIMIT ?
                        """)) {
                    query.setString(1, itemId.toString());
                    query.setInt(2, bounded);
                    try (ResultSet result = query.executeQuery()) {
                        while (result.next()) {
                            Timestamp at = result.getTimestamp("created_at");
                            records.add(new AuditRecord(
                                    result.getString("action"),
                                    result.getString("from_state"),
                                    result.getString("to_state"),
                                    result.getString("actor"),
                                    result.getString("note"),
                                    at == null ? null : at.toInstant()));
                        }
                    }
                }
                connection.commit();
                return List.copyOf(records);
            } catch (SQLException exception) {
                throw new IllegalStateException("lifecycle audit trail read failed", exception);
            }
        });
    }

    /**
     * Applies one audited transition. Freezes never touch a spent identity and
     * thaws only lift a real freeze; both must also satisfy the lifecycle graph
     * (C-001: the rules decide, the operator only verifies).
     */
    private AuditResult apply(
            UUID itemId, String actor, String note,
            EquipmentLifecycle.State target, String token, String action) {
        boolean freezing = target == EquipmentLifecycle.State.QUARANTINED;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                EquipmentLifecycle.State current = selectForUpdate(connection, itemId);
                if (current == null) {
                    connection.rollback();
                    return new AuditResult(AuditResult.Status.NOT_FOUND, null);
                }
                if (EquipmentLifecycle.isTerminal(current)) {
                    connection.rollback();
                    return new AuditResult(AuditResult.Status.TERMINAL, current.name());
                }
                if (freezing) {
                    if (current == target) {
                        connection.rollback();
                        return new AuditResult(AuditResult.Status.ALREADY_IN_STATE, current.name());
                    }
                } else if (current != EquipmentLifecycle.State.QUARANTINED) {
                    connection.rollback();
                    return new AuditResult(AuditResult.Status.NOT_FROZEN, current.name());
                }
                if (!EquipmentLifecycle.isValidTransition(current, target)) {
                    connection.rollback();
                    return new AuditResult(AuditResult.Status.RULES_REFUSED, current.name());
                }
                writeState(connection, itemId, target, token);
                writeAudit(connection, itemId, action, current, target, actor, note);
                connection.commit();
                return new AuditResult(AuditResult.Status.APPLIED, target.name());
            } catch (SQLException exception) {
                connection.rollback();
                throw new IllegalStateException("lifecycle audit mutation failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("lifecycle audit connection failed", exception);
        }
    }

    /** Owner-preserving: neither a freeze nor a thaw rewrites who holds the identity. */
    private void writeState(
            Connection connection, UUID itemId, EquipmentLifecycle.State state, String token) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_equipment_states SET state = ?, last_operation = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE item_id = ?
                """)) {
            int index = 0;
            update.setString(++index, state.name());
            update.setString(++index, token);
            update.setString(++index, itemId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Lifecycle row disappeared: " + itemId);
        }
    }

    private void writeAudit(
            Connection connection, UUID itemId, String action,
            EquipmentLifecycle.State from, EquipmentLifecycle.State to, String actor, String note)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_equipment_lifecycle_audit
                (id, item_id, action, from_state, to_state, actor, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 0;
            insert.setString(++index, UUID.randomUUID().toString());
            insert.setString(++index, itemId.toString());
            insert.setString(++index, action);
            insert.setString(++index, from.name());
            insert.setString(++index, to.name());
            insert.setString(++index, actor);
            insert.setString(++index, note);
            if (insert.executeUpdate() != 1) throw new SQLException("Lifecycle audit row was not written");
        }
    }

    private EquipmentLifecycle.State selectForUpdate(Connection connection, UUID itemId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT state FROM rpg_equipment_states WHERE item_id = ? FOR UPDATE")) {
            query.setString(1, itemId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return EquipmentLifecycle.State.valueOf(result.getString(1));
            }
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Unknown lifecycle state row for item " + itemId, exception);
        }
    }

    private Optional<Entry> readEntry(Connection connection, UUID itemId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT item_id, state, owner_uuid, last_operation FROM rpg_equipment_states
                WHERE item_id = ?
                """)) {
            query.setString(1, itemId.toString());
            try (ResultSet result = query.executeQuery()) {
                Optional<Entry> entry = result.next() ? Optional.of(toEntry(result)) : Optional.empty();
                connection.commit();
                return entry;
            }
        }
    }

    private static Entry toEntry(ResultSet result) throws SQLException {
        String owner = result.getString("owner_uuid");
        return new Entry(
                UUID.fromString(result.getString("item_id")),
                result.getString("state"),
                owner == null ? null : UUID.fromString(owner),
                result.getString("last_operation"));
    }

    private static boolean validText(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.trim().length() <= maximumLength;
    }

    private static <T> CompletionStage<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
