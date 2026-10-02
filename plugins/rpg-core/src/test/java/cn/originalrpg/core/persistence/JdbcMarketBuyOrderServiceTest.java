package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.BuyOrderOperationResult;
import cn.originalrpg.api.model.MarketOrder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 买单簿的行为级测试（真实 H2 + 真实 JDBC 事务）。
 *
 * <p>判据是「钱有没有按 C-009 单一托管正确动」：挂单托管扣款、撮合税后结算、
 * 撤单/过期全额退回、并发撮合只有一次成功。所有断言都读真实表，不读源码字符串。</p>
 */
final class JdbcMarketBuyOrderServiceTest {
    private static final BigDecimal STARTING = new BigDecimal("1000.00");

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcMarketBuyOrderService service;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:buy_orders_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_accounts (
                        player_uuid VARCHAR(36) PRIMARY KEY, balance DECIMAL(19, 2) NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36),
                        amount DECIMAL(19, 2) NOT NULL, fee DECIMAL(19, 2) NOT NULL,
                        reason VARCHAR(128) NOT NULL, reversal_of VARCHAR(96),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_deliveries (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        item_payload TEXT NOT NULL, source VARCHAR(96) NOT NULL,
                        state VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        delivered_at TIMESTAMP NULL)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_buy_orders (
                        order_id VARCHAR(36) PRIMARY KEY, buyer_uuid VARCHAR(36) NOT NULL,
                        item_matcher VARCHAR(64) NOT NULL, price BIGINT NOT NULL,
                        quantity INTEGER NOT NULL, status VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            connection.commit();
        }
        service = new JdbcMarketBuyOrderService(dataSource, executor, STARTING);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void placingEscrowsGoldSoTheBuyerCannotSpendItTwice() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        UUID orderId = UUID.randomUUID();

        BuyOrderOperationResult placed = await(service.place(
                orderId, buyer, "rpgitems:essence", 100, 3, "place-1"));

        assertEquals(BuyOrderOperationResult.Status.SUCCESS, placed.status());
        assertEquals(MarketOrder.ACTIVE, placed.order().status());
        assertEquals(3, placed.order().quantity());
        // 托管扣款：3 × 100 = 300 离开买家余额。
        assertEquals(new BigDecimal("700.00"), balance(buyer));
        assertEquals(1, ledgerCount("buy-order-escrow:" + orderId));

