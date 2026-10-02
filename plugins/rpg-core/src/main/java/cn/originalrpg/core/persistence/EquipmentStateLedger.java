package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.rules.EquipmentLifecycle;
import cn.originalrpg.api.rules.EquipmentLifecycle.State;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Phase 2: the single durable authority for custom-equipment lifecycle state
 * (C-001, C-008, C-009). Every method runs on the caller's JDBC transaction so
 * a state transition commits or rolls back atomically with the ledger rows it
 * guards. Row locks serialize the market escrow and the equipment operation
 * outbox against each other: one item identity can never be simultaneously
 * listed and mutated by a paid operation.
 *
 * <p>Identities that predate this registry have no row. Market collection and
 * escrow return treat a missing row as legacy-compatible; first escrow and
 * operations adopt the item under its PDC-derived seed instead of refusing.</p>
 */
final class EquipmentStateLedger {
    private static final String SELECT = "SELECT state FROM rpg_equipment_states WHERE item_id = ? FOR UPDATE";

    /** Returns the post-operation state, or {@code null} when the transition is refused. */
    State operationOutcome(Connection connection, EquipmentOperationKind kind, UUID itemId, UUID actor)
            throws SQLException {
        if (kind == EquipmentOperationKind.FORGE) throw new IllegalArgumentException("forge has no existing identity");
        State current = select(connection, itemId);
        if (current == null) {
            State adopted = EquipmentLifecycle.afterOperation(kind, EquipmentLifecycle.seedForOperation(kind));
            if (adopted == null) return null;
            upsert(connection, itemId, adopted, actor, kind.name());
            return adopted;
        }
        State next = EquipmentLifecycle.afterOperation(kind, current);
        if (next == null) return null;
        update(connection, itemId, next, actor, kind.name());
        return next;
    }

    /**
     * UNBOUND/CREATED gear (or an unregistered identity) enters market escrow.
     * LISTED covers a relist replay; SOLD is not accepted because the escrow
     * gate must stay stricter than the command-side ownership checks — refusing
     * here fails closed, and a legitimate resale of collected gear goes through
     * collection (SOLD -> UNBOUND) first. RESTORED is accepted
     * because the lifecycle itself allows the human-verified freeze to be
     * lifted straight back into escrow (RESTORED -> LISTED), and refusing here
     * would leave restored gear with no producer for its next transition.
     */
    boolean beginEscrow(Connection connection, UUID itemId, UUID seller) throws SQLException {
        State current = select(connection, itemId);
        if (current == null) {
            upsert(connection, itemId, State.LISTED, seller, "MARKET_PREPARE");
            return true;
        }
        if (current != State.UNBOUND && current != State.CREATED && current != State.LISTED
                && current != State.RESTORED) return false;
        update(connection, itemId, State.LISTED, seller, "MARKET_PREPARE");
        return true;
    }

    /** Escrowed gear is paid for: the item moves to the buyer under sale settlement. */
    boolean completeSale(Connection connection, UUID itemId, UUID buyer) throws SQLException {
        State current = select(connection, itemId);
        if (current == null) return true;
        if (current != State.LISTED) return false;
        update(connection, itemId, State.SOLD, buyer, "MARKET_PURCHASE");
        return true;
    }

    /** A cancelled or expired escrow returns the identity to its seller, tradeable again. */
    boolean returnToSeller(Connection connection, UUID itemId, UUID seller) throws SQLException {
        State current = select(connection, itemId);
        if (current == null || current == State.UNBOUND) return true;
        if (current != State.LISTED) return false;
        update(connection, itemId, State.UNBOUND, seller, "MARKET_RETURN");
        return true;
    }

    /**
     * Risk-control freeze (C-013: freeze only, never mutate). The owning player
     * is kept so a verified restore can hand the identity back to them.
     */
    boolean quarantineEscrow(Connection connection, UUID itemId) throws SQLException {
        State current = select(connection, itemId);
        if (current == null || current == State.QUARANTINED) return true;
        if (EquipmentLifecycle.isTerminal(current)) return false;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_equipment_states SET state = ?, last_operation = ?, updated_at = CURRENT_TIMESTAMP
                WHERE item_id = ?
                """)) {
            int index = 0;
            update.setString(++index, State.QUARANTINED.name());
            update.setString(++index, "MARKET_QUARANTINE");
            update.setString(++index, itemId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Lifecycle row disappeared: " + itemId);
        }
        return true;
    }

    /**
     * The buyer collecting a purchased identity ends the custody transfer
     * (SOLD -> UNBOUND). Market escrow only ever accepts unbound gear — the
     * listing command refuses a bound owner before the ledger is involved — so
     * the collected item arrives in the buyer's inventory unowned in PDC and the
     * ledger must agree; combat binding may move it to BOUND afterwards. Anything
     * already past SOLD is a benign replay, but a frozen or terminal identity
     * refuses the hand-over.
     */
    boolean collectPurchase(Connection connection, UUID itemId, UUID buyer) throws SQLException {
        State current = select(connection, itemId);
        if (current == null) return true;
        if (current == State.BOUND || current == State.UNBOUND || current == State.LISTED) return true;
        if (current != State.SOLD) return false;
        update(connection, itemId, State.UNBOUND, buyer, "MARKET_COLLECT");
        return true;
    }

    private State select(Connection connection, UUID itemId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(SELECT)) {
            query.setString(1, itemId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return State.valueOf(result.getString(1));
            }
        } catch (IllegalArgumentException exception) {
            throw new SQLException("Unknown lifecycle state row for item " + itemId, exception);
        }
    }

    /**
     * Writes a row the caller observed as absent under its FOR UPDATE select.
     * UPDATE-first keeps the statement dialect-neutral (H2 in tests, PostgreSQL
     * in production); the conditional insert covers the race against a concurrent
     * adoption, which the missing-row lock cannot serialize, and the retry then
     * lands on the row the other transaction committed.
     */
    private void upsert(Connection connection, UUID itemId, State state, UUID owner, String operation)
            throws SQLException {
        if (tryUpdate(connection, itemId, state, owner, operation)) return;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_equipment_states (item_id, state, owner_uuid, last_operation)
                VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            int index = 0;
            insert.setString(++index, itemId.toString());
            insert.setString(++index, state.name());
            if (owner == null) insert.setNull(++index, java.sql.Types.VARCHAR);
            else insert.setString(++index, owner.toString());
            insert.setString(++index, operation);
            insert.executeUpdate();
        }
        if (!tryUpdate(connection, itemId, state, owner, operation)) {
            throw new SQLException("Lifecycle row vanished between insert and update: " + itemId);
        }
    }

    private void update(Connection connection, UUID itemId, State state, UUID owner, String operation)
            throws SQLException {
        if (!tryUpdate(connection, itemId, state, owner, operation)) {
            throw new SQLException("Lifecycle row disappeared: " + itemId);
        }
    }

    private boolean tryUpdate(Connection connection, UUID itemId, State state, UUID owner, String operation)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_equipment_states SET state = ?, owner_uuid = ?, last_operation = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE item_id = ?
                """)) {
            int index = 0;
            update.setString(++index, state.name());
            if (owner == null) update.setNull(++index, java.sql.Types.VARCHAR);
            else update.setString(++index, owner.toString());
            update.setString(++index, operation);
            update.setString(++index, itemId.toString());
            return update.executeUpdate() == 1;
        }
    }
}
