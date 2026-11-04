package cn.originalrpg.market;

import cn.originalrpg.api.model.MarketDelivery;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.core.persistence.Database;
import cn.originalrpg.core.persistence.JdbcMarketService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Actual JDBC authority + actual production callback coordinator; physical I/O is an explicit test port. */
final class MailboxDeliveryCoordinatorTest {
    private Database database;
    private MarketService market;
    private Queue main;
    private Queue jdbc;
    private Inventory inventory;
    private UUID player;
    private UUID deliveryId;
    private MarketDelivery listed;

    @BeforeEach void open() {
        for (String key : new String[]{"RPG_DATABASE_URL", "RPG_DATABASE_USER", "RPG_DATABASE_PASSWORD"}) {
            assertTrue(System.getenv().getOrDefault(key, "").isBlank(), "Mailbox tests refuse runtime database overrides");
        }
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", "jdbc:h2:mem:mailbox_paper_chain_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        database = Database.open(config); database.migrate();
        player = UUID.randomUUID(); deliveryId = UUID.randomUUID();
        new JdbcMarketService(database.dataSource(), Runnable::run, BigDecimal.ZERO)
                .enqueueDelivery(deliveryId, player, "database-authoritative-payload", "fixture-reward").toCompletableFuture().join();
        main = new Queue(); jdbc = new Queue();
        market = new JdbcMarketService(database.dataSource(), jdbc, BigDecimal.ZERO);
        inventory = new Inventory();
        // A stale UI payload must never be used to render an item.
        listed = new MarketDelivery(deliveryId, player, "stale-ui-payload", "fixture-reward", Instant.now());
    }
    @AfterEach void close() { database.close(); }

    @Test void databaseAuthorizationPrecedesPhysicalInsertAndBothMarkersThawAfterAcknowledgement() {
        var result = coordinator(market).deliver(listed, inventory);
        pump(result);
        assertEquals(MailboxDeliveryCoordinator.Outcome.DELIVERED, result.toCompletableFuture().join());
        assertEquals(1, inventory.insertions);
        assertEquals(1, inventory.thaws);
        assertNull(inventory.receipt);
        assertEquals("database-authoritative-payload", inventory.renderedPayload);
        assertEquals("DELIVERED", state());
    }

    @Test void lostAcknowledgementReplyRetainsReceiptAndRecoveryNeverInsertsAgain() {
        var result = coordinator(loseReply("acknowledgeDelivery")).deliver(listed, inventory);
        pump(result);
        assertEquals(MailboxDeliveryCoordinator.Outcome.BLOCKED, result.toCompletableFuture().join());
        assertEquals("DELIVERED", state());
        assertNotNull(inventory.receipt);
        assertEquals(0, inventory.thaws);
        var recovery = coordinator(market).recover(deliveryId, inventory); pump(recovery);
        assertEquals(MailboxDeliveryCoordinator.Outcome.DELIVERED, recovery.toCompletableFuture().join());
        assertEquals(1, inventory.insertions);
        assertEquals(1, inventory.thaws);
        assertEquals(1, inventory.persistedReceipts);
    }

    @Test void lostAuthorizationReplyIsIndeterminateEvenWhenNoItemIsVisible() {
        var result = coordinator(loseReply("authorizeDelivery")).deliver(listed, inventory); pump(result);
        assertEquals(MailboxDeliveryCoordinator.Outcome.BLOCKED, result.toCompletableFuture().join());
        assertEquals("STAGING", state());
        assertEquals(0, inventory.insertions);
        var retry = coordinator(market).deliver(listed, inventory); pump(retry);
        assertEquals(MailboxDeliveryCoordinator.Outcome.BLOCKED, retry.toCompletableFuture().join());
        assertEquals(0, inventory.insertions);
        assertNull(inventory.receipt);
    }

