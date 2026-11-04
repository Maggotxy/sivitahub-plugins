package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.BuyOrderOperationResult;
import cn.originalrpg.api.model.MarketGoods;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.model.MarketOperationResult;
import cn.originalrpg.api.model.MarketOrder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Real JDBC transactions against the actual additive schema, including failure injection. */
final class JdbcMarketBuyOrderServiceTest {
    private Database database;
    private ExecutorService executor;
    private JdbcMarketBuyOrderService orders;
    private JdbcMarketService market;
    private UUID buyer;
    private UUID seller;
    private String postgresUrl;
    private String postgresSchema;
    private String postgresUser;
    private String postgresPassword;

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(System.getenv("RPG_DATABASE_URL") == null || System.getenv("RPG_DATABASE_URL").isBlank(),
                "Tests refuse the runtime RPG_DATABASE_URL override; provide only the isolated SIVITA_MARKET_TEST_POSTGRES_URL");
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", "jdbc:h2:mem:buy_orders_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        postgresUrl = System.getenv("SIVITA_MARKET_TEST_POSTGRES_URL");
        if (postgresUrl != null && !postgresUrl.isBlank()) {
            postgresSchema = "sivita_orders_" + UUID.randomUUID().toString().replace("-", "");
            postgresUser = System.getenv().getOrDefault("SIVITA_MARKET_TEST_POSTGRES_USER", "postgres");
            postgresPassword = System.getenv().getOrDefault("SIVITA_MARKET_TEST_POSTGRES_PASSWORD", "");
            try (Connection admin = DriverManager.getConnection(postgresUrl, postgresUser, postgresPassword);
                 Statement sql = admin.createStatement()) { sql.execute("CREATE SCHEMA " + postgresSchema); }
            config.set("database.jdbc-url", postgresUrl + (postgresUrl.contains("?") ? "&" : "?") + "currentSchema=" + postgresSchema);
            config.set("database.username", postgresUser);
            config.set("database.password", postgresPassword);
        }
        config.set("database.maximum-pool-size", 12);
        database = Database.open(config);
        database.migrate();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        orders = new JdbcMarketBuyOrderService(database.dataSource(), executor, BigDecimal.ZERO);
        market = new JdbcMarketService(database.dataSource(), executor, BigDecimal.ZERO);
        buyer = UUID.randomUUID(); seller = UUID.randomUUID();
        seed(buyer, "1000.00"); seed(seller, "1000.00");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executor != null) executor.close();
        if (database != null) database.close();
        if (postgresSchema != null) {
            try (Connection admin = DriverManager.getConnection(postgresUrl, postgresUser, postgresPassword);
                 Statement sql = admin.createStatement()) { sql.execute("DROP SCHEMA " + postgresSchema + " CASCADE"); }
        }
    }

    @Test
    void placingEscrowsGoldAndOnlyExactReplaysAreDuplicates() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 3);
        assertEquals(new BigDecimal("700.00"), balance(buyer));
        assertEquals(BuyOrderOperationResult.Status.DUPLICATE,
                await(orders.place(id, buyer, "minecraft:iron_ingot", 100, 3, "retry-place")).status());
        assertEquals(BuyOrderOperationResult.Status.IDEMPOTENCY_CONFLICT,
                await(orders.place(id, buyer, "minecraft:iron_ingot", 101, 3, "wrong-replay")).status());
        assertEquals(1, count("rpg_ledger"));
    }

    @Test
    void insufficientFundsAndUnrecognizedMatchersCreateNoOrder() throws Exception {
        assertEquals(BuyOrderOperationResult.Status.INSUFFICIENT_FUNDS,
                await(orders.place(UUID.randomUUID(), buyer, "minecraft:diamond", 999, 2, "poor")).status());
        assertEquals(BuyOrderOperationResult.Status.INVALID,
                await(orders.place(UUID.randomUUID(), buyer, "anything:*", 1, 1, "invalid")).status());
        assertEquals(0, count("rpg_market_buy_orders"));
        assertEquals(new BigDecimal("1000.00"), balance(buyer));
    }

    @Test
    void fillsConsumeRealEscrowBurnTaxAndCreateOneMailboxDelivery() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 2);
        UUID listing = list(seller, new MarketGoods("minecraft:iron_ingot", 2, null), null);
        assertEquals(BuyOrderOperationResult.Status.SUCCESS, await(orders.fillListing(id, seller, listing, "fill")).status());
        assertEquals(new BigDecimal("1190.00"), balance(seller));
        assertEquals(new BigDecimal("800.00"), balance(buyer));
        assertEquals(MarketOrder.FILLED, await(orders.order(id)).orElseThrow().status());
        assertEquals(MarketListing.State.SOLD, await(market.listing(listing)).orElseThrow().state());
        var mail = await(market.pendingDeliveries(buyer, 10));
        assertEquals(1, mail.size());
        assertEquals("escrow-payload:" + listing, mail.getFirst().itemPayload());
        assertEquals(new BigDecimal("10.00"), decimal("SELECT SUM(fee) FROM rpg_ledger"));
        assertReversalsReferenceEqualDebits();
    }

    @Test
    void partialFillThenCancellationRefundsOnlyUnconsumedBudgetWithEqualDebitReceipts() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 5);
        UUID listing = list(seller, new MarketGoods("minecraft:iron_ingot", 2, null), null);
        await(orders.fillListing(id, seller, listing, "partial"));
        assertEquals(3, await(orders.order(id)).orElseThrow().quantity());
        assertEquals(new BigDecimal("500.00"), balance(buyer));
        assertEquals(BuyOrderOperationResult.Status.SUCCESS, await(orders.cancel(id, buyer, "cancel")).status());
        assertEquals(new BigDecimal("800.00"), balance(buyer));
        assertEquals(new BigDecimal("1190.00"), balance(seller));
        assertEquals(BuyOrderOperationResult.Status.DUPLICATE, await(orders.cancel(id, buyer, "cancel-again")).status());
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_ledger WHERE reversal_of IS NOT NULL"));
        assertReversalsReferenceEqualDebits();
    }

    @Test
    void craftCommissionRequiresTheActualMakerAndUsesTenPercentFeeAndLifecycle() throws Exception {
        UUID id = UUID.randomUUID();
        assertEquals(BuyOrderOperationResult.Status.SUCCESS, await(orders.place(id, buyer,
                "equipment:sword:rare", 200, 1, MarketOrder.Kind.CRAFT, "craft-place")).status());
        UUID identity = UUID.randomUUID();
        UUID listing = list(seller, new MarketGoods("equipment:sword:rare", 1, seller), identity);
        assertEquals(BuyOrderOperationResult.Status.SUCCESS, await(orders.fillListing(id, seller, listing, "craft-fill")).status());
        assertEquals(new BigDecimal("1180.00"), balance(seller));
        assertEquals("SOLD", string("SELECT state FROM rpg_equipment_states WHERE item_id = '" + identity + "'"));
        assertEquals(buyer.toString(), string("SELECT owner_uuid FROM rpg_equipment_states WHERE item_id = '" + identity + "'"));
        assertEquals(new BigDecimal("20.00"), decimal("SELECT SUM(fee) FROM rpg_ledger"));
        assertReversalsReferenceEqualDebits();
    }

    @Test
    void resellingAnotherMakersGearCannotSatisfyACraftingCommission() throws Exception {
        UUID id = UUID.randomUUID();
        await(orders.place(id, buyer, "equipment:sword:rare", 200, 1, MarketOrder.Kind.CRAFT, "craft-place"));
        UUID listing = list(seller, new MarketGoods("equipment:sword:rare", 1, UUID.randomUUID()), UUID.randomUUID());
        assertEquals(BuyOrderOperationResult.Status.NO_MATCH, await(orders.fillListing(id, seller, listing, "resale")).status());
        assertEquals(MarketListing.State.ACTIVE, await(market.listing(listing)).orElseThrow().state());
        assertEquals(0, count("rpg_market_deliveries"));
    }

    @Test
    void unmatchedOrTooLargeStacksCannotConsumeBuyerFunds() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 1);
        UUID wrong = list(seller, new MarketGoods("minecraft:dirt", 1, null), null);
        UUID large = list(seller, new MarketGoods("minecraft:iron_ingot", 2, null), null);
        assertEquals(BuyOrderOperationResult.Status.NO_MATCH, await(orders.fillListing(id, seller, wrong, "wrong")).status());
        assertEquals(BuyOrderOperationResult.Status.NO_MATCH, await(orders.fillListing(id, seller, large, "large")).status());
        assertEquals(new BigDecimal("900.00"), balance(buyer));
        assertEquals(new BigDecimal("1000.00"), balance(seller));
        assertEquals(0, count("rpg_market_deliveries"));
    }

    @Test
    void foreignListingsAndSelfTradingAreRejected() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 1);
        UUID listing = list(seller, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        assertEquals(BuyOrderOperationResult.Status.NOT_OWNER, await(orders.fillListing(id, buyer, listing, "self")).status());
        assertEquals(BuyOrderOperationResult.Status.NOT_OWNER,
                await(orders.fillListing(id, UUID.randomUUID(), listing, "foreign")).status());
        assertEquals(BuyOrderOperationResult.Status.NOT_OWNER, await(orders.cancel(id, seller, "foreign-cancel")).status());
    }

    @Test
    void exactFillReplayIsDuplicateAndSameKeyForDifferentTradeIsConflict() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 2);
        UUID first = list(seller, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        UUID second = list(seller, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        await(orders.fillListing(id, seller, first, "stable-fill"));
        assertEquals(BuyOrderOperationResult.Status.DUPLICATE, await(orders.fillListing(id, seller, first, "stable-fill")).status());
        assertEquals(BuyOrderOperationResult.Status.IDEMPOTENCY_CONFLICT,
                await(orders.fillListing(id, seller, second, "stable-fill")).status());
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals(new BigDecimal("1095.00"), balance(seller));
    }

    @Test
    void aListingCannotBeFilledTwiceWithDifferentIdempotencyKeys() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 2);
        UUID listing = list(seller, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        await(orders.fillListing(id, seller, listing, "first"));
        assertEquals(BuyOrderOperationResult.Status.NOT_ACTIVE,
                await(orders.fillListing(id, seller, listing, "different")).status());
        assertEquals(1, count("rpg_market_deliveries"));
    }

    @Test
    void parallelSellersCannotOverfillOneOrder() throws Exception {
        UUID other = UUID.randomUUID(); seed(other, "1000.00");
        UUID id = place("minecraft:iron_ingot", 100, 1);
        UUID a = list(seller, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        UUID b = list(other, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        var first = orders.fillListing(id, seller, a, "race-a").toCompletableFuture();
        var second = orders.fillListing(id, other, b, "race-b").toCompletableFuture();
        var results = List.of(first.join(), second.join());
        assertEquals(1, results.stream().filter(r -> r.status() == BuyOrderOperationResult.Status.SUCCESS).count());
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals(new BigDecimal("2095.00"), balance(seller).add(balance(other)));
    }

    @Test
    void fillAndOrdinaryPurchaseCompeteForTheSameItemEscrow() throws Exception {
        UUID anotherBuyer = UUID.randomUUID(); seed(anotherBuyer, "1000.00");
        UUID id = place("minecraft:iron_ingot", 100, 1);
        UUID listing = list(seller, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        var fill = orders.fillListing(id, seller, listing, "race-fill").toCompletableFuture();
        var purchase = market.purchase(listing, anotherBuyer, "race-purchase").toCompletableFuture();
        int wins = (fill.join().status() == BuyOrderOperationResult.Status.SUCCESS ? 1 : 0)
                + (purchase.join().status() == MarketOperationResult.Status.SUCCESS ? 1 : 0);
        assertEquals(1, wins);
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals(MarketListing.State.SOLD, await(market.listing(listing)).orElseThrow().state());
    }

    @Test
    void deliveryFailureRollsBackMoneyHoldOrderItemAndLifecycle() throws Exception {
        UUID id = place("equipment:sword:rare", 200, 1);
        UUID identity = UUID.randomUUID();
        UUID listing = list(seller, new MarketGoods("equipment:sword:rare", 1, seller), identity);
        execute("ALTER TABLE rpg_market_deliveries ADD CONSTRAINT reject_order_delivery CHECK (source NOT LIKE 'buy-order:%')");
        assertThrows(RuntimeException.class, () -> await(orders.fillListing(id, seller, listing, "fail-delivery")));
        assertEquals(new BigDecimal("800.00"), balance(buyer));
        assertEquals(new BigDecimal("1000.00"), balance(seller));
        assertEquals(MarketOrder.ACTIVE, await(orders.order(id)).orElseThrow().status());
        assertEquals(MarketListing.State.ACTIVE, await(market.listing(listing)).orElseThrow().state());
        assertEquals("LISTED", string("SELECT state FROM rpg_equipment_states WHERE item_id = '" + identity + "'"));
        assertEquals(1, count("rpg_ledger"));
    }

    @Test
    void expiryRefundsOnlyOnceAndCanRecoverAfterPartialFill() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 3);
        UUID listing = list(seller, new MarketGoods("minecraft:iron_ingot", 1, null), null);
        await(orders.fillListing(id, seller, listing, "partial-expire"));
        execute("UPDATE rpg_market_buy_orders SET created_at = TIMESTAMP '2020-01-01 00:00:00'");
        assertEquals(1, await(orders.expire(Instant.now().minusSeconds(3600), 10)));
        assertEquals(0, await(orders.expire(Instant.now().minusSeconds(3600), 10)));
        assertEquals(new BigDecimal("900.00"), balance(buyer));
        assertReversalsReferenceEqualDebits();
    }

    @Test
    void balanceCapDefersRefundWithoutLosingTheFundedOrder() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 1);
        setBalance(buyer, "1000000000000.00");
        assertEquals(BuyOrderOperationResult.Status.BALANCE_LIMIT, await(orders.cancel(id, buyer, "cap-cancel")).status());
        assertEquals(MarketOrder.ACTIVE, await(orders.order(id)).orElseThrow().status());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_ledger WHERE reversal_of IS NOT NULL"));
        setBalance(buyer, "500.00");
        assertEquals(BuyOrderOperationResult.Status.SUCCESS, await(orders.cancel(id, buyer, "cap-cancel")).status());
        assertEquals(new BigDecimal("600.00"), balance(buyer));
    }

    @Test
    void oldFullyFundedOrderCanBeAdoptedButUnverifiedPartialHoldCannotMintRefund() throws Exception {
        UUID id = place("minecraft:iron_ingot", 100, 3);
        execute("UPDATE rpg_market_buy_orders SET escrow_key = NULL, original_quantity = 0 WHERE order_id = '" + id + "'");
        assertEquals(BuyOrderOperationResult.Status.SUCCESS, await(orders.cancel(id, buyer, "adopt-legacy")).status());
        UUID partial = place("minecraft:iron_ingot", 100, 3);
        execute("UPDATE rpg_market_buy_orders SET escrow_key = NULL, quantity = 2 WHERE order_id = '" + partial + "'");
        assertEquals(BuyOrderOperationResult.Status.ESCROW_UNVERIFIED,
                await(orders.cancel(partial, buyer, "bad-legacy-refund")).status());
        assertEquals(new BigDecimal("700.00"), balance(buyer));
    }

    @Test
    void twelveOpenOrdersAreTheTransactionallyEnforcedCombinedLimit() throws Exception {
        for (int i = 0; i < 12; i++) place("minecraft:iron_ingot", 1, 1);
        assertEquals(BuyOrderOperationResult.Status.ORDER_LIMIT, await(orders.place(UUID.randomUUID(), buyer,
                "equipment:sword:rare", 1, 1, MarketOrder.Kind.CRAFT, "too-many")).status());
        assertEquals(12, await(orders.ownOrders(buyer, 100)).size());
        assertEquals(0, await(orders.ownOrders(seller, 100)).size());
        assertEquals(new BigDecimal("988.00"), balance(buyer));
    }

    @Test
    void underTaxedEquipmentAndUnprovenLegacyListingAreNotMatchable() throws Exception {
        UUID identity = UUID.randomUUID(); UUID listing = UUID.randomUUID();
        assertEquals(MarketOperationResult.Status.INVALID, await(market.prepare(listing, seller, "opaque", new BigDecimal("100"),
                new BigDecimal("0.05"), 12, Optional.of(identity), Optional.of(new MarketGoods("equipment:sword:rare", 1, seller)))).status());
        UUID legacy = UUID.randomUUID();
        assertEquals(MarketOperationResult.Status.SUCCESS, await(market.prepare(legacy, seller, "legacy", new BigDecimal("100"),
                new BigDecimal("0.05"), 12)).status());
        await(market.activate(legacy, seller));
        UUID order = place("minecraft:iron_ingot", 100, 1);
        assertEquals(BuyOrderOperationResult.Status.NO_MATCH, await(orders.fillListing(order, seller, legacy, "legacy-fill")).status());
    }

    private UUID place(String matcher, long price, int quantity) {
        UUID id = UUID.randomUUID();
        assertEquals(BuyOrderOperationResult.Status.SUCCESS,
                await(orders.place(id, buyer, matcher, price, quantity, "place:" + id)).status());
        return id;
    }
    private UUID list(UUID owner, MarketGoods goods, UUID identity) {
        UUID id = UUID.randomUUID();
        var prepared = await(market.prepare(id, owner, "escrow-payload:" + id, new BigDecimal("100.00"),
                new BigDecimal(identity == null ? "0.05" : "0.10"), 12, Optional.ofNullable(identity), Optional.of(goods)));
        assertEquals(MarketOperationResult.Status.SUCCESS, prepared.status());
        assertEquals(BuyOrderOperationResult.Status.SUCCESS.name(), await(market.activate(id, owner)).status().name());
        return id;
    }
    private void assertReversalsReferenceEqualDebits() throws Exception {
        assertEquals(0, scalar("""
                SELECT COUNT(*) FROM rpg_ledger r LEFT JOIN rpg_ledger d ON r.reversal_of = d.idempotency_key
                WHERE r.reversal_of IS NOT NULL AND (d.id IS NULL OR r.amount <> d.amount OR r.receiver_uuid <> d.sender_uuid
                OR r.sender_uuid IS NOT NULL OR d.receiver_uuid IS NOT NULL OR d.fee <> 0 OR r.fee <> 0)
                """));
    }
    private void seed(UUID player, String amount) throws Exception {
        try (Connection c = database.dataSource().getConnection(); PreparedStatement q = c.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid,balance) VALUES (?,?)")) {
            q.setString(1, player.toString()); q.setBigDecimal(2, new BigDecimal(amount)); q.executeUpdate(); c.commit();
        }
    }
    private void setBalance(UUID player, String amount) throws Exception {
        execute("UPDATE rpg_accounts SET balance = " + new BigDecimal(amount).toPlainString() + " WHERE player_uuid = '" + player + "'");
    }
    private BigDecimal balance(UUID player) throws Exception {
        return decimal("SELECT balance FROM rpg_accounts WHERE player_uuid = '" + player + "'");
    }
    private int count(String table) throws Exception { return scalar("SELECT COUNT(*) FROM " + table); }
    private int scalar(String sql) throws Exception { return decimal(sql).intValueExact(); }
    private BigDecimal decimal(String sql) throws Exception {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next(); return rs.getBigDecimal(1);
        }
    }
    private String string(String sql) throws Exception {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next(); return rs.getString(1);
        }
    }
    private void execute(String sql) throws Exception {
        try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) { s.execute(sql); c.commit(); }
    }
    private static <T> T await(CompletionStage<T> result) { return result.toCompletableFuture().join(); }
}
