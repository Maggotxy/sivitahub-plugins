package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.MarketDeliveryAuthorization;
import cn.originalrpg.api.model.MarketDeliveryClaim;
import cn.originalrpg.api.model.PlayerSessionLease;
import cn.originalrpg.api.service.MarketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

final class JdbcMailboxDeliveryClaimTest {
    private NetworkDatabaseFixture fixture;
    private JdbcMarketService market;
    private UUID player;
    private UUID delivery;

    @BeforeEach void open() throws Exception {
        fixture = new NetworkDatabaseFixture();
        market = new JdbcMarketService(fixture.dataSource, Runnable::run, BigDecimal.ZERO);
        player = UUID.randomUUID(); delivery = UUID.randomUUID();
        assertTrue(await(market.enqueueDelivery(delivery, player, "authoritative-fixture-item", "fixture-reward")));
    }
    @AfterEach void close() throws Exception { assertNull(SessionWriteContext.current()); fixture.close(); }

    @Test void claimAuthorizePhysicalReceiptAndAcknowledgementHaveDistinctAuthority() {
        UUID token = UUID.randomUUID();
        var claim = await(market.claimDelivery(delivery, player, token));
        assertEquals(MarketDeliveryClaim.Status.CLAIMED, claim.status());
        assertEquals(claim, await(market.claimDelivery(delivery, player, token)));
        assertFalse(await(market.acknowledgeDelivery(delivery, player, token)));
        assertFalse(await(market.markDelivered(delivery, player)), "Legacy acknowledgement must not bypass a token claim");
        assertEquals(MarketDeliveryAuthorization.AUTHORIZED, await(market.authorizeDelivery(delivery, player, token)));
        assertEquals(MarketDeliveryAuthorization.INDETERMINATE, await(market.authorizeDelivery(delivery, player, token)));
        assertFalse(await(market.acknowledgeDelivery(delivery, UUID.randomUUID(), token)));
        assertFalse(await(market.acknowledgeDelivery(delivery, player, UUID.randomUUID())));
        assertTrue(await(market.acknowledgeDelivery(delivery, player, token)));
        assertTrue(await(market.acknowledgeDelivery(delivery, player, token)));
        assertFalse(await(market.markDelivered(delivery, player)));
        assertTrue(await(market.pendingDeliveries(player, 20)).isEmpty());
        assertEquals("DELIVERED", state());
    }

