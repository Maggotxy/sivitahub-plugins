package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SignedSupportReceipt;
import cn.originalrpg.api.model.SupportCosmetic;
import cn.originalrpg.api.model.SupportCreditResult;
import cn.originalrpg.api.model.SupportCreditStatus;
import cn.originalrpg.api.model.SupportPaymentResult;
import cn.originalrpg.api.model.SupportProduct;
import cn.originalrpg.api.model.SupportPurchaseResult;
import cn.originalrpg.api.model.SupportReceipt;
import cn.originalrpg.api.service.SupportCreditService;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Signed-order issuance and cosmetic-only spending through the existing isolated
 * support-credit accounts and ledger. Order identity, quota, balance and audit
 * commit together; purchase identity, debit and entitlement also commit together.
 * No statement in this authority writes an earnable currency or gameplay item.
 */
public final class JdbcSupportCreditService implements SupportCreditService {
    private final DataSource dataSource;
    private final Executor executor;
    private final SupportCreditPolicy policy;
    private final SupportReceiptVerifier verifier;
    private final BooleanSupplier paymentEnabled;
    private final Clock clock;
    private final MechanismRegistry mechanisms;

    /** Compatibility construction is deliberately disabled; it cannot become an unsigned issuance backdoor. */
    public JdbcSupportCreditService(DataSource dataSource, Executor executor) {
        this(dataSource, executor, SupportCreditPolicy.disabled(), () -> false, Clock.systemUTC());
    }

    public JdbcSupportCreditService(DataSource dataSource, Executor executor, SupportCreditPolicy policy,
                                    MechanismRegistry mechanisms) {
        this(dataSource, executor, policy,
                () -> false, Clock.systemUTC(), Objects.requireNonNull(mechanisms, "mechanisms"));
    }

    JdbcSupportCreditService(DataSource dataSource, Executor executor, SupportCreditPolicy policy,
                             BooleanSupplier paymentEnabled, Clock clock) {
        this(dataSource, executor, policy, paymentEnabled, clock, null);
    }

