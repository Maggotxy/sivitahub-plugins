package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PetRevivalIntent;
import cn.originalrpg.api.model.PetRevivalRequest;
import cn.originalrpg.api.model.PetRevivalResult;
import cn.originalrpg.api.service.PetRevivalService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Transactional Gold reservation and durable intent store for MyPet revivals. */
public final class JdbcPetRevivalService implements PetRevivalService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{3,96}");
    private static final Pattern REASON = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final BigDecimal MAX_COST = new BigDecimal("1000000000000.00");
    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000000.00");
    private static final int MAX_REVIEW_ROWS = 200;

    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;

    public JdbcPetRevivalService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
    }

    @Override
    public CompletionStage<PetRevivalResult> begin(PetRevivalRequest request) {
        if (!valid(request)) {
            return CompletableFuture.completedFuture(PetRevivalResult.withoutIntent(
                    PetRevivalResult.Status.INVALID));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> begin(connection, request)));
    }

    @Override
    public CompletionStage<List<PetRevivalIntent>> pending(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(List.of());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            List<PetRevivalIntent> intents = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT operation_id, player_uuid, pet_uuid, gold_cost, debit_key, state, failure_reason
                    FROM rpg_pet_revival_intents
                    WHERE player_uuid = ? AND state = 'PENDING'
                    ORDER BY created_at, operation_id
                    """)) {
                query.setString(1, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) intents.add(read(result));
                }
            }
            return List.copyOf(intents);
        }));
    }

    @Override
    public CompletionStage<Optional<PetRevivalIntent>> find(UUID operationId) {
        if (operationId == null) return CompletableFuture.completedFuture(Optional.empty());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection ->
                Optional.ofNullable(intent(connection, operationId, false))));
    }

    @Override
    public CompletionStage<List<PetRevivalIntent>> reviewQueue(int limit) {
        if (limit <= 0) return CompletableFuture.completedFuture(List.of());
        int bounded = Math.min(limit, MAX_REVIEW_ROWS);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            List<PetRevivalIntent> intents = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT operation_id, player_uuid, pet_uuid, gold_cost, debit_key, state, failure_reason
                    FROM rpg_pet_revival_intents
                    WHERE state = 'QUARANTINED'
                    ORDER BY created_at, operation_id
                    LIMIT ?
                    """)) {
                query.setInt(1, bounded);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) intents.add(read(result));
                }
            }
            return List.copyOf(intents);
        }));
    }

    @Override
    public CompletionStage<PetRevivalResult> complete(UUID operationId, UUID playerId, UUID petId) {
        if (operationId == null || playerId == null || petId == null) {
            return CompletableFuture.completedFuture(PetRevivalResult.withoutIntent(
                    PetRevivalResult.Status.INVALID));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> transition(
                connection, operationId, playerId, petId, PetRevivalIntent.State.COMPLETED, null)));
    }

    @Override
    public CompletionStage<PetRevivalResult> quarantine(
            UUID operationId, UUID playerId, UUID petId, String failureReason
    ) {
        if (operationId == null || playerId == null || petId == null || failureReason == null
                || !REASON.matcher(failureReason).matches()) {
            return CompletableFuture.completedFuture(PetRevivalResult.withoutIntent(
                    PetRevivalResult.Status.INVALID));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> transition(
                connection, operationId, playerId, petId, PetRevivalIntent.State.QUARANTINED, failureReason)));
    }

    @Override
    public CompletionStage<PetRevivalResult> retryQuarantined(UUID operationId) {
        if (operationId == null) {
            return CompletableFuture.completedFuture(PetRevivalResult.withoutIntent(
                    PetRevivalResult.Status.INVALID));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PetRevivalIntent existing = intent(connection, operationId, true);
            if (existing == null) return PetRevivalResult.withoutIntent(PetRevivalResult.Status.NOT_FOUND);
            if (existing.state() == PetRevivalIntent.State.PENDING) {
                return new PetRevivalResult(PetRevivalResult.Status.PENDING, Optional.of(existing));
            }
            if (existing.state() != PetRevivalIntent.State.QUARANTINED) {
                return new PetRevivalResult(PetRevivalResult.Status.CONFLICT, Optional.of(existing));
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_pet_revival_intents
                    SET state = 'PENDING', completed_at = NULL, failure_reason = NULL,
                        active_pet_uuid = pet_uuid
                    WHERE operation_id = ? AND state = 'QUARANTINED'
                    """)) {
                update.setString(1, operationId.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Pet revival retry lost its row lock");
            }
            PetRevivalIntent retried = new PetRevivalIntent(
                    existing.operationId(), existing.playerId(), existing.petId(), existing.goldCost(),
                    existing.debitKey(), PetRevivalIntent.State.PENDING, "");
            return new PetRevivalResult(PetRevivalResult.Status.PENDING, Optional.of(retried));
        }));
    }

    @Override
    public CompletionStage<PetRevivalResult> compensateQuarantined(UUID operationId, String administrator) {
        if (operationId == null || administrator == null || !ACTOR.matcher(administrator).matches()) {
            return CompletableFuture.completedFuture(PetRevivalResult.withoutIntent(
                    PetRevivalResult.Status.INVALID));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection ->
                compensateQuarantined(connection, operationId, administrator)));
    }

    private PetRevivalResult begin(Connection connection, PetRevivalRequest request) throws SQLException {
        PetRevivalIntent byOperation = intent(connection, request.operationId(), true);
        if (byOperation != null) return replay(request, byOperation);

        loadOrCreateAccount(connection, request.playerId());
        BigDecimal balance = lockBalance(connection, request.playerId());
        PetRevivalIntent existingPending = unresolvedForPet(connection, request.petId(), true);
        if (existingPending != null) {
            return new PetRevivalResult(existingPending.playerId().equals(request.playerId())
                    ? status(existingPending.state()) : PetRevivalResult.Status.CONFLICT,
                    Optional.of(existingPending));
        }
        if (balance.compareTo(request.goldCost()) < 0) {
            return PetRevivalResult.withoutIntent(PetRevivalResult.Status.INSUFFICIENT_FUNDS);
        }

        PetRevivalIntent created = new PetRevivalIntent(
                request.operationId(), request.playerId(), request.petId(), request.goldCost(),
                request.debitKey(), PetRevivalIntent.State.PENDING, "");
        insertIntent(connection, created);
        if (request.goldCost().signum() > 0) {
            updateBalance(connection, request.playerId(), balance.subtract(request.goldCost()));
            if (!insertDebit(connection, request)) {
                throw new SQLException("Pet revival debit key collided after intent reservation");
            }
        }
        return new PetRevivalResult(PetRevivalResult.Status.STARTED, Optional.of(created));
    }

    private PetRevivalResult compensateQuarantined(
            Connection connection, UUID operationId, String administrator
    ) throws SQLException {
        PetRevivalIntent snapshot = intent(connection, operationId, false);
        if (snapshot == null) return PetRevivalResult.withoutIntent(PetRevivalResult.Status.NOT_FOUND);
        if (snapshot.state() == PetRevivalIntent.State.COMPENSATED) {
            return new PetRevivalResult(PetRevivalResult.Status.COMPENSATED, Optional.of(snapshot));
        }
        if (snapshot.state() != PetRevivalIntent.State.QUARANTINED) {
            return new PetRevivalResult(PetRevivalResult.Status.CONFLICT, Optional.of(snapshot));
        }

        // Match begin()'s account-before-active-intent lock order. Otherwise a
        // new revival racing an administrator compensation can deadlock by
        // holding the account and waiting for this intent while we do the
        // inverse.
        loadOrCreateAccount(connection, snapshot.playerId());
        BigDecimal balance = lockBalance(connection, snapshot.playerId());
        PetRevivalIntent existing = intent(connection, operationId, true);
        if (existing == null) throw new SQLException("Pet revival intent disappeared during compensation");
        if (existing.state() == PetRevivalIntent.State.COMPENSATED) {
            return new PetRevivalResult(PetRevivalResult.Status.COMPENSATED, Optional.of(existing));
        }
        if (existing.state() != PetRevivalIntent.State.QUARANTINED
                || !existing.playerId().equals(snapshot.playerId())) {
            return new PetRevivalResult(PetRevivalResult.Status.CONFLICT, Optional.of(existing));
        }
        if (balance.add(existing.goldCost()).compareTo(MAX_BALANCE) > 0) {
            return new PetRevivalResult(PetRevivalResult.Status.BALANCE_LIMIT, Optional.of(existing));
        }

        if (existing.goldCost().signum() > 0) {
            verifyOriginalDebit(connection, existing);
            String refundKey = "pet-revive-refund:" + operationId;
            if (!insertCompensation(connection, refundKey, existing, administrator)) {
                PetRevivalCompensation recorded = compensation(connection, existing.debitKey());
                if (recorded == null || !recorded.idempotencyKey().equals(refundKey)
                        || !recorded.playerId().equals(existing.playerId())
                        || !recorded.amount().equals(existing.goldCost())) {
                    throw new SQLException("Pet revival compensation key collided with different facts");
                }
            } else {
                updateBalance(connection, existing.playerId(), balance.add(existing.goldCost()));
            }
        }

        String auditReason = "compensated_by:" + administrator;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_pet_revival_intents
                SET state = 'COMPENSATED', completed_at = CURRENT_TIMESTAMP,
                    failure_reason = ?, active_pet_uuid = NULL
                WHERE operation_id = ? AND state = 'QUARANTINED'
                """)) {
            update.setString(1, auditReason);
            update.setString(2, operationId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Pet revival compensation lost its row lock");
        }
        PetRevivalIntent compensated = new PetRevivalIntent(
                existing.operationId(), existing.playerId(), existing.petId(), existing.goldCost(),
                existing.debitKey(), PetRevivalIntent.State.COMPENSATED, auditReason);
        return new PetRevivalResult(PetRevivalResult.Status.COMPENSATED, Optional.of(compensated));
    }

    private PetRevivalResult transition(
            Connection connection,
            UUID operationId,
            UUID playerId,
            UUID petId,
            PetRevivalIntent.State target,
            String failureReason
    ) throws SQLException {
        PetRevivalIntent existing = intent(connection, operationId, true);
        if (existing == null) return PetRevivalResult.withoutIntent(PetRevivalResult.Status.NOT_FOUND);
        if (!existing.playerId().equals(playerId) || !existing.petId().equals(petId)) {
            return new PetRevivalResult(PetRevivalResult.Status.CONFLICT, Optional.of(existing));
        }
        if (existing.state() == target) {
            return new PetRevivalResult(status(target), Optional.of(existing));
        }
        if (existing.state() != PetRevivalIntent.State.PENDING) {
            return new PetRevivalResult(PetRevivalResult.Status.CONFLICT, Optional.of(existing));
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_pet_revival_intents
                SET state = ?, completed_at = CURRENT_TIMESTAMP, failure_reason = ?,
                    active_pet_uuid = CASE WHEN ? = 'COMPLETED' THEN NULL ELSE active_pet_uuid END
                WHERE operation_id = ? AND state = 'PENDING'
                """)) {
            update.setString(1, target.name());
            update.setString(2, failureReason);
            update.setString(3, target.name());
            update.setString(4, operationId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Pet revival intent lost its row lock");
        }
        PetRevivalIntent updated = new PetRevivalIntent(
                existing.operationId(), existing.playerId(), existing.petId(), existing.goldCost(),
                existing.debitKey(), target, failureReason);
        return new PetRevivalResult(status(target), Optional.of(updated));
    }

    private PetRevivalResult replay(PetRevivalRequest request, PetRevivalIntent existing) {
        if (!existing.playerId().equals(request.playerId()) || !existing.petId().equals(request.petId())
                || !existing.goldCost().equals(money(request.goldCost()))
                || !existing.debitKey().equals(request.debitKey())) {
            return new PetRevivalResult(PetRevivalResult.Status.CONFLICT, Optional.of(existing));
        }
        return new PetRevivalResult(status(existing.state()), Optional.of(existing));
    }

    private PetRevivalIntent intent(Connection connection, UUID operationId, boolean lock) throws SQLException {
        String sql = """
                SELECT operation_id, player_uuid, pet_uuid, gold_cost, debit_key, state, failure_reason
                FROM rpg_pet_revival_intents WHERE operation_id = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, operationId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private PetRevivalIntent unresolvedForPet(Connection connection, UUID petId, boolean lock) throws SQLException {
        String sql = """
                SELECT operation_id, player_uuid, pet_uuid, gold_cost, debit_key, state, failure_reason
                FROM rpg_pet_revival_intents WHERE active_pet_uuid = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, petId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? read(result) : null;
            }
        }
    }

    private void insertIntent(Connection connection, PetRevivalIntent intent) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_pet_revival_intents
                (operation_id, player_uuid, pet_uuid, active_pet_uuid, gold_cost, debit_key, state, failure_reason)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', NULL)
                """)) {
            insert.setString(1, intent.operationId().toString());
            insert.setString(2, intent.playerId().toString());
            insert.setString(3, intent.petId().toString());
            insert.setString(4, intent.petId().toString());
            insert.setBigDecimal(5, intent.goldCost());
            insert.setString(6, intent.debitKey());
            insert.executeUpdate();
        }
    }

    private boolean insertDebit(Connection connection, PetRevivalRequest request) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger
                (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, NULL, ?, 0, 'pet_revive') ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, request.debitKey());
            insert.setString(3, request.playerId().toString());
            insert.setBigDecimal(4, request.goldCost());
            return insert.executeUpdate() == 1;
        }
    }

    private void verifyOriginalDebit(Connection connection, PetRevivalIntent intent) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT sender_uuid, receiver_uuid, amount, reason
                FROM rpg_ledger WHERE idempotency_key = ? FOR UPDATE
                """)) {
            query.setString(1, intent.debitKey());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()
                        || !intent.playerId().toString().equals(result.getString("sender_uuid"))
                        || result.getString("receiver_uuid") != null
                        || !intent.goldCost().equals(money(result.getBigDecimal("amount")))
                        || !"pet_revive".equals(result.getString("reason"))) {
                    throw new SQLException("Pet revival debit does not match the quarantined intent");
                }
            }
        }
    }

    private boolean insertCompensation(
            Connection connection,
            String refundKey,
            PetRevivalIntent intent,
            String administrator
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger
                    (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason, reversal_of)
                VALUES (?, ?, NULL, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, refundKey);
            insert.setString(3, intent.playerId().toString());
            insert.setBigDecimal(4, intent.goldCost());
            insert.setString(5, "pet_revive_compensation:" + administrator);
            insert.setString(6, intent.debitKey());
            return insert.executeUpdate() == 1;
        }
    }

    private PetRevivalCompensation compensation(Connection connection, String debitKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT idempotency_key, receiver_uuid, amount
                FROM rpg_ledger WHERE reversal_of = ? FOR UPDATE
                """)) {
            query.setString(1, debitKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new PetRevivalCompensation(
                        result.getString("idempotency_key"),
                        UUID.fromString(result.getString("receiver_uuid")),
                        money(result.getBigDecimal("amount")));
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
                if (!result.next()) throw new SQLException("Pet revival account was not created");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Pet revival account disappeared");
        }
    }

    private boolean valid(PetRevivalRequest request) {
        return request != null && request.operationId() != null && request.playerId() != null
                && request.petId() != null && request.goldCost() != null && request.debitKey() != null
                && KEY.matcher(request.debitKey()).matches() && request.goldCost().scale() <= 2
                && request.goldCost().signum() >= 0 && request.goldCost().compareTo(MAX_COST) <= 0;
    }

    private static PetRevivalIntent read(ResultSet result) throws SQLException {
        return new PetRevivalIntent(
                UUID.fromString(result.getString("operation_id")),
                UUID.fromString(result.getString("player_uuid")),
                UUID.fromString(result.getString("pet_uuid")),
                result.getBigDecimal("gold_cost"),
                result.getString("debit_key"),
                PetRevivalIntent.State.valueOf(result.getString("state")),
                result.getString("failure_reason"));
    }

    private static PetRevivalResult.Status status(PetRevivalIntent.State state) {
        return switch (state) {
            case PENDING -> PetRevivalResult.Status.PENDING;
            case COMPLETED -> PetRevivalResult.Status.COMPLETED;
            case QUARANTINED -> PetRevivalResult.Status.QUARANTINED;
            case COMPENSATED -> PetRevivalResult.Status.COMPENSATED;
        };
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
                throw new IllegalStateException("Pet revival transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open pet revival transaction", exception);
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private record PetRevivalCompensation(String idempotencyKey, UUID playerId, BigDecimal amount) {
    }
}
