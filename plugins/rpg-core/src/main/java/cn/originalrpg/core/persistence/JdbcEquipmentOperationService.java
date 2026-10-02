package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.api.rules.EquipmentLifecycle;
import cn.originalrpg.api.service.EquipmentOperationService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Durable equipment outbox. The Gold debit, immutable receipt and every item
 * mailbox row commit in one JDBC transaction.
 */
public final class JdbcEquipmentOperationService implements EquipmentOperationService {
    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;
    private final EquipmentStateLedger lifecycle = new EquipmentStateLedger();

    public JdbcEquipmentOperationService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
    }

    @Override
    public CompletionStage<EquipmentOperationResult> settle(EquipmentOperationRequest request) {
        if (request == null || !request.settleable()) {
            UUID operationId = request == null ? new UUID(0L, 0L) : request.operationId();
            return CompletableFuture.completedFuture(new EquipmentOperationResult(
                    EquipmentOperationResult.Status.INVALID, operationId, false, 0, BigDecimal.ZERO));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> settle(connection, request)));
    }

    private EquipmentOperationResult settle(Connection connection, EquipmentOperationRequest request) throws SQLException {
        loadOrCreateAccount(connection, request.playerId());
        BigDecimal balance = lockBalance(connection, request.playerId());

        Receipt existing = receipt(connection, request.operationId(), true);
        if (existing != null) {
            boolean same = existing.matches(request);
            return new EquipmentOperationResult(same ? existing.status()
                    : EquipmentOperationResult.Status.CONFLICT, request.operationId(), true,
                    same ? existing.queuedDeliveries() : 0, balance);
        }

        EquipmentOperationResult.Status status;
        List<String> deliveries;
        if (balance.compareTo(request.goldCost()) < 0) {
            status = EquipmentOperationResult.Status.REJECTED_INSUFFICIENT_FUNDS;
            deliveries = request.rejectedDeliveries();
        } else {
            // Phase 2 (C-001, C-009): the lifecycle ledger is the authority on
            // whether this identity may undergo the operation. operationOutcome
            // locks the row FOR UPDATE and advances it only when the transition
            // is legal, so it must run after the funds check — a paid commit is
            // the only path allowed to mutate state. FORGE mints a brand-new
            // identity that carries no row yet and is adopted lazily on its first
            // escrow or operation, so it is deliberately not gated here.
            Optional<UUID> itemId = request.itemId();
            boolean legal = true;
            if (itemId.isPresent() && request.kind() != EquipmentOperationKind.FORGE) {
                EquipmentLifecycle.State outcome =
                        lifecycle.operationOutcome(connection, request.kind(), itemId.get(), request.playerId());
                legal = outcome != null;
            }
            if (!legal) {
                status = EquipmentOperationResult.Status.OPERATION_REFUSED;
                deliveries = request.rejectedDeliveries();
            } else {
                status = EquipmentOperationResult.Status.COMMITTED;
                deliveries = request.committedDeliveries();
                if (request.goldCost().signum() > 0) {
                    balance = balance.subtract(request.goldCost());
                    updateBalance(connection, request.playerId(), balance);
                }
            }
        }

        String lane = status == EquipmentOperationResult.Status.COMMITTED ? "result" : "recovery";
        for (int index = 0; index < deliveries.size(); index++) {
            insertDelivery(connection, EquipmentOperationEnvelope.derivedId(
                    request.operationId(), lane, index), request.playerId(), deliveries.get(index),
                    "equipment-" + request.kind().name().toLowerCase(java.util.Locale.ROOT)
                            + ":" + request.operationId());
        }
        insertReceipt(connection, request, status, deliveries.size());
        if (status == EquipmentOperationResult.Status.COMMITTED && request.goldCost().signum() > 0
                && !insertDebit(connection, request)) {
            throw new SQLException("Equipment debit key collided after receipt reservation");
        }
        return new EquipmentOperationResult(status, request.operationId(), false, deliveries.size(), balance);
    }

    private Receipt receipt(Connection connection, UUID operationId, boolean lock) throws SQLException {
        String sql = "SELECT * FROM rpg_equipment_operations WHERE operation_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                String item = result.getString("item_id");
                return new Receipt(UUID.fromString(result.getString("player_uuid")),
                        result.getString("kind"), item == null ? null : UUID.fromString(item),
                        result.getString("input_fingerprint"), result.getString("request_payload"),
                        money(result.getBigDecimal("gold_cost")), result.getString("debit_key"),
                        EquipmentOperationResult.Status.valueOf(result.getString("state")),
                        result.getInt("queued_deliveries"));
            }
        }
    }

    private void insertReceipt(Connection connection, EquipmentOperationRequest request,
                               EquipmentOperationResult.Status status, int queued) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_equipment_operations
                (operation_id, player_uuid, kind, item_id, input_fingerprint, request_payload,
                 gold_cost, debit_key, state, queued_deliveries, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            insert.setString(1, request.operationId().toString());
            insert.setString(2, request.playerId().toString());
            insert.setString(3, request.kind().name());
            insert.setString(4, request.itemId().map(UUID::toString).orElse(null));
            insert.setString(5, request.inputFingerprint());
            insert.setString(6, request.requestPayload());
            insert.setBigDecimal(7, request.goldCost());
            insert.setString(8, request.debitKey());
            insert.setString(9, status.name());
            insert.setInt(10, queued);
            insert.executeUpdate();
        }
    }

    private boolean insertDebit(Connection connection, EquipmentOperationRequest request) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger
                (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, NULL, ?, 0, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, request.debitKey());
            insert.setString(3, request.playerId().toString());
            insert.setBigDecimal(4, request.goldCost());
            insert.setString(5, "equipment-" + request.kind().name().toLowerCase(java.util.Locale.ROOT));
            return insert.executeUpdate() == 1;
        }
    }

    private void insertDelivery(Connection connection, UUID deliveryId, UUID playerId,
                                String itemPayload, String source) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, deliveryId.toString());
            insert.setString(2, playerId.toString());
            insert.setString(3, itemPayload);
            insert.setString(4, source);
            if (insert.executeUpdate() == 1) return;
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, item_payload, source FROM rpg_market_deliveries WHERE id = ?
                """)) {
            query.setString(1, deliveryId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !playerId.toString().equals(result.getString("player_uuid"))
                        || !itemPayload.equals(result.getString("item_payload"))
                        || !source.equals(result.getString("source"))) {
                    throw new SQLException("Equipment delivery identity collision: " + deliveryId);
                }
            }
        }
    }

    private void loadOrCreateAccount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
    }

    private BigDecimal lockBalance(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Equipment account was not created");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Equipment account disappeared");
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = operation.apply(connection);
                connection.commit();
                connection.setAutoCommit(originalAutoCommit);
                return result;
            } catch (Exception exception) {
                connection.rollback();
                connection.setAutoCommit(originalAutoCommit);
                throw new IllegalStateException("Equipment operation transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open equipment operation transaction", exception);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private record Receipt(UUID playerId, String kind, UUID itemId, String inputFingerprint,
                           String requestPayload, BigDecimal goldCost, String debitKey,
                           EquipmentOperationResult.Status status, int queuedDeliveries) {
        boolean matches(EquipmentOperationRequest request) {
            return playerId.equals(request.playerId()) && kind.equals(request.kind().name())
                    && java.util.Objects.equals(itemId, request.itemId().orElse(null))
                    && inputFingerprint.equals(request.inputFingerprint())
                    && requestPayload.equals(request.requestPayload()) && goldCost.equals(request.goldCost())
                    && debitKey.equals(request.debitKey());
        }
    }
}