    private JdbcSupportCreditService(DataSource dataSource, Executor executor, SupportCreditPolicy policy,
                             BooleanSupplier paymentEnabled, Clock clock, MechanismRegistry mechanisms) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.verifier = new SupportReceiptVerifier(policy);
        this.paymentEnabled = Objects.requireNonNull(paymentEnabled, "paymentEnabled");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mechanisms = mechanisms;
    }

    @Override public List<SupportProduct> catalog() { return policy.catalog(); }

    @Override public CompletionStage<Long> balance(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(0L);
        return AsyncJdbc.supply(executor, () -> transaction(connection -> readBalance(connection, playerId)));
    }

    @Override public CompletionStage<SupportCreditStatus> status(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return AsyncJdbc.supply(executor, () -> transaction(connection -> new SupportCreditStatus(
                readBalance(connection, playerId), readQuota(connection, playerScope(playerId), week(clock.instant()), false),
                policy.playerWeeklyLimit(), readEntitlements(connection, playerId))));
    }

    @Override public CompletionStage<Set<SupportCosmetic>> entitlements(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return AsyncJdbc.supply(executor, () -> transaction(connection -> readEntitlements(connection, playerId)));
    }

    @Override public CompletionStage<SupportPaymentResult> acceptReceipt(SignedSupportReceipt signed) {
        if (!policy.enabled() || (mechanisms == null && !paymentEnabled.getAsBoolean())) return CompletableFuture.completedFuture(
                payment(SupportPaymentResult.Status.DISABLED, "", 0, 0, 0));
        // Cryptography and JDBC both run on the bounded core executor, never on a Paper event thread.
        return AsyncJdbc.supply(executor, () -> {
            SupportReceiptVerifier.Verified verified;
            try { verified = verifier.authenticate(signed); }
            catch (SupportReceiptVerifier.Rejected rejected) { return payment(rejected.status(), "", 0, 0, 0); }
            return transaction(connection -> accept(connection, verified));
        });
    }

    private SupportPaymentResult accept(Connection connection, SupportReceiptVerifier.Verified verified) throws SQLException {
        SupportReceipt receipt = verified.receipt();
        // This is the first business lock. A disable on another node updates
        // the same authority row and cannot overtake an admitted transaction.
        boolean gate = mechanisms == null ? paymentEnabled.getAsBoolean()
                : mechanisms.requireEnabled(connection, MechanismRegistry.SUPPORT_CREDIT_PAYMENT);
        if (!gate) return payment(SupportPaymentResult.Status.DISABLED, receipt.orderId(), 0, 0, 0);
        PaymentOrder previous = previousOrder(connection, receipt.orderId());
        if (previous != null) return replayOrder(connection, previous, verified);
        SupportPaymentResult.Status time = verifier.newOrderTimeStatus(receipt, clock.instant());
        if (time != null) return payment(time, receipt.orderId(), 0, 0, 0);
        if (receipt.creditUnits() > policy.maximumPerOrder()) return payment(
                SupportPaymentResult.Status.PER_ORDER_LIMIT, receipt.orderId(), 0, 0, 0);
        String week = week(clock.instant());
        // Every importer takes the same lock order. Global UTC quota serializes
        // competing orders; the player's quota and account follow, never reverse.
        long globalIssued = lockQuota(connection, "global", week);
        long playerIssued = lockQuota(connection, playerScope(receipt.playerId()), week);
        previous = previousOrder(connection, receipt.orderId());
        if (previous != null) return replayOrder(connection, previous, verified);
        Instant decisionAt = clock.instant();
        if (!week.equals(week(decisionAt))) return payment(SupportPaymentResult.Status.RETRY_REQUIRED, receipt.orderId(), 0, 0, playerIssued);
        time = verifier.newOrderTimeStatus(receipt, decisionAt);
        if (time != null) return payment(time, receipt.orderId(), 0, 0, playerIssued);
        if (receipt.creditUnits() > policy.playerWeeklyLimit() - playerIssued) return payment(
                SupportPaymentResult.Status.PLAYER_WEEK_LIMIT, receipt.orderId(), readBalance(connection, receipt.playerId()), 0, playerIssued);
        if (receipt.creditUnits() > policy.globalWeeklyLimit() - globalIssued) return payment(
                SupportPaymentResult.Status.GLOBAL_WEEK_LIMIT, receipt.orderId(), readBalance(connection, receipt.playerId()), 0, playerIssued);
        long balance = lockBalance(connection, receipt.playerId());
        // A production transaction still owns the gate row until commit.
        if (mechanisms == null && !paymentEnabled.getAsBoolean()) return payment(SupportPaymentResult.Status.DISABLED, receipt.orderId(), balance, 0, playerIssued);
        // Account contention may outlast a receipt or the UTC quota week. The
        // final admission decision must use time after every blocking lock.
        decisionAt = clock.instant();
        if (!week.equals(week(decisionAt))) return payment(SupportPaymentResult.Status.RETRY_REQUIRED, receipt.orderId(), balance, 0, playerIssued);
        time = verifier.newOrderTimeStatus(receipt, decisionAt);
        if (time != null) return payment(time, receipt.orderId(), balance, 0, playerIssued);
        if (balance > SupportCreditPolicy.MAX_BALANCE - receipt.creditUnits()) return payment(
                SupportPaymentResult.Status.BALANCE_LIMIT, receipt.orderId(), balance, 0, playerIssued);
        if (!insertOrder(connection, verified, week)) {
            return replayOrder(connection, Objects.requireNonNull(previousOrder(connection, receipt.orderId())), verified);
        }
        String ledgerKey = orderLedgerKey(receipt.orderId());
        insertLedger(connection, receipt.playerId(), "GRANT", receipt.creditUnits(), ledgerKey, "signed-order:" + receipt.keyId());
        updateBalance(connection, receipt.playerId(), balance + receipt.creditUnits());
        writeQuota(connection, "global", week, globalIssued + receipt.creditUnits());
        writeQuota(connection, playerScope(receipt.playerId()), week, playerIssued + receipt.creditUnits());
        return payment(SupportPaymentResult.Status.SUCCESS, receipt.orderId(), balance + receipt.creditUnits(),
                receipt.creditUnits(), playerIssued + receipt.creditUnits());
    }

    @Override public CompletionStage<Boolean> paymentAcceptanceEnabled() {
        if (!policy.enabled()) return CompletableFuture.completedFuture(false);
        if (mechanisms == null) return CompletableFuture.completedFuture(paymentEnabled.getAsBoolean());
        return AsyncJdbc.supply(executor, () -> transaction(connection ->
                mechanisms.isEnabled(connection, MechanismRegistry.SUPPORT_CREDIT_PAYMENT)));
    }

    @Override public CompletionStage<Boolean> setPaymentAcceptanceEnabled(boolean enabled, String actor) {
        if (mechanisms == null) return CompletableFuture.failedFuture(new UnsupportedOperationException("No persistent payment control"));
        if (actor == null || actor.isBlank() || actor.length() > 64 || actor.codePoints().anyMatch(Character::isISOControl)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid support payment control actor"));
        }
        return AsyncJdbc.supply(executor, () -> {
            mechanisms.setEnabled(MechanismRegistry.SUPPORT_CREDIT_PAYMENT, enabled, actor);
            return policy.enabled() && transaction(connection -> mechanisms.isEnabled(connection, MechanismRegistry.SUPPORT_CREDIT_PAYMENT));
        });
    }

    private SupportPaymentResult replayOrder(Connection connection, PaymentOrder previous,
                                             SupportReceiptVerifier.Verified verified) throws SQLException {
        SupportReceipt receipt = verified.receipt();
        if (!previous.playerId().equals(receipt.playerId()) || !previous.payloadHash().equals(verified.payloadHash())) {
            return payment(SupportPaymentResult.Status.CONFLICT, receipt.orderId(), 0, 0, 0);
        }
        return payment(SupportPaymentResult.Status.DUPLICATE, receipt.orderId(), readBalance(connection, receipt.playerId()), 0,
                readQuota(connection, playerScope(receipt.playerId()), week(clock.instant()), false));
    }

    @Override public CompletionStage<SupportPurchaseResult> purchaseCosmetic(
            UUID playerId, SupportCosmetic cosmetic, long expectedPrice, UUID operationId) {
        if (playerId == null || cosmetic == null || operationId == null || expectedPrice < 1) return CompletableFuture.completedFuture(
                purchase(SupportPurchaseResult.Status.INVALID, 0, 0, cosmetic));
        if (!policy.enabled()) return CompletableFuture.completedFuture(purchase(SupportPurchaseResult.Status.DISABLED, 0, 0, cosmetic));
        return AsyncJdbc.supply(executor, () -> transaction(connection -> {
            CosmeticPurchase previous = previousPurchase(connection, operationId);
            if (previous != null) return replayPurchase(connection, previous, playerId, cosmetic, expectedPrice);
            Long cost = policy.cosmeticPrices().get(cosmetic);
            if (cost == null) return purchase(SupportPurchaseResult.Status.INVALID, 0, 0, cosmetic);
            if (cost != expectedPrice) return purchase(SupportPurchaseResult.Status.PRICE_CHANGED, 0, 0, cosmetic);
            long balance = lockBalance(connection, playerId);
            previous = previousPurchase(connection, operationId);
            if (previous != null) return replayPurchase(connection, previous, playerId, cosmetic, expectedPrice);
            if (readEntitlements(connection, playerId).contains(cosmetic)) {
                return purchase(SupportPurchaseResult.Status.ALREADY_OWNED, balance, 0, cosmetic);
            }
            if (balance < cost) return purchase(SupportPurchaseResult.Status.INSUFFICIENT_FUNDS, balance, 0, cosmetic);
            if (!insertPurchase(connection, operationId, playerId, cosmetic, cost)) {
                return replayPurchase(connection, Objects.requireNonNull(previousPurchase(connection, operationId)), playerId, cosmetic, expectedPrice);
            }
            insertLedger(connection, playerId, "SPEND", cost, "support:cosmetic:" + operationId, "cosmetic:" + cosmetic.id());
            updateBalance(connection, playerId, balance - cost);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_support_cosmetic_entitlements (player_uuid, cosmetic_id, purchase_id)
                    VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, playerId.toString()); insert.setString(2, cosmetic.id()); insert.setString(3, operationId.toString());
                insert.executeUpdate();
            }
            return purchase(SupportPurchaseResult.Status.SUCCESS, balance - cost, cost, cosmetic);
        }));
    }

    private SupportPurchaseResult replayPurchase(Connection connection, CosmeticPurchase previous, UUID playerId,
                                                  SupportCosmetic cosmetic, long expectedPrice) throws SQLException {
        if (!previous.playerId().equals(playerId) || !previous.cosmetic().equals(cosmetic.id()) || previous.cost() != expectedPrice) {
            return purchase(SupportPurchaseResult.Status.CONFLICT, 0, 0, cosmetic);
        }
        if (!readEntitlements(connection, playerId).contains(cosmetic)) throw new SQLException("Cosmetic receipt has no matching entitlement");
        return purchase(SupportPurchaseResult.Status.DUPLICATE, readBalance(connection, playerId), 0, cosmetic);
    }

    /** No unsigned issuance remains reachable, even through direct service calls from another plugin. */
    @Deprecated(forRemoval = false)
    @Override public CompletionStage<SupportCreditResult> grant(UUID playerId, long amount, String key, String reason) {
        return CompletableFuture.completedFuture(new SupportCreditResult(SupportCreditResult.Status.LEGACY_DISABLED, 0, 0));
    }

    /** No arbitrary target/price/reason can substitute for a committed reviewed entitlement. */
    @Deprecated(forRemoval = false)
    @Override public CompletionStage<SupportCreditResult> spend(UUID playerId, long amount, SupportCreditTarget target, String key, String reason) {
        return CompletableFuture.completedFuture(new SupportCreditResult(target == null || !target.allowedByC012()
                ? SupportCreditResult.Status.CROSS_CURRENCY_FORBIDDEN : SupportCreditResult.Status.LEGACY_DISABLED, 0, 0));
    }

    private static PaymentOrder previousOrder(Connection connection, String orderId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid, payload_hash FROM rpg_support_payment_orders WHERE order_id = ?")) {
            query.setString(1, orderId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new PaymentOrder(UUID.fromString(rows.getString(1)), rows.getString(2)) : null;
            }
        }
    }

    private static boolean insertOrder(Connection connection, SupportReceiptVerifier.Verified verified, String week) throws SQLException {
        SupportReceipt receipt = verified.receipt();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_support_payment_orders
                (order_id, player_uuid, key_id, audience, credit_units, issued_at, expires_at,
                 payload_hash, signed_payload, signature, ledger_key, week_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, receipt.orderId()); insert.setString(2, receipt.playerId().toString());
            insert.setString(3, receipt.keyId()); insert.setString(4, receipt.audience()); insert.setLong(5, receipt.creditUnits());
            insert.setTimestamp(6, Timestamp.from(receipt.issuedAt())); insert.setTimestamp(7, Timestamp.from(receipt.expiresAt()));
            insert.setString(8, verified.payloadHash()); insert.setString(9, verified.payload()); insert.setString(10, verified.signature());
            insert.setString(11, orderLedgerKey(receipt.orderId())); insert.setString(12, week);
            return insert.executeUpdate() == 1;
        }
    }

    private static CosmeticPurchase previousPurchase(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid, cosmetic_id, credit_cost FROM rpg_support_cosmetic_purchases WHERE operation_id = ?")) {
            query.setString(1, operationId.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new CosmeticPurchase(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getLong(3)) : null;
            }
        }
    }

    private static boolean insertPurchase(Connection connection, UUID operation, UUID player, SupportCosmetic cosmetic, long cost) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_support_cosmetic_purchases (operation_id, player_uuid, cosmetic_id, credit_cost, ledger_key)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, operation.toString()); insert.setString(2, player.toString());
            insert.setString(3, cosmetic.id()); insert.setLong(4, cost);
            insert.setString(5, "support:cosmetic:" + operation);
            return insert.executeUpdate() == 1;
        }
    }

    private static Set<SupportCosmetic> readEntitlements(Connection connection, UUID player) throws SQLException {
        Set<SupportCosmetic> result = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT cosmetic_id FROM rpg_support_cosmetic_entitlements WHERE player_uuid = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    result.add(SupportCosmetic.parse(id).orElseThrow(() -> new SQLException("Unknown persisted support cosmetic: " + id)));
                }
            }
        }
        return Set.copyOf(result);
    }

    private static long readBalance(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT balance FROM rpg_support_credit_accounts WHERE player_uuid = ?")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) { return rows.next() ? validBalance(rows.getLong(1)) : 0; }
        }
    }

    private static long lockBalance(Connection connection, UUID player) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_support_credit_accounts (player_uuid, balance) VALUES (?, 0) ON CONFLICT DO NOTHING")) {
            insert.setString(1, player.toString()); insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT balance FROM rpg_support_credit_accounts WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, player.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing support-credit account");
                return validBalance(rows.getLong(1));
            }
        }
    }

    private static long validBalance(long balance) throws SQLException {
        if (balance < 0 || balance > SupportCreditPolicy.MAX_BALANCE) throw new SQLException("Support-credit balance is outside its reviewed bounds");
        return balance;
    }

    private static void updateBalance(Connection connection, UUID player, long balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_support_credit_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setLong(1, balance); update.setString(2, player.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Support-credit account update was lost");
        }
    }

    private static long lockQuota(Connection connection, String scope, String week) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_support_credit_limits (scope_id, week_key, issued_units) VALUES (?, ?, 0)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, scope); insert.setString(2, week); insert.executeUpdate();
        }
        return readQuota(connection, scope, week, true);
    }

    private static long readQuota(Connection connection, String scope, String week, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT issued_units FROM rpg_support_credit_limits WHERE scope_id = ? AND week_key = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, scope); query.setString(2, week);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    if (lock) throw new SQLException("Missing locked support quota");
                    return 0;
                }
                long issued = rows.getLong(1);
                if (issued < 0) throw new SQLException("Negative persisted support quota");
                return issued;
            }
        }
    }

    private static void writeQuota(Connection connection, String scope, String week, long issued) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_support_credit_limits SET issued_units = ?, updated_at = CURRENT_TIMESTAMP
                WHERE scope_id = ? AND week_key = ?
                """)) {
            update.setLong(1, issued); update.setString(2, scope); update.setString(3, week);
            if (update.executeUpdate() != 1) throw new SQLException("Support quota update was lost");
        }
    }

    private static void insertLedger(Connection connection, UUID player, String direction, long amount, String key, String reason) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_support_credit_ledger (id, idempotency_key, player_uuid, amount, direction, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, key); insert.setString(3, player.toString());
            insert.setLong(4, amount); insert.setString(5, direction); insert.setString(6, reason); insert.executeUpdate();
        }
    }

    private SupportPaymentResult payment(SupportPaymentResult.Status status, String order, long balance, long credited, long issued) {
        return new SupportPaymentResult(status, order, balance, credited, issued, policy.playerWeeklyLimit());
    }
    private static SupportPurchaseResult purchase(SupportPurchaseResult.Status status, long balance, long cost, SupportCosmetic cosmetic) {
        return new SupportPurchaseResult(status, balance, cost, cosmetic);
    }
    private static String week(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
    }
    private static String playerScope(UUID player) { return "player:" + player; }
    private static String orderLedgerKey(String orderId) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("sivita:support-order:v1:" + orderId).getBytes(StandardCharsets.UTF_8));
            return "support:order:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private <T> T transaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.getAutoCommit()) connection.setAutoCommit(false);
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception error) {
                try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Support-credit transaction failed", error);
            }
        } catch (SQLException error) { throw new IllegalStateException("Could not open support-credit transaction", error); }
    }

    private record PaymentOrder(UUID playerId, String payloadHash) { }
    private record CosmeticPurchase(UUID playerId, String cosmetic, long cost) { }
    @FunctionalInterface private interface SqlOperation<T> { T apply(Connection connection) throws SQLException; }
}
