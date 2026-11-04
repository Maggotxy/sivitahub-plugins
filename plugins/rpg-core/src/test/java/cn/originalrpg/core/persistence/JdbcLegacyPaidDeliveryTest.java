package cn.originalrpg.core.persistence;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Upgrade and receipt tests use the canonical schema, including its repeatable migration. */
final class JdbcLegacyPaidDeliveryTest {
    private Database database;
    private JdbcMarketService market;
    private UUID player;

    @BeforeEach
    void open() {
        assertTrue(System.getenv("RPG_DATABASE_URL") == null || System.getenv("RPG_DATABASE_URL").isBlank());
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", "jdbc:h2:mem:legacy_paid_mail_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        database = Database.open(config);
        database.migrate();
        market = new JdbcMarketService(database.dataSource(), Runnable::run, BigDecimal.ZERO);
        player = UUID.randomUUID();
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void upgradeHoldsUndeliveredPaidMaterialsAndPreservesHistoricalPayloadAndReceipt() throws Exception {
        UUID paid = seed("commerce-reward:veteran-support", "PENDING");
        UUID delivered = seed("commerce-reward:old-delivered", "DELIVERED");
        UUID earned = seed("commerce-purchase:earned-item", "PENDING");
        database.migrate();
        Timestamp heldAt;
        try (Connection connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement("SELECT * FROM rpg_market_deliveries WHERE id = ?")) {
            statement.setString(1, paid.toString());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(player.toString(), rows.getString("player_uuid"));
                assertEquals("original-payload", rows.getString("item_payload"));
                assertEquals("commerce-reward:veteran-support", rows.getString("source"));
                assertEquals("QUARANTINED", rows.getString("state"));
                assertEquals("LEGACY_PAID_REWARD", rows.getString("quarantine_reason"));
                heldAt = rows.getTimestamp("quarantined_at");
                assertNotNull(heldAt);
                assertNull(rows.getTimestamp("delivered_at"));
            }
        }
        database.migrate();
        assertEquals(heldAt, timestamp(paid, "quarantined_at"));
        assertEquals("DELIVERED", state(delivered));
        assertNull(timestamp(delivered, "quarantined_at"));
        assertEquals("PENDING", state(earned));
        var pending = market.pendingDeliveries(player, 10).toCompletableFuture().join();
        assertEquals(1, pending.size());
        assertEquals(earned, pending.getFirst().id());
    }

    @Test
    void sourceGuardBlocksEvenUnmigratedLegacyRowsAndAlreadyDeliveredPaidReceipts() throws Exception {
        UUID pending = seed("commerce-reward:pending", "PENDING");
        UUID delivered = seed("commerce-reward:delivered", "DELIVERED");
        assertTrue(market.pendingDeliveries(player, 10).toCompletableFuture().join().isEmpty());
        assertFalse(market.markDelivered(pending, player).toCompletableFuture().join());
        assertFalse(market.markDelivered(delivered, player).toCompletableFuture().join());
        assertEquals("PENDING", state(pending));
        assertEquals("DELIVERED", state(delivered));
    }

    @Test
    void prohibitedProducerCannotCreatePaidMaterialMailThroughGenericEnqueue() throws Exception {
        UUID delivery = UUID.randomUUID();
        assertFalse(market.enqueueDelivery(delivery, player, "payload", "commerce-reward:new")
                .toCompletableFuture().join());
        assertNull(state(delivery));
    }

    @Test
    void permittedReceiptCanReplayLostAcknowledgementOnlyForItsOwner() throws Exception {
        UUID delivery = seed("community-storage:" + UUID.randomUUID(), "PENDING");
        assertFalse(market.markDelivered(delivery, UUID.randomUUID()).toCompletableFuture().join());
        assertTrue(market.markDelivered(delivery, player).toCompletableFuture().join());
        Timestamp deliveredAt = timestamp(delivery, "delivered_at");
        assertNotNull(deliveredAt);
        assertTrue(market.markDelivered(delivery, player).toCompletableFuture().join());
        assertEquals(deliveredAt, timestamp(delivery, "delivered_at"));
        assertFalse(market.markDelivered(delivery, UUID.randomUUID()).toCompletableFuture().join());
        assertTrue(market.pendingDeliveries(player, 10).toCompletableFuture().join().isEmpty());
    }

    @Test
    void unknownAndQuarantinedDeliveriesNeverAuthorizePhysicalReceiptThaw() throws Exception {
        UUID held = seed("market-cancel:review", "QUARANTINED");
        assertFalse(market.markDelivered(held, player).toCompletableFuture().join());
        assertFalse(market.markDelivered(UUID.randomUUID(), player).toCompletableFuture().join());
        assertEquals("QUARANTINED", state(held));
        assertNull(timestamp(held, "delivered_at"));
    }

    private UUID seed(String source, String state) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = database.dataSource().getConnection(); var insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id,player_uuid,item_payload,source,state)
                VALUES (?,?,'original-payload',?,?)
                """)) {
            insert.setString(1, id.toString()); insert.setString(2, player.toString());
            insert.setString(3, source); insert.setString(4, state);
            assertEquals(1, insert.executeUpdate());
            connection.commit();
        }
        return id;
    }

    private String state(UUID id) throws Exception {
        try (Connection connection = database.dataSource().getConnection();
             var query = connection.prepareStatement("SELECT state FROM rpg_market_deliveries WHERE id = ?")) {
            query.setString(1, id.toString());
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private Timestamp timestamp(UUID id, String column) throws Exception {
        if (!column.equals("quarantined_at") && !column.equals("delivered_at")) throw new IllegalArgumentException();
        try (Connection connection = database.dataSource().getConnection();
             var query = connection.prepareStatement("SELECT " + column + " FROM rpg_market_deliveries WHERE id = ?")) {
            query.setString(1, id.toString());
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getTimestamp(1); }
        }
    }
}