    @Test void reconnectBetweenAuthorizationAndItsCallbackCannotReceiveAnOldPhysicalInsert() {
        var result = coordinator(market).deliver(listed, inventory);
        main.run(); jdbc.run(); // claim commits
        main.run(); jdbc.run(); // physical authorization commits
        assertEquals("STAGING", state());
        inventory.online = false; // original Player handle was replaced before the main-thread callback
        pump(result);
        assertEquals(MailboxDeliveryCoordinator.Outcome.ABANDONED, result.toCompletableFuture().join());
        assertEquals(0, inventory.insertions);
        inventory.online = true;
        var retry = coordinator(market).deliver(listed, inventory); pump(retry);
        assertEquals(MailboxDeliveryCoordinator.Outcome.BLOCKED, retry.toCompletableFuture().join());
        assertEquals(0, inventory.insertions);
    }

    @Test void fullInventoryReleasesOnlyTheUnstartedReservation() {
        inventory.full = true;
        var result = coordinator(market).deliver(listed, inventory); pump(result);
        assertEquals(MailboxDeliveryCoordinator.Outcome.INVENTORY_FULL, result.toCompletableFuture().join());
        assertEquals("PENDING", state());
        assertEquals(0, inventory.insertions);
        inventory.full = false;
        var retry = coordinator(market).deliver(listed, inventory); pump(retry);
        assertEquals(MailboxDeliveryCoordinator.Outcome.DELIVERED, retry.toCompletableFuture().join());
        assertEquals(1, inventory.insertions);
    }

    @Test void uncertainPhysicalSaveMustSucceedOnRecoveryBeforeSqlAcknowledgement() {
        inventory.stageSaveFails = true;
        var result = coordinator(market).deliver(listed, inventory); pump(result);
        assertEquals(MailboxDeliveryCoordinator.Outcome.ERROR, result.toCompletableFuture().join());
        assertNotNull(inventory.receipt);
        assertEquals("STAGING", state());
        inventory.recoverySaveFails = true;
        var failedSave = coordinator(market).recover(deliveryId, inventory); pump(failedSave);
        assertEquals(MailboxDeliveryCoordinator.Outcome.BLOCKED, failedSave.toCompletableFuture().join());
        assertEquals("STAGING", state());
        assertEquals(0, inventory.thaws);
        inventory.recoverySaveFails = false;
        var recovered = coordinator(market).recover(deliveryId, inventory); pump(recovered);
        assertEquals(MailboxDeliveryCoordinator.Outcome.DELIVERED, recovered.toCompletableFuture().join());
        assertEquals(1, inventory.insertions);
        assertEquals(1, inventory.thaws);
    }

    @Test void deniedAcknowledgementAndDuplicateReceiptsNeverClearPhysicalMarkers() {
        var result = coordinator(denyAcknowledgement()).deliver(listed, inventory); pump(result);
        assertEquals(MailboxDeliveryCoordinator.Outcome.BLOCKED, result.toCompletableFuture().join());
        assertEquals(1, inventory.insertions); assertNotNull(inventory.receipt); assertEquals(0, inventory.thaws);
        inventory.ambiguous = true;
        var duplicate = coordinator(market).recover(deliveryId, inventory); pump(duplicate);
        assertEquals(MailboxDeliveryCoordinator.Outcome.BLOCKED, duplicate.toCompletableFuture().join());
        assertEquals("STAGING", state()); assertEquals(0, inventory.thaws);
    }

    @Test void legacyTokenlessReceiptUsesOnlyLegacyAcknowledgementAndIsNeverReinserted() {
        inventory.receipt = new MailboxDeliveryCoordinator.Receipt(MailboxDeliveryCoordinator.ReceiptKind.LEGACY, null);
        var recovery = coordinator(market).recover(deliveryId, inventory); pump(recovery);
        assertEquals(MailboxDeliveryCoordinator.Outcome.DELIVERED, recovery.toCompletableFuture().join());
        assertEquals(0, inventory.insertions); assertEquals(1, inventory.thaws);
        assertEquals("DELIVERED", state());
    }

    @Test void pluginShutdownBeforeTheNextMainThreadCallbackCannotMutateInventory() {
        var result = coordinator(market).deliver(listed, inventory);
        main.run(); // submit claim
        main.closed = true;
        jdbc.run(); // completed SQL cannot schedule physical work into an unloaded plugin
        assertEquals(MailboxDeliveryCoordinator.Outcome.ABANDONED, result.toCompletableFuture().join());
        assertEquals("CLAIMED", state()); assertEquals(0, inventory.insertions);
    }

