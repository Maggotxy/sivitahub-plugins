package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.MerchantTransactionResult;
import cn.originalrpg.api.model.MerchantUsage;
import cn.originalrpg.api.service.MerchantService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcMerchantService implements MerchantService {
    private static final Pattern ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final Pattern PERIOD = Pattern.compile("[a-zA-Z0-9._:-]{1,32}");
    private static final int MAX_PAYLOAD = 2_000_000;
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000000.00");
    private static final BigDecimal MAX_GOLD = new BigDecimal("1000000000000.00");

    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;

    public JdbcMerchantService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
    }

    @Override
    public CompletionStage<Map<String, MerchantUsage>> usage(UUID playerId, String periodKey) {
        if (playerId == null || periodKey == null || !PERIOD.matcher(periodKey).matches()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Map<String, int[]> mutable = new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT offer_id, direction, used_units FROM rpg_merchant_limits
                    WHERE player_uuid = ? AND period_key = ?
                    """)) {
                query.setString(1, playerId.toString());
                query.setString(2, periodKey);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        int[] values = mutable.computeIfAbsent(result.getString("offer_id"), ignored -> new int[2]);
                        if ("PURCHASE".equals(result.getString("direction"))) values[0] = result.getInt("used_units");
                        else if ("BUYBACK".equals(result.getString("direction"))) values[1] = result.getInt("used_units");
                    }
                }
            }
            Map<String, MerchantUsage> usage = new HashMap<>();
            mutable.forEach((offer, values) -> usage.put(offer, new MerchantUsage(values[0], values[1])));
            return Map.copyOf(usage);
        }));
    }

    @Override
    public CompletionStage<MerchantTransactionResult> purchase(
            UUID playerId, String offerId, String periodKey, int units, int maximumUnits,
            BigDecimal unitPrice, String itemPayload, String idempotencyKey
    ) {
        if (!valid(playerId, offerId, periodKey, units, maximumUnits, unitPrice, idempotencyKey)
                || itemPayload == null || itemPayload.isBlank() || itemPayload.length() > MAX_PAYLOAD) {
            return invalid();
        }
        return transact(playerId, offerId, periodKey, "PURCHASE", units, maximumUnits,
                money(unitPrice), itemPayload, idempotencyKey);
    }

    @Override
    public CompletionStage<MerchantTransactionResult> buyback(
            UUID playerId, String offerId, String periodKey, int units, int maximumUnits,
            BigDecimal unitPrice, String idempotencyKey
    ) {
        if (!valid(playerId, offerId, periodKey, units, maximumUnits, unitPrice, idempotencyKey)) return invalid();
        return transact(playerId, offerId, periodKey, "BUYBACK", units, maximumUnits,
                money(unitPrice), null, idempotencyKey);
    }

    private CompletionStage<MerchantTransactionResult> transact(
            UUID playerId, String offerId, String periodKey, String direction, int units, int maximumUnits,
            BigDecimal unitPrice, String payload, String key
    ) {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            BigDecimal balance = loadOrCreateAccount(connection, playerId);
            MerchantTransactionResult replay = replay(connection, key, balance, maximumUnits);
            if (replay != null) return replay;
            ensureLimit(connection, playerId, offerId, periodKey, direction);
            int used = lockLimit(connection, playerId, offerId, periodKey, direction);
            if (used > maximumUnits - units) {
                return result(MerchantTransactionResult.Status.LIMIT_REACHED, BigDecimal.ZERO,
                        balance, used, maximumUnits, null);
            }
            balance = lockBalance(connection, playerId);
            replay = replay(connection, key, balance, maximumUnits);
            if (replay != null) return replay;
            BigDecimal total = money(unitPrice.multiply(BigDecimal.valueOf(units)));
            UUID deliveryId = null;
            BigDecimal delta;
            if (direction.equals("PURCHASE")) {
                if (balance.compareTo(total) < 0) {
                    return result(MerchantTransactionResult.Status.INSUFFICIENT_FUNDS, BigDecimal.ZERO,
                            balance, used, maximumUnits, null);
                }
                balance = money(balance.subtract(total));
                delta = total.negate();
                deliveryId = UUID.randomUUID();
                insertDelivery(connection, deliveryId, playerId, payload, "black-market:" + offerId);
                insertGoldLedger(connection, key, playerId, null, total, "black-market-purchase:" + offerId);
            } else {
                BigDecimal updated = money(balance.add(total));
                if (updated.compareTo(MAX_GOLD) > 0) {
                    return result(MerchantTransactionResult.Status.BALANCE_LIMIT, BigDecimal.ZERO,
                            balance, used, maximumUnits, null);
                }
                balance = updated;
                delta = total;
                insertGoldLedger(connection, key, null, playerId, total, "black-market-buyback:" + offerId);
            }
            updateBalance(connection, playerId, balance);
            updateLimit(connection, playerId, offerId, periodKey, direction, used + units);
            insertMerchantLedger(connection, key, playerId, offerId, periodKey, direction, units, delta, deliveryId);
            return result(MerchantTransactionResult.Status.SUCCESS, delta, balance,
                    used + units, maximumUnits, deliveryId);
        }));
    }

    private MerchantTransactionResult replay(
            Connection connection, String key, BigDecimal balance, int maximumUnits
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT units, gold_delta, delivery_id FROM rpg_merchant_ledger WHERE idempotency_key = ?
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                String delivery = result.getString("delivery_id");
                return result(MerchantTransactionResult.Status.DUPLICATE, result.getBigDecimal("gold_delta"),
                        balance, result.getInt("units"), maximumUnits,
                        delivery == null ? null : UUID.fromString(delivery));
            }
        }
    }

    private void ensureLimit(Connection connection, UUID playerId, String offerId, String period, String direction)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_merchant_limits (player_uuid, offer_id, period_key, direction, used_units)
                VALUES (?, ?, ?, ?, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, offerId);
            insert.setString(3, period);
            insert.setString(4, direction);
            insert.executeUpdate();
        }
    }

    private int lockLimit(Connection connection, UUID playerId, String offerId, String period, String direction)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT used_units FROM rpg_merchant_limits
                WHERE player_uuid = ? AND offer_id = ? AND period_key = ? AND direction = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, offerId);
            query.setString(3, period);
            query.setString(4, direction);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Merchant limit row disappeared");
                return result.getInt(1);
            }
        }
    }

    private void updateLimit(
            Connection connection, UUID playerId, String offerId, String period, String direction, int used
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_merchant_limits SET used_units = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND offer_id = ? AND period_key = ? AND direction = ?
                """)) {
            update.setInt(1, used);
            update.setString(2, playerId.toString());
            update.setString(3, offerId);
            update.setString(4, period);
            update.setString(5, direction);
            update.executeUpdate();
        }
    }

    private void insertMerchantLedger(
            Connection connection, String key, UUID playerId, String offerId, String period, String direction,
            int units, BigDecimal delta, UUID deliveryId
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_merchant_ledger
                    (id, idempotency_key, player_uuid, offer_id, period_key, direction, units, gold_delta, delivery_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, playerId.toString());
            insert.setString(4, offerId);
            insert.setString(5, period);
            insert.setString(6, direction);
            insert.setInt(7, units);
            insert.setBigDecimal(8, delta);
            insert.setString(9, deliveryId == null ? null : deliveryId.toString());
            insert.executeUpdate();
        }
    }

    private void insertGoldLedger(
            Connection connection, String key, UUID sender, UUID receiver, BigDecimal amount, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, ?, ?, 0, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, "merchant:" + key);
            insert.setString(3, sender == null ? null : sender.toString());
            insert.setString(4, receiver == null ? null : receiver.toString());
            insert.setBigDecimal(5, amount);
            insert.setString(6, reason);
            insert.executeUpdate();
        }
    }

    private void insertDelivery(
            Connection connection, UUID deliveryId, UUID playerId, String payload, String source
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING')
                """)) {
            insert.setString(1, deliveryId.toString());
            insert.setString(2, playerId.toString());
            insert.setString(3, payload);
            insert.setString(4, source);
            insert.executeUpdate();
        }
    }

    private BigDecimal loadOrCreateAccount(Connection connection, UUID playerId) throws SQLException {
        BigDecimal existing = selectBalance(connection, playerId, false);
        if (existing != null) return existing;
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
            update.setBigDecimal(1, balance);
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
    }

    private static boolean valid(
            UUID playerId, String offerId, String periodKey, int units, int maximumUnits,
            BigDecimal unitPrice, String idempotencyKey
    ) {
        if (unitPrice == null) return false;
        BigDecimal normalizedPrice = money(unitPrice);
        return playerId != null && offerId != null && ID.matcher(offerId).matches()
                && periodKey != null && PERIOD.matcher(periodKey).matches()
                && units > 0 && units <= 1000 && maximumUnits > 0 && maximumUnits <= 100_000
                && normalizedPrice.signum() > 0 && normalizedPrice.compareTo(MAX_PRICE) <= 0
                && idempotencyKey != null && !idempotencyKey.isBlank() && idempotencyKey.length() <= 80;
    }

    private static CompletionStage<MerchantTransactionResult> invalid() {
        return CompletableFuture.completedFuture(result(MerchantTransactionResult.Status.INVALID,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, null));
    }

    private static MerchantTransactionResult result(
            MerchantTransactionResult.Status status, BigDecimal delta, BigDecimal balance,
            int used, int maximum, UUID deliveryId
    ) {
        return new MerchantTransactionResult(status, money(delta), money(balance), used, maximum,
                Optional.ofNullable(deliveryId));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Merchant transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open merchant transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
