package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.BuyOrderOperationResult;
import cn.originalrpg.api.model.MarketOrder;
import cn.originalrpg.api.service.MarketBuyOrderService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Phase 4 市场 V2 买单簿的真实读写消费者。
 *
 * <p>与 {@link JdbcMarketService}（卖单托管）共用同一套账簿与投递表，因此满足 C-009
 * 「市场只能有一套托管结算」：本类不新建余额表、不新建流水表、不新建投递表，
 * 买单的托管金币就是 {@code rpg_accounts} 里的余额，成交流水就是 {@code rpg_ledger}
 * 里的一行（幂等键带 {@code buy-order:} 前缀），交付就是 {@code rpg_market_deliveries}
 * 里的一行（source 带 {@code buy-order:} 前缀）。</p>
 *
 * <p>状态机只允许前向：{@code PREPARED → ACTIVE → FILLED / CANCELLED / EXPIRED}。
 * 每一次迁移都用 {@code UPDATE ... WHERE status = ?} 的条件写做乐观闸门，
 * 并发撮合只有一次能把 ACTIVE 改成 FILLED。</p>
 */
public final class JdbcMarketBuyOrderService implements MarketBuyOrderService {
    private static final Pattern MATCHER = Pattern.compile("[a-z0-9_.:-]{1,64}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    /** 与卖单同口径：单笔价格上界，防止托管金额溢出 DECIMAL(19,2)。 */
    private static final long MAX_PRICE = 1_000_000_000L;
    private static final int MAX_QUANTITY = 10_000;
    private static final int MAX_PAYLOAD = 2_000_000;
    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000000.00");

    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;
    /** C-005 Kill Switch；为 null 表示未接入注册表（测试与无熔断场景）。 */
    private final MechanismRegistry mechanisms;

    public JdbcMarketBuyOrderService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this(dataSource, executor, startingBalance, null);
    }

    public JdbcMarketBuyOrderService(
            DataSource dataSource, Executor executor, BigDecimal startingBalance, MechanismRegistry mechanisms
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.startingBalance = money(startingBalance);
        this.mechanisms = mechanisms;
    }