    private MailboxDeliveryCoordinator<PreparedItem> coordinator(MarketService service) { return new MailboxDeliveryCoordinator<>(service, main); }
    private void pump(CompletionStage<?> result) {
        for (int step = 0; step < 40 && !result.toCompletableFuture().isDone(); step++) {
            if (!main.tasks.isEmpty()) main.run();
            if (!jdbc.tasks.isEmpty()) jdbc.run();
        }
        assertTrue(result.toCompletableFuture().isDone(), "Callback chain did not settle within its bounded steps");
    }
    private String state() {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT state FROM rpg_market_deliveries WHERE id = ?")) {
            statement.setString(1, deliveryId.toString()); try (var result = statement.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    private MarketService loseReply(String selected) {
        AtomicBoolean once = new AtomicBoolean(true);
        return (MarketService) Proxy.newProxyInstance(MarketService.class.getClassLoader(), new Class<?>[]{MarketService.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(market, args);
                if (!method.getName().equals(selected) || !once.compareAndSet(true, false)) return result;
                return ((CompletionStage<?>) result).thenCompose(ignored -> CompletableFuture.failedFuture(new SQLException("Lost committed response")));
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private MarketService denyAcknowledgement() {
        return (MarketService) Proxy.newProxyInstance(MarketService.class.getClassLoader(), new Class<?>[]{MarketService.class}, (proxy, method, args) -> {
            if (method.getName().equals("acknowledgeDelivery")) return CompletableFuture.completedFuture(false);
            try { return method.invoke(market, args); } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private record PreparedItem(String payload, UUID token) { }
    private final class Inventory implements MailboxDeliveryCoordinator.Inventory<PreparedItem> {
        boolean online = true, full, stageSaveFails, recoverySaveFails, ambiguous;
        int insertions, thaws, persistedReceipts;
        String renderedPayload;
        MailboxDeliveryCoordinator.Receipt receipt;
        private void requireMain() { assertTrue(main.running, "Inventory access escaped the Paper-thread executor"); }
        @Override public UUID playerId() { requireMain(); return player; }
        @Override public boolean current() { requireMain(); return online; }
        @Override public MailboxDeliveryCoordinator.Receipt receipt(UUID id) {
            requireMain(); assertEquals(deliveryId, id);
            return ambiguous ? new MailboxDeliveryCoordinator.Receipt(MailboxDeliveryCoordinator.ReceiptKind.AMBIGUOUS, null)
                    : receipt == null ? MailboxDeliveryCoordinator.Receipt.none() : receipt;
        }
        @Override public MailboxDeliveryCoordinator.Prepared<PreparedItem> prepare(MarketDelivery delivery, UUID token) {
            requireMain();
            return full ? new MailboxDeliveryCoordinator.Prepared<>(null, MailboxDeliveryCoordinator.Outcome.INVENTORY_FULL)
                    : new MailboxDeliveryCoordinator.Prepared<>(new PreparedItem(delivery.itemPayload(), token), null);
        }
        @Override public boolean stage(PreparedItem item) {
            requireMain(); assertEquals("STAGING", state()); assertNull(receipt, "The same physical delivery was inserted twice");
            insertions++; renderedPayload = item.payload();
            receipt = new MailboxDeliveryCoordinator.Receipt(MailboxDeliveryCoordinator.ReceiptKind.TOKEN, item.token());
            if (stageSaveFails) throw new IllegalStateException("Injected player save failure after physical insertion");
            return true;
        }
        @Override public boolean persistReceipt(UUID id, UUID token) {
            requireMain(); assertEquals(deliveryId, id);
            if (recoverySaveFails) return false;
            assertNotNull(receipt); assertEquals(token, receipt.token()); persistedReceipts++; return true;
        }
        @Override public boolean thaw(UUID id, UUID token) {
            requireMain(); assertEquals("DELIVERED", state()); assertEquals(deliveryId, id);
            assertNotNull(receipt); assertEquals(token, receipt.token()); thaws++; receipt = null; return true;
        }
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean running, closed;
        @Override public void execute(Runnable task) { if (closed) throw new java.util.concurrent.RejectedExecutionException(); tasks.add(task); }
        void run() { running = true; try { tasks.removeFirst().run(); } finally { running = false; } }
    }
}
