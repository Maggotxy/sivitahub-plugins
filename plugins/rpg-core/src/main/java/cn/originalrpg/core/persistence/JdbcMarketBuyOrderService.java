package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.BuyOrderOperationResult;
import cn.originalrpg.api.model.MarketGoods;
import cn.originalrpg.api.model.MarketOrder;
import cn.originalrpg.api.service.MarketBuyOrderService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
 * Buy requests and maker-supplied commissions settle against the existing item
 * escrow, equipment lifecycle, gold ledger and mailbox. No caller-supplied item
 * payload can create a fill. Each partial fill reverses the entire current hold,
 * settles the consumed portion and reserves the remainder in the SAME transaction.
 * Consequently every reversal references one equal-amount original debit (C-006),
 * and cancellation never manufactures a partial refund without a receipt.
 */
public final class JdbcMarketBuyOrderService implements MarketBuyOrderService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    private static final long MAX_PRICE = 1_000_000_000L;
    private static final int MAX_QUANTITY = 10_000;
    private static final int MAX_OPEN_ORDERS = 12;
    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000000.00");
    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;
    private final MechanismRegistry mechanisms;
    private final EquipmentStateLedger lifecycle = new EquipmentStateLedger();

    public JdbcMarketBuyOrderService(DataSource source, Executor executor, BigDecimal startingBalance) {
        this(source, executor, startingBalance, null);
    }

    public JdbcMarketBuyOrderService(DataSource source, Executor executor, BigDecimal startingBalance,
                                    MechanismRegistry mechanisms) {
        this.dataSource = Objects.requireNonNull(source, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.startingBalance = money(startingBalance);
        this.mechanisms = mechanisms;
    }

    private boolean escrowEnabled() {
        return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.MARKET_ESCROW);
    }

    @Override
    public CompletionStage<BuyOrderOperationResult> place(
            UUID orderId, UUID buyerId, String matcher, long price, int quantity,
            MarketOrder.Kind kind, String key) {
        if (orderId == null || buyerId == null || !MarketGoods.validMatcher(matcher) || kind == null
                || kind == MarketOrder.Kind.CRAFT && !MarketGoods.equipmentMatcher(matcher)
                || price < 1 || price > MAX_PRICE || quantity < 1 || quantity > MAX_QUANTITY || !validKey(key)
                || !escrowEnabled()) return completed(BuyOrderOperationResult.Status.INVALID, null);
        return async(connection -> {
            loadOrCreateAccount(connection, buyerId);
            BigDecimal balance = lockBalance(connection, buyerId);
            OrderRow existing = selectOrder(connection, orderId, true);
            if (existing != null) {
                MarketOrder order = existing.order();
                boolean same = buyerId.equals(order.buyerId()) && matcher.equals(order.itemMatcher())
                        && price == order.price() && kind == order.kind() && quantity == existing.originalQuantity();
                return result(same ? BuyOrderOperationResult.Status.DUPLICATE
                        : BuyOrderOperationResult.Status.IDEMPOTENCY_CONFLICT, order);
            }
            if (ledger(connection, key, false) != null) {
                return result(BuyOrderOperationResult.Status.IDEMPOTENCY_CONFLICT, null);
            }
            if (openOrderCount(connection, buyerId) >= MAX_OPEN_ORDERS) {
                return result(BuyOrderOperationResult.Status.ORDER_LIMIT, null);
            }
            BigDecimal escrow = money(BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(quantity)));
            if (balance.compareTo(escrow) < 0) return result(BuyOrderOperationResult.Status.INSUFFICIENT_FUNDS, null);
            insertLedger(connection, key, buyerId, null, escrow, BigDecimal.ZERO,
                    "buy-order-escrow:" + orderId, null);
            updateBalance(connection, buyerId, balance.subtract(escrow));
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_market_buy_orders
                    (order_id, buyer_uuid, item_matcher, price, quantity, status, order_kind,
                     original_quantity, placement_key, escrow_key)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                    """)) {
                insert.setString(1, orderId.toString()); insert.setString(2, buyerId.toString());
                insert.setString(3, matcher); insert.setLong(4, price); insert.setInt(5, quantity);
                insert.setString(6, kind.name()); insert.setInt(7, quantity);
                insert.setString(8, key); insert.setString(9, key); insert.executeUpdate();
            }
            return result(BuyOrderOperationResult.Status.SUCCESS, selectOrder(connection, orderId, false).order());
        });
    }

    @Override
    public CompletionStage<BuyOrderOperationResult> fillListing(
            UUID orderId, UUID sellerId, UUID listingId, String key) {
        if (orderId == null || sellerId == null || listingId == null || !validKey(key) || !escrowEnabled()) {
            return completed(BuyOrderOperationResult.Status.INVALID, null);
        }
        return async(connection -> {
            OrderRow row = selectOrder(connection, orderId, true);
            if (row == null) return result(BuyOrderOperationResult.Status.NOT_FOUND, null);
            MarketOrder order = row.order();
            if (order.buyerId().equals(sellerId)) return result(BuyOrderOperationResult.Status.NOT_OWNER, order);
            String reason = "buy-order-fill:" + orderId + ":" + listingId;
            LedgerRow prior = ledger(connection, key, false);
            if (prior != null) return result(prior.matches(order.buyerId(), sellerId, reason)
                    ? BuyOrderOperationResult.Status.DUPLICATE : BuyOrderOperationResult.Status.IDEMPOTENCY_CONFLICT, order);
            if (!MarketOrder.ACTIVE.equals(order.status())) return result(BuyOrderOperationResult.Status.NOT_ACTIVE, order);
            ListingRow listing = selectListing(connection, listingId);
            if (listing == null) return result(BuyOrderOperationResult.Status.NOT_FOUND, order);
            if (!sellerId.equals(listing.seller())) return result(BuyOrderOperationResult.Status.NOT_OWNER, order);
            if (!"ACTIVE".equals(listing.state())) return result(BuyOrderOperationResult.Status.NOT_ACTIVE, order);
            boolean gear = MarketGoods.equipmentMatcher(order.itemMatcher());
            if (!order.itemMatcher().equals(listing.matcher()) || listing.quantity() < 1
                    || listing.quantity() > order.quantity() || listing.quantity() > 64
                    || gear != (listing.identity() != null)
                    || gear && (listing.quantity() != 1 || listing.crafter() == null)
                    || order.kind() == MarketOrder.Kind.CRAFT && !sellerId.equals(listing.crafter())) {
                return result(BuyOrderOperationResult.Status.NO_MATCH, order);
            }
            BigDecimal requiredFee = new BigDecimal(gear ? "0.10" : "0.05");
            if (listing.feeRate().compareTo(requiredFee) != 0) {
                return result(BuyOrderOperationResult.Status.NO_MATCH, order);
            }
            // The buyer's account lock also serializes hold reversals with generic refunds.
            loadOrCreateAccount(connection, sellerId);
            UUID first = order.buyerId().toString().compareTo(sellerId.toString()) < 0 ? order.buyerId() : sellerId;
            UUID second = first.equals(sellerId) ? order.buyerId() : sellerId;
            BigDecimal firstBalance = lockBalance(connection, first);
            BigDecimal secondBalance = lockBalance(connection, second);
            BigDecimal sellerBalance = first.equals(sellerId) ? firstBalance : secondBalance;
            String holdKey = verifiedHold(connection, row);
            if (holdKey == null) return result(BuyOrderOperationResult.Status.ESCROW_UNVERIFIED, order);
            BigDecimal gross = money(BigDecimal.valueOf(order.price()).multiply(BigDecimal.valueOf(listing.quantity())));
            BigDecimal fee = money(gross.multiply(requiredFee));
            BigDecimal payout = gross.subtract(fee);
            if (sellerBalance.add(payout).compareTo(MAX_BALANCE) > 0) {
                return result(BuyOrderOperationResult.Status.BALANCE_LIMIT, order);
            }
            if (listing.identity() != null && !lifecycle.completeSale(connection, listing.identity(), order.buyerId())) {
                return result(BuyOrderOperationResult.Status.NO_MATCH, order);
            }
            String receipt = UUID.nameUUIDFromBytes((orderId + ":" + listingId + ":" + key)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            releaseHold(connection, row, holdKey, "buy-order-release:" + receipt);
            insertLedger(connection, key, order.buyerId(), sellerId, payout, fee, reason, null);
            int remaining = order.quantity() - listing.quantity();
            String nextHoldKey = remaining == 0 ? null : "buy-order-hold:" + receipt;
            if (remaining > 0) insertLedger(connection, nextHoldKey, order.buyerId(), null,
                    money(BigDecimal.valueOf(order.price()).multiply(BigDecimal.valueOf(remaining))),
                    BigDecimal.ZERO, "buy-order-hold:" + orderId, null);
            // Release, purchase and re-hold net to zero for the buyer. There is no
            // externally visible spendable-balance window and no intermediate mint.
            updateBalance(connection, sellerId, sellerBalance.add(payout));
            updateOrder(connection, orderId, remaining, remaining == 0 ? MarketOrder.FILLED : MarketOrder.ACTIVE, nextHoldKey);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_market_listings SET state = 'SOLD', buyer_uuid = ?, open_item_identity = NULL,
                    completed_at = CURRENT_TIMESTAMP WHERE id = ? AND state = 'ACTIVE' AND custody_community_id IS NULL
                    """)) {
                update.setString(1, order.buyerId().toString()); update.setString(2, listingId.toString());
                if (update.executeUpdate() != 1) throw new SQLException("Locked item escrow changed during fill");
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """)) {
                insert.setString(1, UUID.nameUUIDFromBytes(("buy-order-delivery:" + listingId)
                        .getBytes(StandardCharsets.UTF_8)).toString());
                insert.setString(2, order.buyerId().toString()); insert.setString(3, listing.payload());
                insert.setString(4, "buy-order:" + orderId); insert.executeUpdate();
            }
            return result(BuyOrderOperationResult.Status.SUCCESS, selectOrder(connection, orderId, false).order());
        });
    }

    @Override
    public CompletionStage<BuyOrderOperationResult> cancel(UUID orderId, UUID buyerId, String key) {
        if (orderId == null || buyerId == null || !validKey(key)) return completed(BuyOrderOperationResult.Status.INVALID, null);
        return async(connection -> {
            OrderRow row = selectOrder(connection, orderId, true);
            if (row == null) return result(BuyOrderOperationResult.Status.NOT_FOUND, null);
            MarketOrder order = row.order();
            if (!buyerId.equals(order.buyerId())) return result(BuyOrderOperationResult.Status.NOT_OWNER, order);
            if (MarketOrder.CANCELLED.equals(order.status())) return result(BuyOrderOperationResult.Status.DUPLICATE, order);
            if (!MarketOrder.ACTIVE.equals(order.status())) return result(BuyOrderOperationResult.Status.NOT_ACTIVE, order);
            if (ledger(connection, key, false) != null) return result(BuyOrderOperationResult.Status.IDEMPOTENCY_CONFLICT, order);
            BuyOrderOperationResult.Status status = refundAndClose(connection, row, key, MarketOrder.CANCELLED);
            return result(status, selectOrder(connection, orderId, false).order());
        });
    }

    @Override
    public CompletionStage<Integer> expire(Instant boundary, int limit) {
        if (boundary == null) return CompletableFuture.completedFuture(0);
        return async(connection -> {
            List<UUID> candidates = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT order_id FROM rpg_market_buy_orders WHERE status = 'ACTIVE' AND created_at < ?
                    ORDER BY created_at, order_id LIMIT ?
                    """)) {
                query.setTimestamp(1, Timestamp.from(boundary)); query.setInt(2, Math.clamp(limit, 1, 500));
                try (ResultSet rs = query.executeQuery()) { while (rs.next()) candidates.add(UUID.fromString(rs.getString(1))); }
            }
            int expired = 0;
            for (UUID id : candidates) {
                OrderRow row = selectOrder(connection, id, true);
                if (row == null || !MarketOrder.ACTIVE.equals(row.order().status()) || !row.createdAt().isBefore(boundary)) continue;
                if (refundAndClose(connection, row, "buy-order-expire:" + id, MarketOrder.EXPIRED)
                        == BuyOrderOperationResult.Status.SUCCESS) expired++;
            }
            return expired;
        });
    }

    private BuyOrderOperationResult.Status refundAndClose(Connection connection, OrderRow row, String key, String state)
            throws SQLException {
        BigDecimal balance = lockBalance(connection, row.order().buyerId());
        String holdKey = verifiedHold(connection, row);
        if (holdKey == null) return BuyOrderOperationResult.Status.ESCROW_UNVERIFIED;
        BigDecimal refund = escrowAmount(row.order());
        if (balance.add(refund).compareTo(MAX_BALANCE) > 0) return BuyOrderOperationResult.Status.BALANCE_LIMIT;
        releaseHold(connection, row, holdKey, key);
        updateBalance(connection, row.order().buyerId(), balance.add(refund));
        updateOrder(connection, UUID.fromString(row.order().orderId()), row.order().quantity(), state, null);
        return BuyOrderOperationResult.Status.SUCCESS;
    }

    private String verifiedHold(Connection connection, OrderRow row) throws SQLException {
        String key = row.escrowKey();
        MarketOrder order = row.order();
        if (key == null) {
            // Adopt only an unambiguous, still fully funded legacy order. Old
            // partial fills with no equal-amount hold must be reconciled explicitly.
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT idempotency_key FROM rpg_ledger WHERE reason = ? AND sender_uuid = ?
                    AND receiver_uuid IS NULL AND amount = ? AND fee = 0 AND reversal_of IS NULL
                    """)) {
                query.setString(1, "buy-order-escrow:" + order.orderId());
                query.setString(2, order.buyerId().toString()); query.setBigDecimal(3, escrowAmount(order));
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) return null;
                    key = rs.getString(1);
                    if (rs.next()) return null;
                }
            }
        }
        LedgerRow debit = ledger(connection, key, true);
        if (debit == null || !order.buyerId().equals(debit.sender()) || debit.receiver() != null
                || debit.amount().compareTo(escrowAmount(order)) != 0 || debit.fee().signum() != 0
                || debit.reversal() != null || !(debit.reason().equals("buy-order-escrow:" + order.orderId())
                || debit.reason().equals("buy-order-hold:" + order.orderId()))) return null;
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM rpg_ledger WHERE reversal_of = ?")) {
            query.setString(1, key);
            try (ResultSet rs = query.executeQuery()) { if (rs.next()) return null; }
        }
        return key;
    }

    private void releaseHold(Connection connection, OrderRow row, String holdKey, String key) throws SQLException {
        insertLedger(connection, key, null, row.order().buyerId(), escrowAmount(row.order()), BigDecimal.ZERO,
                "buy-order-release:" + row.order().orderId(), holdKey);
    }

    @Override
    public CompletionStage<Optional<MarketOrder>> order(UUID id) {
        if (id == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(connection -> Optional.ofNullable(selectOrder(connection, id, false)).map(OrderRow::order));
    }

    @Override
    public CompletionStage<List<MarketOrder>> activeOrders(String matcher, int limit) {
        return activeOrders(matcher, null, limit);
    }

    @Override
    public CompletionStage<List<MarketOrder>> activeOrders(String matcher, MarketOrder.Kind kind, int limit) {
        return list(matcher, kind, null, limit);
    }

    @Override
    public CompletionStage<List<MarketOrder>> ownOrders(UUID buyerId, int limit) {
        if (buyerId == null) return CompletableFuture.completedFuture(List.of());
        return list(null, null, buyerId, limit);
    }

    private CompletionStage<List<MarketOrder>> list(String matcher, MarketOrder.Kind kind, UUID buyer, int limit) {
        return async(connection -> {
            String sql = "SELECT * FROM rpg_market_buy_orders WHERE status = 'ACTIVE'"
                    + (matcher == null ? "" : " AND item_matcher = ?")
                    + (kind == null ? "" : " AND order_kind = ?") + (buyer == null ? "" : " AND buyer_uuid = ?")
                    + " ORDER BY price DESC, created_at, order_id LIMIT ?";
            List<MarketOrder> orders = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(sql)) {
                int index = 1;
                if (matcher != null) query.setString(index++, matcher);
                if (kind != null) query.setString(index++, kind.name());
                if (buyer != null) query.setString(index++, buyer.toString());
                query.setInt(index, Math.clamp(limit, 1, 100));
                try (ResultSet rs = query.executeQuery()) { while (rs.next()) orders.add(mapOrder(rs).order()); }
            }
            return List.copyOf(orders);
        });
    }

    private int openOrderCount(Connection connection, UUID buyer) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM rpg_market_buy_orders WHERE buyer_uuid = ? AND status = 'ACTIVE'")) {
            query.setString(1, buyer.toString());
            try (ResultSet rs = query.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private void updateOrder(Connection connection, UUID id, int quantity, String status, String holdKey) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_buy_orders SET quantity = ?, status = ?, escrow_key = ?
                WHERE order_id = ? AND status = 'ACTIVE'
                """)) {
            update.setInt(1, quantity); update.setString(2, status); update.setString(3, holdKey); update.setString(4, id.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Locked buy order changed during settlement");
        }
    }

    private OrderRow selectOrder(Connection connection, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM rpg_market_buy_orders WHERE order_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id.toString());
            try (ResultSet rs = query.executeQuery()) { return rs.next() ? mapOrder(rs) : null; }
        }
    }

    private OrderRow mapOrder(ResultSet rs) throws SQLException {
        MarketOrder order = new MarketOrder(rs.getString("order_id"), UUID.fromString(rs.getString("buyer_uuid")),
                rs.getString("item_matcher"), rs.getLong("price"), rs.getInt("quantity"), rs.getString("status"),
                MarketOrder.Kind.valueOf(rs.getString("order_kind")));
        return new OrderRow(order, rs.getString("escrow_key"), rs.getInt("original_quantity"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ListingRow selectListing(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM rpg_market_listings WHERE id = ? AND custody_community_id IS NULL FOR UPDATE")) {
            query.setString(1, id.toString());
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? new ListingRow(UUID.fromString(rs.getString("seller_uuid")), rs.getString("state"),
                        rs.getString("item_matcher"), rs.getInt("item_quantity"), uuid(rs.getString("crafter_uuid")),
                        uuid(rs.getString("item_identity")), rs.getBigDecimal("fee_rate"), rs.getString("item_payload")) : null;
            }
        }
    }

    private LedgerRow ledger(Connection connection, String key, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM rpg_ledger WHERE idempotency_key = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, key);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? new LedgerRow(uuid(rs.getString("sender_uuid")), uuid(rs.getString("receiver_uuid")),
                        money(rs.getBigDecimal("amount")), money(rs.getBigDecimal("fee")), rs.getString("reason"),
                        rs.getString("reversal_of")) : null;
            }
        }
    }

    private void insertLedger(Connection connection, String key, UUID sender, UUID receiver, BigDecimal amount,
                              BigDecimal fee, String reason, String reversal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason, reversal_of)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, key);
            insert.setString(3, sender == null ? null : sender.toString());
            insert.setString(4, receiver == null ? null : receiver.toString());
            insert.setBigDecimal(5, money(amount)); insert.setBigDecimal(6, money(fee));
            insert.setString(7, reason); insert.setString(8, reversal); insert.executeUpdate();
        }
    }

    private void loadOrCreateAccount(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, id.toString()); insert.setBigDecimal(2, startingBalance); insert.executeUpdate();
        }
    }

    private BigDecimal lockBalance(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT balance FROM rpg_accounts WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, id.toString());
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) throw new SQLException("Gold account missing for funded order");
                return money(rs.getBigDecimal(1));
            }
        }
    }

    private void updateBalance(Connection connection, UUID id, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance)); update.setString(2, id.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Gold balance update lost");
        }
    }

    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal escrowAmount(MarketOrder order) {
        return money(BigDecimal.valueOf(order.price()).multiply(BigDecimal.valueOf(order.quantity())));
    }
    private static boolean validKey(String key) { return key != null && KEY.matcher(key).matches(); }
    private static BuyOrderOperationResult result(BuyOrderOperationResult.Status status, MarketOrder order) {
        return new BuyOrderOperationResult(status, order);
    }
    private static CompletionStage<BuyOrderOperationResult> completed(BuyOrderOperationResult.Status status, MarketOrder order) {
        return CompletableFuture.completedFuture(result(status, order));
    }
    private <T> CompletionStage<T> async(SqlOperation<T> operation) {
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection()) {
                try {
                    if (connection.getAutoCommit()) connection.setAutoCommit(false);
                    T result = operation.apply(connection); connection.commit(); return result;
                } catch (Exception error) {
                    connection.rollback();
                    if (error instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("Buy-order transaction failed", error);
                }
            } catch (SQLException error) { throw new IllegalStateException("Could not open buy-order transaction", error); }
        });
    }
    private record OrderRow(MarketOrder order, String escrowKey, int originalQuantity, Instant createdAt) { }
    private record ListingRow(UUID seller, String state, String matcher, int quantity, UUID crafter,
                              UUID identity, BigDecimal feeRate, String payload) { }
    private record LedgerRow(UUID sender, UUID receiver, BigDecimal amount, BigDecimal fee, String reason, String reversal) {
        boolean matches(UUID buyer, UUID seller, String expectedReason) {
            return buyer.equals(sender) && seller.equals(receiver) && expectedReason.equals(reason) && reversal == null;
        }
    }
    @FunctionalInterface private interface SqlOperation<T> { T apply(Connection connection) throws SQLException; }
}
