package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CurrencyOperationResult;
import cn.originalrpg.api.model.TransactionResult;
import cn.originalrpg.api.service.EconomyService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcEconomyService implements EconomyService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{3,96}");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000.00");
    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;

    public JdbcEconomyService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
    }

    @Override
    public CompletionStage<BigDecimal> balance(UUID playerId) {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> loadOrCreate(connection, playerId)));
    }

    @Override
    public CompletionStage<CurrencyOperationResult> credit(
            UUID playerId, BigDecimal amount, String idempotencyKey, String reason
    ) {
        return change(playerId, amount, idempotencyKey, reason, true);
    }

    @Override
    public CompletionStage<CurrencyOperationResult> burn(
            UUID playerId, BigDecimal amount, String idempotencyKey, String reason
    ) {
        return change(playerId, amount, idempotencyKey, reason, false);
    }

    @Override
    public CompletionStage<CurrencyOperationResult> refund(
            UUID playerId,
            BigDecimal amount,
            String originalDebitKey,
            String idempotencyKey,
            String reason
    ) {
        if (playerId == null || amount == null || originalDebitKey == null || idempotencyKey == null
                || reason == null || originalDebitKey.equals(idempotencyKey)
                || !KEY.matcher(originalDebitKey).matches() || !KEY.matcher(idempotencyKey).matches()
                || reason.isBlank() || reason.length() > 128) {
            return CompletableFuture.completedFuture(new CurrencyOperationResult(
                    CurrencyOperationResult.Status.INVALID_AMOUNT,
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)));
        }
        BigDecimal normalized = money(amount);
        if (normalized.signum() <= 0 || normalized.compareTo(MAX_AMOUNT) > 0) {
            return CompletableFuture.completedFuture(new CurrencyOperationResult(
                    CurrencyOperationResult.Status.INVALID_AMOUNT,
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            loadOrCreate(connection, playerId);
            BigDecimal current = lockBalance(connection, playerId);

            LedgerEntry replay = ledger(connection, idempotencyKey, false);
            if (replay != null) {
                boolean exactReplay = replay.sender() == null && playerId.equals(replay.receiver())
                        && normalized.equals(replay.amount())
                        && originalDebitKey.equals(replay.reversalOf());
                return new CurrencyOperationResult(exactReplay
                        ? CurrencyOperationResult.Status.DUPLICATE
                        : CurrencyOperationResult.Status.REFUND_REJECTED, current, normalized);
            }

            LedgerEntry debit = ledger(connection, originalDebitKey, true);
            boolean refundable = debit != null && playerId.equals(debit.sender()) && debit.receiver() == null
                    && debit.reversalOf() == null && normalized.equals(debit.amount())
                    && debit.fee().signum() == 0;
            if (!refundable || reversalExists(connection, originalDebitKey)) {
                return new CurrencyOperationResult(
                        CurrencyOperationResult.Status.REFUND_REJECTED, current, normalized);
            }

            BigDecimal updated = current.add(normalized);
            if (updated.compareTo(MAX_AMOUNT) > 0) {
                return new CurrencyOperationResult(
                        CurrencyOperationResult.Status.BALANCE_LIMIT, current, normalized);
            }

            if (!insertRefundLedger(connection, idempotencyKey, originalDebitKey,
                    playerId, normalized, reason)) {
                return new CurrencyOperationResult(
                        CurrencyOperationResult.Status.REFUND_REJECTED, current, normalized);
            }
            updateBalance(connection, playerId, updated);
            return new CurrencyOperationResult(CurrencyOperationResult.Status.SUCCESS, updated, normalized);
        }));
    }

    @Override
    public CompletionStage<TransactionResult> transfer(
            UUID sender,
            UUID receiver,
            BigDecimal amount,
            BigDecimal feeRate,
            String idempotencyKey,
            String reason
    ) {
        if (sender == null || receiver == null || amount == null || feeRate == null
                || idempotencyKey == null || reason == null || sender.equals(receiver)
                || feeRate.signum() < 0 || feeRate.compareTo(BigDecimal.ONE) > 0
                || !KEY.matcher(idempotencyKey).matches() || reason.isBlank() || reason.length() > 128) {
            return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.INVALID_AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        BigDecimal normalizedAmount = money(amount);
        if (normalizedAmount.signum() <= 0 || normalizedAmount.compareTo(MAX_AMOUNT) > 0) {
            return CompletableFuture.completedFuture(new TransactionResult(
                    TransactionResult.Status.INVALID_AMOUNT, BigDecimal.ZERO, BigDecimal.ZERO));
        }
        BigDecimal fee = money(normalizedAmount.multiply(feeRate));
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            loadOrCreate(connection, sender);
            loadOrCreate(connection, receiver);
            UUID first = sender.toString().compareTo(receiver.toString()) < 0 ? sender : receiver;
            UUID second = first.equals(sender) ? receiver : sender;
            BigDecimal firstBalance = lockBalance(connection, first);
            BigDecimal secondBalance = lockBalance(connection, second);
            BigDecimal senderBalance = first.equals(sender) ? firstBalance : secondBalance;
            BigDecimal receiverBalance = first.equals(receiver) ? firstBalance : secondBalance;
            if (ledgerExists(connection, idempotencyKey)) {
                return new TransactionResult(TransactionResult.Status.DUPLICATE, senderBalance, fee);
            }
            BigDecimal total = normalizedAmount.add(fee);
            if (senderBalance.compareTo(total) < 0) {
                return new TransactionResult(TransactionResult.Status.INSUFFICIENT_FUNDS, senderBalance, fee);
            }
            BigDecimal newReceiverBalance = receiverBalance.add(normalizedAmount);
            if (newReceiverBalance.compareTo(MAX_AMOUNT) > 0) {
                return new TransactionResult(TransactionResult.Status.BALANCE_LIMIT, senderBalance, fee);
            }
            if (!insertLedger(connection, idempotencyKey, sender, receiver, normalizedAmount, fee, reason)) {
                return new TransactionResult(TransactionResult.Status.DUPLICATE, senderBalance, fee);
            }
            BigDecimal newSenderBalance = senderBalance.subtract(total);
            updateBalance(connection, sender, newSenderBalance);
            updateBalance(connection, receiver, newReceiverBalance);
            return new TransactionResult(TransactionResult.Status.SUCCESS, newSenderBalance, fee);
        }));
    }

    private CompletionStage<CurrencyOperationResult> change(
            UUID playerId, BigDecimal amount, String idempotencyKey, String reason, boolean credit
    ) {
        if (playerId == null || amount == null || idempotencyKey == null || reason == null
                || !KEY.matcher(idempotencyKey).matches()
                || reason.isBlank() || reason.length() > 128) {
            return CompletableFuture.completedFuture(new CurrencyOperationResult(
                    CurrencyOperationResult.Status.INVALID_AMOUNT, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)));
        }
        BigDecimal normalized = money(amount);
        if (normalized.signum() <= 0 || normalized.compareTo(MAX_AMOUNT) > 0) {
            return CompletableFuture.completedFuture(new CurrencyOperationResult(
                    CurrencyOperationResult.Status.INVALID_AMOUNT,
                    BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            loadOrCreate(connection, playerId);
            BigDecimal current = lockBalance(connection, playerId);
            if (ledgerExists(connection, idempotencyKey)) {
                return new CurrencyOperationResult(CurrencyOperationResult.Status.DUPLICATE, current, normalized);
            }
            if (!credit && current.compareTo(normalized) < 0) {
                return new CurrencyOperationResult(CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, current, normalized);
            }
            BigDecimal updated = credit ? current.add(normalized) : current.subtract(normalized);
            if (credit && updated.compareTo(MAX_AMOUNT) > 0) {
                return new CurrencyOperationResult(
                        CurrencyOperationResult.Status.BALANCE_LIMIT, current, normalized);
            }
            if (!insertLedger(connection, idempotencyKey,
                    credit ? null : playerId, credit ? playerId : null,
                    normalized, BigDecimal.ZERO.setScale(2), reason)) {
                return new CurrencyOperationResult(CurrencyOperationResult.Status.DUPLICATE, current, normalized);
            }
            updateBalance(connection, playerId, updated);
            return new CurrencyOperationResult(CurrencyOperationResult.Status.SUCCESS, updated, normalized);
        }));
    }

    private boolean insertLedger(
            Connection connection,
            String key,
            UUID sender,
            UUID receiver,
            BigDecimal amount,
            BigDecimal fee,
            String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, sender == null ? null : sender.toString());
            insert.setString(4, receiver == null ? null : receiver.toString());
            insert.setBigDecimal(5, amount);
            insert.setBigDecimal(6, fee);
            insert.setString(7, reason);
            return insert.executeUpdate() == 1;
        }
    }

    private boolean insertRefundLedger(
            Connection connection,
            String key,
            String originalDebitKey,
            UUID playerId,
            BigDecimal amount,
            String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger
                    (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason, reversal_of)
                VALUES (?, ?, NULL, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, playerId.toString());
            insert.setBigDecimal(4, amount);
            insert.setString(5, reason);
            insert.setString(6, originalDebitKey);
            return insert.executeUpdate() == 1;
        }
    }

    private LedgerEntry ledger(Connection connection, String idempotencyKey, boolean lock) throws SQLException {
        String sql = """
                SELECT sender_uuid, receiver_uuid, amount, fee, reversal_of
                FROM rpg_ledger WHERE idempotency_key = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, idempotencyKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new LedgerEntry(uuid(result.getString("sender_uuid")),
                        uuid(result.getString("receiver_uuid")), money(result.getBigDecimal("amount")),
                        money(result.getBigDecimal("fee")), result.getString("reversal_of"));
            }
        }
    }

    private boolean reversalExists(Connection connection, String originalDebitKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_ledger WHERE reversal_of = ?")) {
            query.setString(1, originalDebitKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean ledgerExists(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM rpg_ledger WHERE idempotency_key = ?")) {
            query.setString(1, idempotencyKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private BigDecimal loadOrCreate(Connection connection, UUID playerId) throws SQLException {
        BigDecimal balance = selectBalance(connection, playerId, false);
        if (balance != null) {
            return balance;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
        return selectBalance(connection, playerId, false);
    }

    private BigDecimal lockBalance(Connection connection, UUID playerId) throws SQLException {
        return selectBalance(connection, playerId, true);
    }

    private BigDecimal selectBalance(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT balance FROM rpg_accounts WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? money(result.getBigDecimal(1)) : null;
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw new IllegalStateException("Economy transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open economy transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private record LedgerEntry(
            UUID sender,
            UUID receiver,
            BigDecimal amount,
            BigDecimal fee,
            String reversalOf
    ) {
    }
}