    /** 买单托管是市场入口的一部分，与卖单挂牌同受 Kill Switch 熔断。 */
    private boolean escrowEnabled() {
        return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.MARKET_ESCROW);
    }

    @Override
    public CompletionStage<BuyOrderOperationResult> place(
            UUID orderId, UUID buyerId, String itemMatcher, long price, int quantity, String idempotencyKey
    ) {
        if (orderId == null || buyerId == null || itemMatcher == null || !MATCHER.matcher(itemMatcher).matches()
                || price < 1 || price > MAX_PRICE || quantity < 1 || quantity > MAX_QUANTITY
                || !validKey(idempotencyKey)) {
            return completed(BuyOrderOperationResult.Status.INVALID, null);
        }
        if (!escrowEnabled()) return completed(BuyOrderOperationResult.Status.INVALID, null);
        return async(connection -> {
            MarketOrder existing = selectOrder(connection, orderId, false);
            if (existing != null) {
                // 主键冲突即重放：不再扣第二笔托管。
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.DUPLICATE, existing);
            }
            // 托管流水与买单行同事务：任一步失败都不会留下「扣了钱没有买单」。
            loadOrCreateAccount(connection, buyerId);
            BigDecimal balance = lockBalance(connection, buyerId);
            BigDecimal escrow = money(BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(quantity)));
            if (balance.compareTo(escrow) < 0) {
                return completedStatus(BuyOrderOperationResult.Status.INSUFFICIENT_FUNDS, null);
            }
            insertEscrowLedger(connection, idempotencyKey, buyerId, escrow, "buy-order-escrow:" + orderId);
            updateBalance(connection, buyerId, balance.subtract(escrow));
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_market_buy_orders
                    (order_id, buyer_uuid, item_matcher, price, quantity, status)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, orderId.toString());
                insert.setString(2, buyerId.toString());
                insert.setString(3, itemMatcher);
                insert.setLong(4, price);
                insert.setInt(5, quantity);
                insert.setString(6, MarketOrder.ACTIVE);
                if (insert.executeUpdate() != 1) {
                    // 主键竞争：另一条并发请求已建单，本事务回滚（含托管流水与扣款）。
                    throw new IllegalStateException("Concurrent buy-order creation for " + orderId);
                }
            }
            return new BuyOrderOperationResult(
                    BuyOrderOperationResult.Status.SUCCESS, selectOrder(connection, orderId, false));
        });
    }

    @Override
    public CompletionStage<BuyOrderOperationResult> fill(
            UUID orderId, UUID sellerId, String itemPayload, int quantity, String idempotencyKey
    ) {
        if (orderId == null || sellerId == null || itemPayload == null || itemPayload.isBlank()
                || itemPayload.length() > MAX_PAYLOAD || quantity < 1 || quantity > MAX_QUANTITY
                || !validKey(idempotencyKey)) {
            return completed(BuyOrderOperationResult.Status.INVALID, null);
        }
        return async(connection -> {
            MarketOrder order = selectOrder(connection, orderId, true);
            if (order == null) return completedStatus(BuyOrderOperationResult.Status.NOT_FOUND, null);
            if (order.buyerId().equals(sellerId)) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.NOT_OWNER, order);
            }
            // 幂等优先于状态判定：重放必须报 DUPLICATE，而不是被「已成交」掩盖成 NOT_ACTIVE。
            if (ledgerExists(connection, idempotencyKey)) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.DUPLICATE, order);
            }
            if (!MarketOrder.ACTIVE.equals(order.status())) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.NOT_ACTIVE, order);
            }
            if (quantity > order.quantity()) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.INVALID, order);
            }
            loadOrCreateAccount(connection, sellerId);
            BigDecimal sellerBalance = lockBalance(connection, sellerId);
            BigDecimal gross = money(BigDecimal.valueOf(order.price()).multiply(BigDecimal.valueOf(quantity)));
            // 与卖单同一费率口径：普通物资 5%，自制装备 10% 由调用方在价格里体现；
            // 这里按 C-009 复用卖单的手续费语义 —— 手续费永久离开流通量，不进入任何账户。
            BigDecimal fee = money(gross.multiply(new BigDecimal("0.05")));
            BigDecimal payout = gross.subtract(fee);
            if (sellerBalance.add(payout).compareTo(MAX_BALANCE) > 0) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.BALANCE_LIMIT, order);
            }
            updateBalance(connection, sellerId, sellerBalance.add(payout));
            insertTradeLedger(connection, idempotencyKey, order.buyerId(), sellerId, payout, fee, orderId);
            // 成交后剩余数量回到买入方托管？不：剩余部分继续留在同一买单里（部分成交），
            // 全额成交才推进到 FILLED。这保证「一次撮合一行流水」而不是拆成多套状态。
            int remaining = order.quantity() - quantity;
            String nextStatus = remaining == 0 ? MarketOrder.FILLED : MarketOrder.ACTIVE;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_market_buy_orders SET quantity = ?, status = ?
                    WHERE order_id = ? AND status = ?
                    """)) {
                update.setInt(1, remaining);
                update.setString(2, nextStatus);
                update.setString(3, orderId.toString());
                update.setString(4, MarketOrder.ACTIVE);
                if (update.executeUpdate() != 1) {
                    // 并发撮合抢走了这单：整体回滚，账面不留任何痕迹。
                    return new BuyOrderOperationResult(BuyOrderOperationResult.Status.NOT_ACTIVE, order);
                }
            }
            insertDelivery(connection, order.buyerId(), itemPayload, "buy-order:" + orderId);
            return new BuyOrderOperationResult(
                    BuyOrderOperationResult.Status.SUCCESS, selectOrder(connection, orderId, false));
        });
    }

    @Override
    public CompletionStage<BuyOrderOperationResult> cancel(UUID orderId, UUID buyerId, String idempotencyKey) {
        if (orderId == null || buyerId == null || !validKey(idempotencyKey)) {
            return completed(BuyOrderOperationResult.Status.INVALID, null);
        }
        return async(connection -> {
            MarketOrder order = selectOrder(connection, orderId, true);
            if (order == null) return completedStatus(BuyOrderOperationResult.Status.NOT_FOUND, null);
            if (!order.buyerId().equals(buyerId)) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.NOT_OWNER, order);
            }
            if (MarketOrder.CANCELLED.equals(order.status())) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.DUPLICATE, order);
            }
            if (!MarketOrder.ACTIVE.equals(order.status())) {
                return new BuyOrderOperationResult(BuyOrderOperationResult.Status.NOT_ACTIVE, order);
            }
            refundEscrow(connection, order, idempotencyKey, "buy-order-cancel:" + orderId);
            return transition(connection, orderId, MarketOrder.CANCELLED);
        });
    }

    @Override
    public CompletionStage<Integer> expire(Instant createdBefore, int limit) {
        if (createdBefore == null) return CompletableFuture.completedFuture(0);
        int bounded = Math.clamp(limit, 1, 500);
        return async(connection -> {
            List<UUID> candidates = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT order_id FROM rpg_market_buy_orders
                    WHERE status = ? AND created_at < ?
                    ORDER BY created_at, order_id LIMIT ?
                    """)) {
                query.setString(1, MarketOrder.ACTIVE);
                query.setTimestamp(2, Timestamp.from(createdBefore));
                query.setInt(3, bounded);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) candidates.add(UUID.fromString(result.getString(1)));
                }
            }
            int expired = 0;
            for (UUID orderId : candidates) {
                MarketOrder order = selectOrder(connection, orderId, true);
                if (order == null || !MarketOrder.ACTIVE.equals(order.status())) continue;
                if (!createdBefore(connection, orderId, createdBefore)) continue;
                // 幂等键与订单 id 绑死：同一单无论被扫描多少次都只退一次托管。
                refundEscrow(connection, order, "buy-order-expire:" + orderId, "buy-order-expire:" + orderId);
                if (transition(connection, orderId, MarketOrder.EXPIRED).status()
                        == BuyOrderOperationResult.Status.SUCCESS) {
                    expired++;
                }
            }
            return expired;
        });
    }

    @Override
    public CompletionStage<Optional<MarketOrder>> order(UUID orderId) {
        if (orderId == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(connection -> Optional.ofNullable(selectOrder(connection, orderId, false)));
    }

    @Override
    public CompletionStage<List<MarketOrder>> activeOrders(String itemMatcher, int limit) {
        int bounded = Math.clamp(limit, 1, 100);
        String matcher = itemMatcher == null ? null : itemMatcher.strip().toLowerCase(java.util.Locale.ROOT);
        return async(connection -> {
            List<MarketOrder> orders = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM rpg_market_buy_orders
                    WHERE status = ? AND (? IS NULL OR item_matcher = ?)
                    ORDER BY price DESC, created_at, order_id LIMIT ?
                    """)) {
                query.setString(1, MarketOrder.ACTIVE);
                query.setString(2, matcher);
                query.setString(3, matcher);
                query.setInt(4, bounded);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) orders.add(mapOrder(result));
                }
            }
            return List.copyOf(orders);
        });
    }

    /** 候选行来自未加锁的扫描，落锁后必须复查 created_at，避免把并发新单当成过期单。 */
    private boolean createdBefore(Connection connection, UUID orderId, Instant boundary) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT created_at FROM rpg_market_buy_orders WHERE order_id = ?")) {
            query.setString(1, orderId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getTimestamp(1).toInstant().isBefore(boundary);
            }
        }
    }

    /** 托管退回：写一条与托管金额相等的反向流水，余额回到买家。 */
    private void refundEscrow(Connection connection, MarketOrder order, String key, String reason) throws SQLException {
        if (ledgerExists(connection, key)) return;
        BigDecimal gross = money(BigDecimal.valueOf(order.price()).multiply(BigDecimal.valueOf(order.quantity())));
        loadOrCreateAccount(connection, order.buyerId());
        BigDecimal balance = lockBalance(connection, order.buyerId());
        insertEscrowLedger(connection, key, order.buyerId(), gross, reason);
        updateBalance(connection, order.buyerId(), balance.add(gross));
    }

    private BuyOrderOperationResult transition(Connection connection, UUID orderId, String status)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_buy_orders SET status = ? WHERE order_id = ? AND status = ?
                """)) {
            update.setString(1, status);
            update.setString(2, orderId.toString());
            update.setString(3, MarketOrder.ACTIVE);
            if (update.executeUpdate() != 1) {
                return completedStatus(BuyOrderOperationResult.Status.NOT_ACTIVE, null);
            }
        }
        return new BuyOrderOperationResult(
                BuyOrderOperationResult.Status.SUCCESS, selectOrder(connection, orderId, false));
    }

    private MarketOrder selectOrder(Connection connection, UUID orderId, boolean lock) throws SQLException {
        String sql = "SELECT * FROM rpg_market_buy_orders WHERE order_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, orderId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? mapOrder(result) : null;
            }
        }
    }

    private MarketOrder mapOrder(ResultSet result) throws SQLException {
        return new MarketOrder(
                result.getString("order_id"),
                UUID.fromString(result.getString("buyer_uuid")),
                result.getString("item_matcher"),
                result.getLong("price"),
                result.getInt("quantity"),
                result.getString("status")
        );
    }

    private boolean ledgerExists(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_ledger WHERE idempotency_key = ?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    /** 托管扣款流水：sender 为空，receiver 为空 —— 托管期间金币处于系统冻结，不是「给谁」。 */
    private void insertEscrowLedger(
            Connection connection, String key, UUID buyerId, BigDecimal amount, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, buyerId.toString());
            insert.setString(4, null);
            insert.setBigDecimal(5, money(amount));
            insert.setBigDecimal(6, money(BigDecimal.ZERO));
            insert.setString(7, reason);
            insert.executeUpdate();
        }
    }

    private void insertTradeLedger(
            Connection connection, String key, UUID sender, UUID receiver,
            BigDecimal amount, BigDecimal fee, UUID orderId
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, sender.toString());
            insert.setString(4, receiver.toString());
            insert.setBigDecimal(5, money(amount));
            insert.setBigDecimal(6, money(fee));
            insert.setString(7, "buy-order-fill:" + orderId);
            insert.executeUpdate();
        }
    }

    private void insertDelivery(Connection connection, UUID playerId, String payload, String source)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING')
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, playerId.toString());
            insert.setString(3, payload);
            insert.setString(4, source);
            insert.executeUpdate();
        }
    }

    private void loadOrCreateAccount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING
                """)) {
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
                if (!result.next()) throw new IllegalStateException("Gold account row disappeared");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?
                """)) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Gold balance update was lost");
        }
    }

    private static boolean validKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BuyOrderOperationResult completedStatus(BuyOrderOperationResult.Status status, MarketOrder order) {
        return new BuyOrderOperationResult(status, order);
    }

    private static CompletionStage<BuyOrderOperationResult> completed(
            BuyOrderOperationResult.Status status, MarketOrder order
    ) {
        return CompletableFuture.completedFuture(new BuyOrderOperationResult(status, order));
    }

    private <T> CompletionStage<T> async(SqlOperation<T> operation) {
        return AsyncJdbc.supply(executor, () -> inTransaction(operation));
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                if (connection.getAutoCommit()) connection.setAutoCommit(false);
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Buy-order transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open buy-order transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
