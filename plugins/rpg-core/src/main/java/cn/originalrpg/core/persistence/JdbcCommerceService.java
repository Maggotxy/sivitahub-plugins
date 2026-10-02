package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommercePurchaseResult;
import cn.originalrpg.api.model.CommerceResult;
import cn.originalrpg.api.model.CommerceStatus;
import cn.originalrpg.api.model.CreditResult;
import cn.originalrpg.api.model.RewardClaimResult;
import cn.originalrpg.api.model.SupplyPurchaseResult;
import cn.originalrpg.api.service.CommerceService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcCommerceService implements CommerceService {
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._:-]{3,96}");
    private static final Pattern CREDIT_PURCHASE_KEY = Pattern.compile("[A-Za-z0-9._:-]{3,80}");
    private static final Pattern PERIOD_KEY = Pattern.compile("[A-Za-z0-9._:-]{3,32}");
    private static final Pattern REWARD_ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final Pattern PRODUCT_ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final BigDecimal MAX_PAYMENT = new BigDecimal("1000000.00");
    private static final BigDecimal MAX_GOLD_COST = new BigDecimal("1000000000.00");
    private static final BigDecimal MAX_LIFETIME_PAID = new BigDecimal("1000000000000.00");
    private static final long MAX_CREDITS = 1_000_000_000L;
    private static final int MIN_MEMBERSHIP_DAYS = 3;
    private static final int MAX_MEMBERSHIP_DAYS = 30;
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;
    private final OperationalStarlightPolicy starlightPolicy;
    private final Clock clock;
    /** C-005 Kill Switch；为 null 表示未接入注册表。 */
    private final MechanismRegistry mechanisms;

    public JdbcCommerceService(DataSource dataSource, Executor executor) {
        this(dataSource, executor, BigDecimal.ZERO, OperationalStarlightPolicy.testDefault(), Clock.systemUTC(), null);
    }

    public JdbcCommerceService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this(dataSource, executor, startingBalance, OperationalStarlightPolicy.testDefault(), Clock.systemUTC(), null);
    }

    public JdbcCommerceService(
            DataSource dataSource,
            Executor executor,
            BigDecimal startingBalance,
            OperationalStarlightPolicy starlightPolicy
    ) {
        this(dataSource, executor, startingBalance, starlightPolicy, Clock.systemUTC(), null);
    }

    public JdbcCommerceService(
            DataSource dataSource,
            Executor executor,
            BigDecimal startingBalance,
            OperationalStarlightPolicy starlightPolicy,
            MechanismRegistry mechanisms
    ) {
        this(dataSource, executor, startingBalance, starlightPolicy, Clock.systemUTC(), mechanisms);
    }

    JdbcCommerceService(
            DataSource dataSource,
            Executor executor,
            BigDecimal startingBalance,
            OperationalStarlightPolicy starlightPolicy,
            Clock clock
    ) {
        this(dataSource, executor, startingBalance, starlightPolicy, clock, null);
    }

    JdbcCommerceService(
            DataSource dataSource,
            Executor executor,
            BigDecimal startingBalance,
            OperationalStarlightPolicy starlightPolicy,
            Clock clock,
            MechanismRegistry mechanisms
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
        this.starlightPolicy = java.util.Objects.requireNonNull(starlightPolicy, "starlightPolicy");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.mechanisms = mechanisms;
    }

    @Override
    public CompletionStage<CommerceStatus> status(UUID playerId) {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> loadOrCreate(connection, playerId, false)));
    }

    @Override
    public CompletionStage<CommerceResult> recordPayment(
            UUID playerId,
            BigDecimal amount,
            String externalReference,
            int membershipDays,
            String operator
    ) {
        if (playerId == null || amount == null || externalReference == null || operator == null
                || !REFERENCE.matcher(externalReference).matches()
                || !validMembershipDays(membershipDays)
                || operator.isBlank() || operator.length() > 64) {
            return CompletableFuture.completedFuture(new CommerceResult(CommerceResult.Status.INVALID, null));
        }
        BigDecimal normalized = money(amount);
        if (normalized.signum() <= 0 || normalized.compareTo(MAX_PAYMENT) > 0) {
            return CompletableFuture.completedFuture(new CommerceResult(CommerceResult.Status.INVALID, null));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            CommerceStatus current = loadOrCreate(connection, playerId, true);
            if (paymentExists(connection, externalReference)) {
                return new CommerceResult(CommerceResult.Status.DUPLICATE, current);
            }
            BigDecimal updatedLifetime = money(current.lifetimePaid().add(normalized));
            if (updatedLifetime.compareTo(MAX_LIFETIME_PAID) > 0) {
                return new CommerceResult(CommerceResult.Status.LIFETIME_LIMIT_REACHED, current);
            }
            Instant memberUntil = current.memberUntil();
            if (membershipDays > 0) {
                Instant now = clock.instant();
                Instant base = memberUntil != null && memberUntil.isAfter(now) ? memberUntil : now;
                Instant proposed = base.plus(membershipDays, ChronoUnit.DAYS);
                if (proposed.isAfter(now.plus(MAX_MEMBERSHIP_DAYS, ChronoUnit.DAYS))) {
                    return new CommerceResult(CommerceResult.Status.MEMBERSHIP_LIMIT_REACHED, current);
                }
                memberUntil = proposed;
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_commerce_accounts
                    SET lifetime_paid = ?, member_until = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ?
                    """)) {
                update.setBigDecimal(1, updatedLifetime);
                update.setTimestamp(2, memberUntil == null ? null : Timestamp.from(memberUntil));
                update.setString(3, playerId.toString());
                update.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_payment_ledger
                    (id, external_reference, player_uuid, amount, membership_days, operator_name)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, externalReference);
                insert.setString(3, playerId.toString());
                insert.setBigDecimal(4, normalized);
                insert.setInt(5, membershipDays);
                insert.setString(6, operator);
                insert.executeUpdate();
            }
            return new CommerceResult(CommerceResult.Status.SUCCESS, loadOrCreate(connection, playerId, false));
        }));
    }

    @Override
    public CompletionStage<CreditResult> adjustCredits(
            UUID playerId, long delta, String externalReference, String operator
    ) {
        if (playerId == null || delta == 0 || delta == Long.MIN_VALUE || Math.abs(delta) > 1_000_000_000L
                || externalReference == null || !REFERENCE.matcher(externalReference).matches()
                || operator == null || operator.isBlank() || operator.length() > 64) {
            return CompletableFuture.completedFuture(creditResult(CreditResult.Status.INVALID, 0L, null));
        }
        if (delta > starlightPolicy.maximumPerGrant()) {
            return CompletableFuture.completedFuture(
                    creditResult(CreditResult.Status.PER_GRANT_LIMIT_REACHED, 0L, null));
        }
        // C-005：正向发放星辉是 faucet，受 Kill Switch 约束（扣减不受限）。
        if (delta > 0 && mechanisms != null
                && !mechanisms.requireEnabled(MechanismRegistry.STARLIGHT_GRANT)) {
            return CompletableFuture.completedFuture(creditResult(CreditResult.Status.INVALID, 0L, null));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            CommerceStatus current = loadOrCreate(connection, playerId, true);
            OperationalQuota quota = delta > 0 ? lockOperationalQuota(connection) : null;
            if (creditAdjustmentExists(connection, externalReference)) {
                return creditResult(CreditResult.Status.DUPLICATE, current.credits(), quota);
            }

            long updated;
            try {
                updated = Math.addExact(current.credits(), delta);
            } catch (ArithmeticException exception) {
                return creditResult(delta > 0 ? CreditResult.Status.BALANCE_LIMIT : CreditResult.Status.INVALID,
                        current.credits(), quota);
            }
            if (updated < 0) {
                return creditResult(CreditResult.Status.INSUFFICIENT_FUNDS, current.credits(), quota);
            }
            if (updated > MAX_CREDITS) {
                return creditResult(CreditResult.Status.BALANCE_LIMIT, current.credits(), quota);
            }
            if (quota != null && delta > starlightPolicy.dailyLimit() - quota.dailyGranted()) {
                return creditResult(CreditResult.Status.DAILY_LIMIT_REACHED, current.credits(), quota);
            }
            if (quota != null && delta > starlightPolicy.weeklyLimit() - quota.weeklyGranted()) {
                return creditResult(CreditResult.Status.WEEKLY_LIMIT_REACHED, current.credits(), quota);
            }
            if (!insertCreditAdjustment(connection, playerId, delta, externalReference, operator)) {
                return creditResult(CreditResult.Status.DUPLICATE, current.credits(), quota);
            }
            updateCredits(connection, playerId, updated);
            OperationalQuota updatedQuota = quota;
            if (quota != null) {
                updatedQuota = new OperationalQuota(quota.dayKey(), quota.weekKey(),
                        quota.dailyGranted() + delta, quota.weeklyGranted() + delta);
                updateOperationalQuota(connection, updatedQuota);
            }
            return creditResult(CreditResult.Status.SUCCESS, updated, updatedQuota);
        }));
    }

    @Override
    public CompletionStage<CommercePurchaseResult> purchaseWithCredits(
            UUID playerId, String productId, long creditCost, String itemPayload, String idempotencyKey,
            String periodKey, int maximumPurchases
    ) {
        if (playerId == null || productId == null || !PRODUCT_ID.matcher(productId).matches()
                || creditCost <= 0 || creditCost > 1_000_000_000L || itemPayload == null || itemPayload.isBlank()
                || itemPayload.length() > 2_000_000 || idempotencyKey == null
                || !CREDIT_PURCHASE_KEY.matcher(idempotencyKey).matches() || periodKey == null
                || !PERIOD_KEY.matcher(periodKey).matches() || maximumPurchases < 1 || maximumPurchases > 10_000) {
            return CompletableFuture.completedFuture(new CommercePurchaseResult(
                    CommercePurchaseResult.Status.INVALID, null, Optional.empty(), 0, 0));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            CommerceStatus current = loadOrCreate(connection, playerId, true);
            UUID previous = previousPurchase(connection, idempotencyKey);
            if (previous != null) {
                return new CommercePurchaseResult(CommercePurchaseResult.Status.DUPLICATE,
                        current, Optional.of(previous), purchaseCount(connection, playerId, productId, periodKey),
                        maximumPurchases);
            }
            int used = purchaseCount(connection, playerId, productId, periodKey);
            if (used >= maximumPurchases) {
                return new CommercePurchaseResult(CommercePurchaseResult.Status.LIMIT_REACHED,
                        current, Optional.empty(), used, maximumPurchases);
            }
            if (current.credits() < creditCost) {
                return new CommercePurchaseResult(CommercePurchaseResult.Status.INSUFFICIENT_CREDITS,
                        current, Optional.empty(), used, maximumPurchases);
            }
            UUID deliveryId = UUID.randomUUID();
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_commerce_accounts SET credits = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ?
                    """)) {
                update.setLong(1, current.credits() - creditCost);
                update.setString(2, playerId.toString());
                update.executeUpdate();
            }
            try (PreparedStatement delivery = connection.prepareStatement("""
                    INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """)) {
                delivery.setString(1, deliveryId.toString());
                delivery.setString(2, playerId.toString());
                delivery.setString(3, itemPayload);
                delivery.setString(4, "commerce-product:" + productId);
                delivery.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_commerce_purchases
                    (id, idempotency_key, player_uuid, product_id, period_key, credit_cost, delivery_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, idempotencyKey);
                insert.setString(3, playerId.toString());
                insert.setString(4, productId);
                insert.setString(5, periodKey);
                insert.setLong(6, creditCost);
                insert.setString(7, deliveryId.toString());
                insert.executeUpdate();
            }
            insertCreditSpend(connection, playerId, creditCost, idempotencyKey);
            return new CommercePurchaseResult(CommercePurchaseResult.Status.SUCCESS,
                    loadOrCreate(connection, playerId, false), Optional.of(deliveryId),
                    used + 1, maximumPurchases);
        }));
    }

    @Override
    public CompletionStage<CommercePurchaseResult> purchaseMembershipWithCredits(
            UUID playerId,
            String productId,
            long creditCost,
            int membershipDays,
            String idempotencyKey,
            String periodKey,
            int maximumPurchases
    ) {
        if (playerId == null || productId == null || !PRODUCT_ID.matcher(productId).matches()
                || creditCost <= 0 || creditCost > 1_000_000_000L
                || membershipDays < MIN_MEMBERSHIP_DAYS || membershipDays > MAX_MEMBERSHIP_DAYS
                || idempotencyKey == null
                || !CREDIT_PURCHASE_KEY.matcher(idempotencyKey).matches() || periodKey == null
                || !PERIOD_KEY.matcher(periodKey).matches() || maximumPurchases < 1 || maximumPurchases > 100) {
            return CompletableFuture.completedFuture(new CommercePurchaseResult(
                    CommercePurchaseResult.Status.INVALID, null, Optional.empty(), 0, 0));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            CommerceStatus current = loadOrCreate(connection, playerId, true);
            UUID previous = previousPurchase(connection, idempotencyKey);
            int used = purchaseCount(connection, playerId, productId, periodKey);
            if (previous != null) {
                return new CommercePurchaseResult(CommercePurchaseResult.Status.DUPLICATE,
                        current, Optional.empty(), used, maximumPurchases);
            }
            if (used >= maximumPurchases) {
                return new CommercePurchaseResult(CommercePurchaseResult.Status.LIMIT_REACHED,
                        current, Optional.empty(), used, maximumPurchases);
            }
            if (current.credits() < creditCost) {
                return new CommercePurchaseResult(CommercePurchaseResult.Status.INSUFFICIENT_CREDITS,
                        current, Optional.empty(), used, maximumPurchases);
            }
            Instant now = clock.instant();
            Instant base = current.memberUntil() != null && current.memberUntil().isAfter(now)
                    ? current.memberUntil() : now;
            Instant memberUntil = base.plus(membershipDays, ChronoUnit.DAYS);
            if (memberUntil.isAfter(now.plus(MAX_MEMBERSHIP_DAYS, ChronoUnit.DAYS))) {
                return new CommercePurchaseResult(CommercePurchaseResult.Status.MEMBERSHIP_LIMIT_REACHED,
                        current, Optional.empty(), used, maximumPurchases);
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_commerce_accounts
                    SET credits = ?, member_until = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ?
                    """)) {
                update.setLong(1, current.credits() - creditCost);
                update.setTimestamp(2, Timestamp.from(memberUntil));
                update.setString(3, playerId.toString());
                update.executeUpdate();
            }
            // The schema uses delivery_id as a non-null operation correlation. Membership has no
            // item delivery, so this UUID only identifies the audited purchase row.
            UUID operationId = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_commerce_purchases
                    (id, idempotency_key, player_uuid, product_id, period_key, credit_cost, delivery_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, idempotencyKey);
                insert.setString(3, playerId.toString());
                insert.setString(4, productId);
                insert.setString(5, periodKey);
                insert.setLong(6, creditCost);
                insert.setString(7, operationId.toString());
                insert.executeUpdate();
            }
            insertCreditSpend(connection, playerId, creditCost, idempotencyKey);
            return new CommercePurchaseResult(CommercePurchaseResult.Status.SUCCESS,
                    loadOrCreate(connection, playerId, false), Optional.empty(), used + 1, maximumPurchases);
        }));
    }

    @Override
    public CompletionStage<SupplyPurchaseResult> purchaseSupplyWithGold(
            UUID playerId,
            String productId,
            BigDecimal publicGoldCost,
            BigDecimal memberGoldCost,
            String itemPayload,
            String idempotencyKey,
            String periodKey,
            int maximumPurchases
    ) {
        BigDecimal publicPrice = publicGoldCost == null ? null : money(publicGoldCost);
        BigDecimal memberPrice = memberGoldCost == null ? null : money(memberGoldCost);
        if (playerId == null || productId == null || !PRODUCT_ID.matcher(productId).matches()
                || publicPrice == null || memberPrice == null
                || publicPrice.signum() <= 0 || publicPrice.compareTo(MAX_GOLD_COST) > 0
                || memberPrice.signum() <= 0 || memberPrice.compareTo(publicPrice) > 0
                || itemPayload == null || itemPayload.isBlank() || itemPayload.length() > 2_000_000
                || idempotencyKey == null || !REFERENCE.matcher(idempotencyKey).matches()
                || periodKey == null || !PERIOD_KEY.matcher(periodKey).matches()
                || maximumPurchases < 1 || maximumPurchases > 10_000) {
            return CompletableFuture.completedFuture(supplyResult(
                    SupplyPurchaseResult.Status.INVALID, BigDecimal.ZERO, BigDecimal.ZERO,
                    false, Optional.empty(), 0, 0));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            // Lock in the shared wealth order used by JdbcCurrencyExchangeService:
            // Gold first, then the commerce account. This avoids cross-service deadlocks.
            BigDecimal gold = loadOrCreateGold(connection, playerId, true);
            CommerceStatus account = loadOrCreate(connection, playerId, true);
            UUID previous = previousPurchase(connection, idempotencyKey);
            int used = purchaseCount(connection, playerId, productId, periodKey);
            boolean discounted = account.memberActive(Instant.now()) && memberPrice.compareTo(publicPrice) < 0;
            BigDecimal price = discounted ? memberPrice : publicPrice;
            if (previous != null) {
                return supplyResult(SupplyPurchaseResult.Status.DUPLICATE, BigDecimal.ZERO, gold,
                        discounted, Optional.of(previous), used, maximumPurchases);
            }
            if (used >= maximumPurchases) {
                return supplyResult(SupplyPurchaseResult.Status.LIMIT_REACHED, BigDecimal.ZERO, gold,
                        discounted, Optional.empty(), used, maximumPurchases);
            }
            if (gold.compareTo(price) < 0) {
                return supplyResult(SupplyPurchaseResult.Status.INSUFFICIENT_FUNDS, price, gold,
                        discounted, Optional.empty(), used, maximumPurchases);
            }

            BigDecimal updatedGold = money(gold.subtract(price));
            UUID deliveryId = UUID.randomUUID();
            try (PreparedStatement ledger = connection.prepareStatement("""
                    INSERT INTO rpg_ledger
                    (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                    VALUES (?, ?, ?, NULL, ?, 0, ?)
                    """)) {
                ledger.setString(1, UUID.randomUUID().toString());
                ledger.setString(2, idempotencyKey);
                ledger.setString(3, playerId.toString());
                ledger.setBigDecimal(4, price);
                ledger.setString(5, "system-supply:" + productId);
                ledger.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ?
                    """)) {
                update.setBigDecimal(1, updatedGold);
                update.setString(2, playerId.toString());
                update.executeUpdate();
            }
            try (PreparedStatement delivery = connection.prepareStatement("""
                    INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """)) {
                delivery.setString(1, deliveryId.toString());
                delivery.setString(2, playerId.toString());
                delivery.setString(3, itemPayload);
                delivery.setString(4, "commerce-supply:" + productId);
                delivery.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_commerce_purchases
                    (id, idempotency_key, player_uuid, product_id, period_key, credit_cost, gold_cost, delivery_id)
                    VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, idempotencyKey);
                insert.setString(3, playerId.toString());
                insert.setString(4, productId);
                insert.setString(5, periodKey);
                insert.setBigDecimal(6, price);
                insert.setString(7, deliveryId.toString());
                insert.executeUpdate();
            }
            return supplyResult(SupplyPurchaseResult.Status.SUCCESS, price, updatedGold,
                    discounted, Optional.of(deliveryId), used + 1, maximumPurchases);
        }));
    }

    private int purchaseCount(Connection connection, UUID playerId, String productId, String periodKey)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_commerce_purchases
                WHERE player_uuid = ? AND product_id = ? AND period_key = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, productId);
            query.setString(3, periodKey);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private BigDecimal loadOrCreateGold(Connection connection, UUID playerId, boolean lock) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
        String sql = "SELECT balance FROM rpg_accounts WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing Gold account after creation");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private static SupplyPurchaseResult supplyResult(
            SupplyPurchaseResult.Status status,
            BigDecimal charged,
            BigDecimal balance,
            boolean discounted,
            Optional<UUID> delivery,
            int used,
            int maximum
    ) {
        return new SupplyPurchaseResult(status, money(charged), money(balance), discounted,
                delivery, used, maximum);
    }

    @Override
    public CompletionStage<RewardClaimResult> claimReward(
            UUID playerId,
            String rewardId,
            BigDecimal requiredLifetimePaid,
            String itemPayload
    ) {
        if (playerId == null || rewardId == null || requiredLifetimePaid == null
                || itemPayload == null || itemPayload.isBlank() || itemPayload.length() > 2_000_000
                || !REWARD_ID.matcher(rewardId).matches() || requiredLifetimePaid.signum() < 0) {
            return CompletableFuture.completedFuture(
                    new RewardClaimResult(RewardClaimResult.Status.INVALID, null, Optional.empty()));
        }
        // C-005：付费奖励领取是发物口，由星辉/商务机制开关统一熔断。
        if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.STARLIGHT_GRANT)) {
            return CompletableFuture.completedFuture(
                    new RewardClaimResult(RewardClaimResult.Status.INVALID, null, Optional.empty()));
        }
        BigDecimal required = money(requiredLifetimePaid);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            CommerceStatus current = loadOrCreate(connection, playerId, true);
            if (current.claimedRewards().contains(rewardId)) {
                return new RewardClaimResult(RewardClaimResult.Status.ALREADY_CLAIMED, current, Optional.empty());
            }
            if (current.lifetimePaid().compareTo(required) < 0) {
                return new RewardClaimResult(RewardClaimResult.Status.NOT_ELIGIBLE, current, Optional.empty());
            }
            UUID deliveryId = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_commerce_reward_claims
                    (player_uuid, reward_id, required_lifetime_paid) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setString(2, rewardId);
                insert.setBigDecimal(3, required);
                insert.executeUpdate();
            }
            try (PreparedStatement delivery = connection.prepareStatement("""
                    INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """)) {
                delivery.setString(1, deliveryId.toString());
                delivery.setString(2, playerId.toString());
                delivery.setString(3, itemPayload);
                delivery.setString(4, "commerce-reward:" + rewardId);
                delivery.executeUpdate();
            }
            return new RewardClaimResult(RewardClaimResult.Status.SUCCESS,
                    loadOrCreate(connection, playerId, false), Optional.of(deliveryId));
        }));
    }

    private CommerceStatus loadOrCreate(Connection connection, UUID playerId, boolean lock) throws SQLException {
        CommerceStatus existing = select(connection, playerId, lock);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_commerce_accounts (player_uuid, lifetime_paid, credits) VALUES (?, ?, 0) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, BigDecimal.ZERO.setScale(2));
            insert.executeUpdate();
        }
        return select(connection, playerId, lock);
    }

    private CommerceStatus select(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT lifetime_paid, credits, member_until FROM rpg_commerce_accounts WHERE player_uuid = ?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Timestamp until = result.getTimestamp("member_until");
                return new CommerceStatus(
                        playerId,
                        money(result.getBigDecimal("lifetime_paid")),
                        result.getLong("credits"),
                        until == null ? null : until.toInstant(),
                        claimedRewards(connection, playerId)
                );
            }
        }
    }

    private Set<String> claimedRewards(Connection connection, UUID playerId) throws SQLException {
        Set<String> rewards = new HashSet<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT reward_id FROM rpg_commerce_reward_claims WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rewards.add(result.getString(1));
                }
            }
        }
        return Set.copyOf(rewards);
    }

    private boolean paymentExists(Connection connection, String reference) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_payment_ledger WHERE external_reference = ?")) {
            query.setString(1, reference);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean creditAdjustmentExists(Connection connection, String reference) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_credit_ledger WHERE external_reference = ?")) {
            query.setString(1, reference);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean insertCreditAdjustment(
            Connection connection,
            UUID playerId,
            long delta,
            String externalReference,
            String operator
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_credit_ledger
                (id, external_reference, player_uuid, delta, operator_name) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, externalReference);
            insert.setString(3, playerId.toString());
            insert.setLong(4, delta);
            insert.setString(5, operator);
            return insert.executeUpdate() == 1;
        }
    }

    private void updateCredits(Connection connection, UUID playerId, long updated) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_commerce_accounts SET credits = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ?
                """)) {
            update.setLong(1, updated);
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Missing commerce account during credit update");
        }
    }

    private OperationalQuota lockOperationalQuota(Connection connection) throws SQLException {
        LocalDate date = LocalDate.ofInstant(clock.instant(), starlightPolicy.zoneId());
        String dayKey = DAY_KEY.format(date);
        WeekFields weekFields = WeekFields.ISO;
        String weekKey = "%04d-W%02d".formatted(
                date.get(weekFields.weekBasedYear()), date.get(weekFields.weekOfWeekBasedYear()));
        ensureOperationalLimit(connection, "DAY", dayKey);
        long daily = lockOperationalLimit(connection, "DAY", dayKey);
        ensureOperationalLimit(connection, "WEEK", weekKey);
        long weekly = lockOperationalLimit(connection, "WEEK", weekKey);
        return new OperationalQuota(dayKey, weekKey, daily, weekly);
    }

    private void ensureOperationalLimit(Connection connection, String periodType, String periodKey)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_starlight_operation_limits
                (scope_id, period_type, period_key, granted) VALUES ('GLOBAL', ?, ?, 0)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, periodType);
            insert.setString(2, periodKey);
            insert.executeUpdate();
        }
    }

    private long lockOperationalLimit(Connection connection, String periodType, String periodKey)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT granted FROM rpg_starlight_operation_limits
                WHERE scope_id = 'GLOBAL' AND period_type = ? AND period_key = ?
                FOR UPDATE
                """)) {
            query.setString(1, periodType);
            query.setString(2, periodKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing Starlight operation quota after creation");
                return result.getLong(1);
            }
        }
    }

    private void updateOperationalQuota(Connection connection, OperationalQuota quota) throws SQLException {
        updateOperationalLimit(connection, "DAY", quota.dayKey(), quota.dailyGranted());
        updateOperationalLimit(connection, "WEEK", quota.weekKey(), quota.weeklyGranted());
    }

    private void updateOperationalLimit(
            Connection connection, String periodType, String periodKey, long granted
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_starlight_operation_limits
                SET granted = ?, updated_at = CURRENT_TIMESTAMP
                WHERE scope_id = 'GLOBAL' AND period_type = ? AND period_key = ?
                """)) {
            update.setLong(1, granted);
            update.setString(2, periodType);
            update.setString(3, periodKey);
            if (update.executeUpdate() != 1) throw new SQLException("Missing locked Starlight operation quota");
        }
    }

    private CreditResult creditResult(CreditResult.Status status, long balance, OperationalQuota quota) {
        return new CreditResult(status, balance,
                starlightPolicy.maximumPerGrant(),
                quota == null ? 0L : quota.dailyGranted(), starlightPolicy.dailyLimit(),
                quota == null ? 0L : quota.weeklyGranted(), starlightPolicy.weeklyLimit());
    }

    private static boolean validMembershipDays(int membershipDays) {
        return membershipDays == 0
                || membershipDays >= MIN_MEMBERSHIP_DAYS && membershipDays <= MAX_MEMBERSHIP_DAYS;
    }

    private void insertCreditSpend(
            Connection connection, UUID playerId, long creditCost, String idempotencyKey
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_credit_ledger
                (id, external_reference, player_uuid, delta, operator_name) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, "starlight-spend:" + idempotencyKey);
            insert.setString(3, playerId.toString());
            insert.setLong(4, -creditCost);
            insert.setString(5, "commerce-purchase");
            insert.executeUpdate();
        }
    }

    private UUID previousPurchase(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT delivery_id FROM rpg_commerce_purchases WHERE idempotency_key = ?")) {
            query.setString(1, idempotencyKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString(1)) : null;
            }
        }
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private record OperationalQuota(String dayKey, String weekKey, long dailyGranted, long weeklyGranted) {}

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Commerce transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open commerce transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