    @Test void onlyAnUnstartedExpiredReservationMayBeClaimedAgain() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(MarketDeliveryClaim.Status.CLAIMED, await(market.claimDelivery(delivery, player, first)).status());
        expireClaim();
        assertEquals(MarketDeliveryAuthorization.EXPIRED, await(market.authorizeDelivery(delivery, player, first)));
        assertEquals(MarketDeliveryClaim.Status.REFUSED, await(market.claimDelivery(delivery, player, first)).status());
        assertEquals(MarketDeliveryClaim.Status.CLAIMED, await(market.claimDelivery(delivery, player, second)).status());
        assertEquals(MarketDeliveryAuthorization.REFUSED, await(market.authorizeDelivery(delivery, player, first)));
        assertEquals(MarketDeliveryAuthorization.AUTHORIZED, await(market.authorizeDelivery(delivery, player, second)));
        expireClaim();
        assertEquals(MarketDeliveryClaim.Status.INDETERMINATE, await(market.claimDelivery(delivery, player, UUID.randomUUID())).status());
        assertFalse(await(market.releaseDeliveryClaim(delivery, player, second)));
        assertTrue(await(market.acknowledgeDelivery(delivery, player, second)), "Saved physical proof remains valid after the reservation deadline");
    }

    @Test void twoIndependentCallersCannotBothOwnOneMailboxRow() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            JdbcMarketService concurrent = new JdbcMarketService(fixture.dataSource, workers, BigDecimal.ZERO);
            var first = concurrent.claimDelivery(delivery, player, UUID.randomUUID()).toCompletableFuture();
            var second = concurrent.claimDelivery(delivery, player, UUID.randomUUID()).toCompletableFuture();
            var a = first.get(10, TimeUnit.SECONDS).status(); var b = second.get(10, TimeUnit.SECONDS).status();
            assertTrue(a == MarketDeliveryClaim.Status.CLAIMED && b == MarketDeliveryClaim.Status.BUSY
                    || b == MarketDeliveryClaim.Status.CLAIMED && a == MarketDeliveryClaim.Status.BUSY);
            assertEquals(1, fixture.count("rpg_market_deliveries"));
            assertEquals("CLAIMED", state());
        }
    }

    @Test void aClaimTokenCannotNameTwoDifferentDeliveries() {
        UUID token = UUID.randomUUID(), other = UUID.randomUUID();
        assertTrue(await(market.enqueueDelivery(other, player, "second-item", "fixture-reward")));
        await(market.claimDelivery(delivery, player, token));
        assertThrows(CompletionException.class, () -> await(market.claimDelivery(other, player, token)));
        assertEquals(MarketDeliveryClaim.Status.CLAIMED, await(market.claimDelivery(other, player, UUID.randomUUID())).status());
    }

    @Test void safeReleaseCannotUndoAuthorizationOrAReplacementClaim() throws Exception {
        UUID first = UUID.randomUUID(), replacement = UUID.randomUUID();
        await(market.claimDelivery(delivery, player, first));
        assertTrue(await(market.releaseDeliveryClaim(delivery, player, first)));
        assertEquals("PENDING", state());
        await(market.claimDelivery(delivery, player, replacement));
        assertFalse(await(market.releaseDeliveryClaim(delivery, player, first)));
        await(market.authorizeDelivery(delivery, player, replacement));
        assertFalse(await(market.releaseDeliveryClaim(delivery, player, replacement)));
        assertEquals("STAGING", state());
    }

    @Test void authorizationCommitWithLostReplyNeverProducesAnotherAuthorization() {
        UUID token = UUID.randomUUID(); await(market.claimDelivery(delivery, player, token));
        JdbcMarketService uncertain = new JdbcMarketService(loseOneCommitResponse(fixture.dataSource), Runnable::run, BigDecimal.ZERO);
        assertThrows(CompletionException.class, () -> await(uncertain.authorizeDelivery(delivery, player, token)));
        assertEquals("STAGING", state());
        assertEquals(MarketDeliveryAuthorization.INDETERMINATE, await(market.authorizeDelivery(delivery, player, token)));
        assertEquals(MarketDeliveryClaim.Status.INDETERMINATE, await(market.claimDelivery(delivery, player, UUID.randomUUID())).status());
    }

    @Test void acknowledgementCommitWithLostReplyIsConfirmedByTheSamePhysicalToken() {
        UUID token = UUID.randomUUID(); await(market.claimDelivery(delivery, player, token));
        await(market.authorizeDelivery(delivery, player, token));
        JdbcMarketService uncertain = new JdbcMarketService(loseOneCommitResponse(fixture.dataSource), Runnable::run, BigDecimal.ZERO);
        assertThrows(CompletionException.class, () -> await(uncertain.acknowledgeDelivery(delivery, player, token)));
        assertEquals("DELIVERED", state());
        assertTrue(await(market.acknowledgeDelivery(delivery, player, token)));
        assertFalse(await(market.acknowledgeDelivery(delivery, player, UUID.randomUUID())));
    }

    @Test void paidAndQuarantinedRowsAreRejectedAtEveryProtocolEntry() throws Exception {
        for (String source : new String[]{"commerce-reward:legacy-paid", "ordinary-quarantined"}) {
            UUID id = UUID.randomUUID(), token = UUID.randomUUID();
            try (var connection = fixture.dataSource.getConnection(); var insert = connection.prepareStatement("""
                    INSERT INTO rpg_market_deliveries (id,player_uuid,item_payload,source,state,claim_token,quarantine_reason)
                    VALUES (?,?,'kept-evidence',?,'STAGING',?,?)
                    """)) {
                insert.setString(1, id.toString()); insert.setString(2, player.toString()); insert.setString(3, source);
                insert.setString(4, token.toString()); insert.setString(5, source.startsWith("commerce-") ? null : "REVIEW_REQUIRED");
                insert.executeUpdate(); connection.commit();
            }
            assertEquals(MarketDeliveryClaim.Status.REFUSED, await(market.claimDelivery(id, player, UUID.randomUUID())).status());
            assertEquals(MarketDeliveryAuthorization.REFUSED, await(market.authorizeDelivery(id, player, token)));
            assertFalse(await(market.releaseDeliveryClaim(id, player, token)));
            assertFalse(await(market.acknowledgeDelivery(id, player, token)));
            assertFalse(await(market.markDelivered(id, player)));
        }
        assertEquals(1, await(market.pendingDeliveries(player, 100)).size());
    }

    @Test void legacyReceiptRecoveryWorksOnlyForRowsWithoutAnyClaimToken() {
        assertTrue(await(market.markDelivered(delivery, player)));
        assertTrue(await(market.markDelivered(delivery, player)));
        assertFalse(await(market.markDelivered(delivery, UUID.randomUUID())));
        assertFalse(await(market.acknowledgeDelivery(delivery, player, UUID.randomUUID())));
    }

    @Test void queuedOldActorCannotAuthorizeButTheNewSessionCanRecoverAnExistingPhysicalReceipt() throws Exception {
        NetworkTestClock clock = new NetworkTestClock();
        JdbcNodeLease node = new JdbcNodeLease(fixture.dataSource, Runnable::run, "mailbox-node", UUID.randomUUID(), Duration.ofMinutes(5), clock);
        assertTrue(node.acquire());
        JdbcSessionLease sessions = new JdbcSessionLease(fixture.dataSource, Runnable::run, node.nodeId(), node.processToken(), Duration.ofMinutes(1), clock);
        PlayerSessionLease old = await(sessions.acquire(player, UUID.randomUUID())).orElseThrow();
        var worker = new QueuedExecutor();
        MarketService fenced = SessionGuardedService.wrap(MarketService.class,
                new JdbcMarketService(new SessionFencedDataSource(fixture.dataSource), worker, BigDecimal.ZERO), sessions);
        UUID token = UUID.randomUUID();
        var claim = fenced.claimDelivery(delivery, player, token); worker.run(); assertEquals(MarketDeliveryClaim.Status.CLAIMED, await(claim).status());
        var queuedOldAuthorization = fenced.authorizeDelivery(delivery, player, token);
        await(sessions.release(old));
        PlayerSessionLease next = await(sessions.acquire(player, UUID.randomUUID())).orElseThrow();
        worker.run(); assertThrows(CompletionException.class, () -> await(queuedOldAuthorization));
        var wrongSession = fenced.authorizeDelivery(delivery, player, token); worker.run();
        assertEquals(MarketDeliveryAuthorization.REFUSED, await(wrongSession));
        expireClaim();
        UUID freshToken = UUID.randomUUID(); var fresh = fenced.claimDelivery(delivery, player, freshToken); worker.run(); await(fresh);
        var authorize = fenced.authorizeDelivery(delivery, player, freshToken); worker.run();
        assertEquals(MarketDeliveryAuthorization.AUTHORIZED, await(authorize));
        await(sessions.release(next)); await(sessions.acquire(player, UUID.randomUUID())).orElseThrow();
        var recovered = fenced.acknowledgeDelivery(delivery, player, freshToken); worker.run();
        assertTrue(await(recovered));
    }

    private void expireClaim() throws SQLException {
        try (var connection = fixture.dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE rpg_market_deliveries SET claim_expires_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.EPOCH)); statement.setString(2, delivery.toString());
            statement.executeUpdate(); connection.commit();
        }
    }
    private String state() {
        try (var connection = fixture.dataSource.getConnection(); var query = connection.prepareStatement("SELECT state FROM rpg_market_deliveries WHERE id = ?")) {
            query.setString(1, delivery.toString()); try (var result = query.executeQuery()) { result.next(); return result.getString(1); }
        } catch (SQLException error) { throw new IllegalStateException(error); }
    }
    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private static DataSource loseOneCommitResponse(DataSource dataSource) {
        AtomicBoolean once = new AtomicBoolean(true);
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            try {
                Object value = method.invoke(dataSource, args);
                if (!method.getName().equals("getConnection")) return value;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, call, parameters) -> {
                    try {
                        Object result = call.invoke(value, parameters);
                        if (call.getName().equals("commit") && once.compareAndSet(true, false)) throw new SQLException("Injected lost commit reply");
                        return result;
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
            } catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }
    private static final class QueuedExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable task) { queue.add(task); }
        void run() { queue.removeFirst().run(); }
    }
}