        // 幂等：同一订单 id 重放不二次托管。
        BuyOrderOperationResult replay = await(service.place(
                orderId, buyer, "rpgitems:essence", 100, 3, "place-1"));
        assertEquals(BuyOrderOperationResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("700.00"), balance(buyer), "重放不得二次托管");
    }

    @Test
    void insufficientGoldIsRejectedWithoutAnyEscrowMovement() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedAccount(buyer, "50.00");

        BuyOrderOperationResult result = await(service.place(
                UUID.randomUUID(), buyer, "rpgitems:essence", 100, 3, "place-poor"));

        assertEquals(BuyOrderOperationResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(new BigDecimal("50.00"), balance(buyer));
        assertEquals(0, ledgerCount(null));
    }

    @Test
    void fillingSettlesTaxedPayoutAndDeliversThroughTheSameMailbox() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        seedAccount(seller, "1000.00");
        UUID orderId = UUID.randomUUID();
        await(service.place(orderId, buyer, "rpgitems:essence", 100, 2, "place-2"));

        BuyOrderOperationResult filled = await(service.fill(
                orderId, seller, "cGF5bG9hZA==", 2, "fill-1"));

        assertEquals(BuyOrderOperationResult.Status.SUCCESS, filled.status());
        assertEquals(MarketOrder.FILLED, filled.order().status());
        // 成交额 200，普通物资 5% 手续费 = 10，卖家实收 190。
        assertEquals(new BigDecimal("1190.00"), balance(seller));
        assertEquals(new BigDecimal("800.00"), balance(buyer), "买家只在托管时被扣一次（2 × 100）");
        // 交付走与卖单同一张邮箱表（C-009 单一托管）。
        assertEquals(1, deliveryCount("buy-order:" + orderId));
    }

    @Test
    void partialFillKeepsTheOrderActiveWithTheRemainingQuantity() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        seedAccount(seller, "1000.00");
        UUID orderId = UUID.randomUUID();
        await(service.place(orderId, buyer, "rpgitems:essence", 100, 5, "place-3"));

        BuyOrderOperationResult filled = await(service.fill(orderId, seller, "cGF5bG9hZA==", 2, "fill-3"));

        assertEquals(BuyOrderOperationResult.Status.SUCCESS, filled.status());
        assertEquals(MarketOrder.ACTIVE, filled.order().status());
        assertEquals(3, filled.order().quantity(), "剩余数量留在同一买单里");
    }

    @Test
    void cancellingReturnsTheFullEscrowAndBlocksASecondRefund() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        UUID orderId = UUID.randomUUID();
        await(service.place(orderId, buyer, "rpgitems:essence", 100, 3, "place-4"));

        BuyOrderOperationResult cancelled = await(service.cancel(orderId, buyer, "cancel-1"));

        assertEquals(BuyOrderOperationResult.Status.SUCCESS, cancelled.status());
        assertEquals(MarketOrder.CANCELLED, cancelled.order().status());
        assertEquals(new BigDecimal("1000.00"), balance(buyer), "撤单必须全额退回托管");

        // 二次撤单：状态已不是 ACTIVE，不得再退一次。
        BuyOrderOperationResult again = await(service.cancel(orderId, buyer, "cancel-2"));
        assertEquals(BuyOrderOperationResult.Status.DUPLICATE, again.status());
        assertEquals(new BigDecimal("1000.00"), balance(buyer), "不得二次退款");
    }

    @Test
    void nonOwnerCannotCancelAndNonActiveOrderCannotBeFilled() throws Exception {
        UUID buyer = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        UUID orderId = UUID.randomUUID();
        await(service.place(orderId, buyer, "rpgitems:essence", 100, 1, "place-5"));

        assertEquals(BuyOrderOperationResult.Status.NOT_OWNER,
                await(service.cancel(orderId, stranger, "cancel-stranger")).status());
        assertEquals(new BigDecimal("900.00"), balance(buyer), "他人撤单不得动托管");

        // 买家自己不能撮合自己的买单（自成交）。
        assertEquals(BuyOrderOperationResult.Status.NOT_OWNER,
                await(service.fill(orderId, buyer, "cGF5bG9hZA==", 1, "fill-self")).status());

        await(service.cancel(orderId, buyer, "cancel-5"));
        assertEquals(BuyOrderOperationResult.Status.NOT_ACTIVE,
                await(service.fill(orderId, stranger, "cGF5bG9hZA==", 1, "fill-dead")).status());
    }

    @Test
    void expiryReturnsEscrowOnceEvenIfScannedRepeatedly() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        UUID orderId = UUID.randomUUID();
        await(service.place(orderId, buyer, "rpgitems:essence", 50, 4, "place-6"));
        ageOrder(orderId, Instant.now().minusSeconds(7200));

        int expired = await(service.expire(Instant.now().minusSeconds(3600), 100));
        assertEquals(1, expired);
        assertEquals(new BigDecimal("1000.00"), balance(buyer));

        // 再扫一次：状态已是 EXPIRED，不能二次退款。
        assertEquals(0, await(service.expire(Instant.now().minusSeconds(3600), 100)));
        assertEquals(new BigDecimal("1000.00"), balance(buyer));
    }

    @Test
    void concurrentFillsOnlyOneWins() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        UUID sellerA = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        seedAccount(sellerA, "0.00");
        seedAccount(sellerB, "0.00");
        UUID orderId = UUID.randomUUID();
        await(service.place(orderId, buyer, "rpgitems:essence", 100, 1, "place-7"));

        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> await(service.fill(
                    orderId, sellerA, "cGF5bG9hZA==", 1, "fill-race-a")), callers);
            var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> await(service.fill(
                    orderId, sellerB, "cGF5bG9hZA==", 1, "fill-race-b")), callers);
            int succeeded = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    if (future.join().status() == BuyOrderOperationResult.Status.SUCCESS) succeeded++;
                } catch (java.util.concurrent.CompletionException ignored) {
                    // 并发抢锁失败整体回滚也是合法结果：关键是只有一次成交。
                }
            }
            assertEquals(1, succeeded, "并发撮合只能有一次成功");
            assertEquals(1, deliveryCount("buy-order:" + orderId));
            // 卖家合计收到 95，不出现双倍结算。
            assertEquals(new BigDecimal("95.00"), balance(sellerA).add(balance(sellerB)));
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void activeOrdersAreFilteredByMatcherAndPriceOrdered() throws Exception {
        UUID buyer = UUID.randomUUID();
        seedAccount(buyer, "1000.00");
        await(service.place(UUID.randomUUID(), buyer, "rpgitems:essence", 10, 1, "list-1"));
        await(service.place(UUID.randomUUID(), buyer, "rpgitems:essence", 90, 1, "list-2"));
        await(service.place(UUID.randomUUID(), buyer, "rpgitems:ore", 50, 1, "list-3"));

        var essence = await(service.activeOrders("rpgitems:essence", 10));
        assertEquals(2, essence.size());
        assertEquals(90L, essence.get(0).price(), "同匹配串按价格降序");
        // 未知订单返回空，而不是抛异常。
        assertTrue(await(service.order(UUID.randomUUID())).isEmpty());
        assertNotNull(await(service.activeOrders(null, 10)).get(0));
    }

    private void ageOrder(UUID orderId, Instant createdAt) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE rpg_market_buy_orders SET created_at = ? WHERE order_id = ?")) {
            update.setTimestamp(1, Timestamp.from(createdAt));
            update.setString(2, orderId.toString());
            update.executeUpdate();
            connection.commit();
        }
    }

    private void seedAccount(UUID player, String amount) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?)")) {
            insert.setString(1, player.toString());
            insert.setBigDecimal(2, new BigDecimal(amount));
            insert.executeUpdate();
            connection.commit();
        }
    }

    private BigDecimal balance(UUID player) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT balance FROM rpg_accounts WHERE player_uuid = ?")) {
            query.setString(1, player.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBigDecimal(1).setScale(2, java.math.RoundingMode.HALF_UP);
            }
        }
    }

    private long ledgerCount(String reason) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(reason == null
                     ? "SELECT COUNT(*) FROM rpg_ledger"
                     : "SELECT COUNT(*) FROM rpg_ledger WHERE reason = ?")) {
            if (reason != null) query.setString(1, reason);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private long deliveryCount(String source) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM rpg_market_deliveries WHERE source = ?")) {
            query.setString(1, source);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
