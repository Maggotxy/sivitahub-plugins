package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.originalrpg.api.model.CommunityStorageResult.Status;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the real migrated schema and both market authorities; no second inventory or fake chest ledger. */
final class JdbcCommunityStorageServiceTest {
    private final UUID communityId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID officer = UUID.randomUUID();
    private final UUID donor = UUID.randomUUID();
    private final UUID recipient = UUID.randomUUID();
    private final MarketGoods stone = new MarketGoods("minecraft:stone", 32, null);
    private final MutableClock clock = new MutableClock(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    private CommunityDatabaseFixture fixture;
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcCommunityStorageService storage;
    private JdbcMarketService market;
    private JdbcMarketBuyOrderService orders;
    private JdbcCommunityLifecycleService lifecycle;
    private JdbcCommunityService ordinary;

    @BeforeEach
    void setup() throws Exception {
        fixture = new CommunityDatabaseFixture();
        dataSource = fixture.dataSource;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        storage = service(dataSource, true, 128);
        market = new JdbcMarketService(dataSource, executor, BigDecimal.ZERO);
        orders = new JdbcMarketBuyOrderService(dataSource, executor, BigDecimal.ZERO);
        ordinary = new JdbcCommunityService(dataSource, executor);
        lifecycle = new JdbcCommunityLifecycleService(dataSource, executor, true, CommunityLifecyclePolicy.defaults(), clock);
        try (Connection connection = dataSource.getConnection()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO rpg_communities (id,name,normalized_name,tag,normalized_tag,owner_uuid,capacity)
                    VALUES (?, 'Wayfarers', 'wayfarers', 'WAY', 'way', ?, 20)
                    """)) {
                insert.setString(1, communityId.toString()); insert.setString(2, owner.toString()); insert.executeUpdate();
            }
            for (var member : java.util.Map.of(owner, "OWNER", officer, "OFFICER", donor, "MEMBER", recipient, "MEMBER").entrySet()) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO rpg_community_members (community_id,player_uuid,role,joined_at,last_authenticated_at)
                        VALUES (?,?,?,?,?)
                        """)) {
                    insert.setString(1, communityId.toString()); insert.setString(2, member.getKey().toString());
                    insert.setString(3, member.getValue());
                    insert.setTimestamp(4, Timestamp.from(clock.instant().minus(Duration.ofDays(60))));
                    insert.setTimestamp(5, Timestamp.from(clock.instant().minus(Duration.ofDays(31))));
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO rpg_accounts (player_uuid,balance) VALUES (?,1000)")) {
                    insert.setString(1, member.getKey().toString()); insert.executeUpdate();
                }
            }
            connection.commit();
        }
    }

    @AfterEach
    void close() throws Exception {
        if (executor != null) executor.close();
        if (fixture != null) fixture.close();
    }

    @Test
    void coreAcceptsOnlyTheReviewedConstructionMatchersAndCurrentMembership() throws Exception {
        for (MarketGoods rejected : List.of(new MarketGoods("minecraft:diamond", 32, null),
                new MarketGoods("minecraft:iron_block", 16, null),
                new MarketGoods("equipment:sword:rare", 1, donor))) {
            assertEquals(MarketOperationResult.Status.INVALID,
                    await(storage.prepare(UUID.randomUUID(), donor, "payload", rejected)).status());
        }
        assertEquals(MarketOperationResult.Status.NOT_OWNER,
                await(storage.prepare(UUID.randomUUID(), UUID.randomUUID(), "payload", stone)).status());
        assertEquals(MarketOperationResult.Status.INVALID,
                await(storage.prepare(UUID.randomUUID(), donor, "x".repeat(64_001), stone)).status());
        assertEquals(0, count("rpg_market_listings"));
        assertEquals(0, count("rpg_community_lifecycle_audit"));
    }

    @Test
    void prepareIsImmutableAndCapacityIncludesUnresolvedPhysicalTransfers() throws Exception {
        var oneSlot = service(dataSource, true, 1);
        UUID id = UUID.randomUUID();
        assertEquals(MarketOperationResult.Status.SUCCESS, await(oneSlot.prepare(id, donor, "original", stone)).status());
        assertEquals(MarketOperationResult.Status.DUPLICATE, await(oneSlot.prepare(id, donor, "original", stone)).status());
        assertEquals(MarketOperationResult.Status.INVALID, await(oneSlot.prepare(id, donor, "replacement", stone)).status());
        assertEquals(MarketOperationResult.Status.LISTING_LIMIT,
                await(oneSlot.prepare(UUID.randomUUID(), recipient, "second", stone)).status());
        assertEquals("original", text("SELECT item_payload FROM rpg_market_listings"));
        assertEquals(1, await(oneSlot.preparedDeposits(donor)).size());
        assertTrue(await(market.preparedListings(donor)).isEmpty());
        assertEquals(1, count("rpg_community_lifecycle_audit"));
    }

    @Test
    void custodyIsClosedToEveryOrdinaryMarketDirectIdAndOrderFillPath() throws Exception {
        UUID listing = stored(donor, "isolated-custody");
        assertTrue(await(market.activeListings(100)).isEmpty());
        assertTrue(await(market.activeListings(donor, 100)).isEmpty());
        assertTrue(await(market.listing(listing)).isEmpty());
        assertEquals(MarketOperationResult.Status.NOT_FOUND, await(market.activate(listing, donor)).status());
        assertEquals(MarketOperationResult.Status.NOT_FOUND, await(market.quarantinePrepared(listing, donor)).status());
        assertEquals(MarketOperationResult.Status.NOT_FOUND, await(market.purchase(listing, recipient, "direct-buy")).status());
        assertEquals(MarketOperationResult.Status.NOT_FOUND, await(market.cancel(listing, donor)).status());
        assertEquals(0, await(market.expireListings(Instant.now().plus(Duration.ofDays(365)), 100)));
        assertNotEquals(MarketOperationResult.Status.SUCCESS, await(market.prepare(listing, donor, "replacement",
                BigDecimal.TEN, new BigDecimal("0.05"), 12, Optional.empty(), Optional.of(stone))).status());
        UUID order = UUID.randomUUID();
        assertEquals(BuyOrderOperationResult.Status.SUCCESS,
                await(orders.place(order, recipient, "minecraft:stone", 1, 32, "custody-buy-order")).status());
        assertEquals(BuyOrderOperationResult.Status.NOT_FOUND,
                await(orders.fillListing(order, donor, listing, "custody-fill")).status());
        assertEquals("ACTIVE", text("SELECT state FROM rpg_market_listings"));
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals("1000.00", text("SELECT balance FROM rpg_accounts WHERE player_uuid = '" + donor + "'"));
    }

    @Test
    void activationAndQuarantineRecoveryUseOnlyTheAcceptedDonorAndDoNotDependOnNewDepositGate() throws Exception {
        UUID id = prepared(donor, "recoverable");
        assertEquals(MarketOperationResult.Status.NOT_OWNER, await(storage.activate(id, recipient)).status());
        var disabled = service(dataSource, false, 128);
        assertEquals(MarketOperationResult.Status.INVALID, await(disabled.prepare(UUID.randomUUID(), donor, "no-new", stone)).status());
        assertEquals(MarketOperationResult.Status.SUCCESS, await(disabled.activate(id, donor)).status());
        assertEquals(MarketOperationResult.Status.SUCCESS, await(disabled.activate(id, donor)).status());
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'STORAGE_STORED'"));
        assertEquals(Status.DISABLED, await(disabled.requestWithdrawal(owner, id, recipient, "disabled-withdrawal")).status());
    }

    @Test
    void independentApprovalAtomicallyClosesEscrowAndQueuesTheOriginalPayloadForTheNamedRecipient() throws Exception {
        UUID listing = stored(donor, "original-payload");
        var request = request(owner, listing, recipient);
        assertEquals(1, request.approvals().size());
        assertEquals(Status.ALREADY_APPROVED, await(storage.approveWithdrawal(owner, request.id())).status());
        assertEquals(Status.NOT_ALLOWED, await(storage.approveWithdrawal(donor, request.id())).status());
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals(Status.DELIVERED, await(storage.approveWithdrawal(officer, request.id())).status());
        assertEquals("CANCELLED", text("SELECT state FROM rpg_market_listings"));
        assertEquals("COMPLETED", text("SELECT state FROM rpg_community_storage_requests"));
        var deliveries = await(market.pendingDeliveries(recipient, 10));
        assertEquals(1, deliveries.size());
        assertEquals("original-payload", deliveries.getFirst().itemPayload());
        assertEquals("community-storage:" + request.id(), deliveries.getFirst().source());
        assertTrue(await(market.markDelivered(deliveries.getFirst().id(), recipient)));
        assertTrue(await(market.pendingDeliveries(recipient, 10)).isEmpty());
        assertEquals(0, count("rpg_ledger"), "custody is a donated material movement, never currency minting or a tax-free equipment sale");
    }

    @Test
    void anExistingRequestCannotHaveItsRecipientSilentlyReplaced() {
        UUID listing = stored(donor, "immutable-recipient");
        var first = await(storage.requestWithdrawal(owner, listing, recipient, "same-intent"));
        assertEquals(Status.INVALID, await(storage.requestWithdrawal(owner, listing, owner, "same-intent")).status());
        var replacement = await(storage.requestWithdrawal(officer, listing, officer, "different-intent"));
        assertEquals(Status.ACTIVE_REQUEST, replacement.status());
        assertEquals(recipient, replacement.request().orElseThrow().recipientId());
        assertEquals(first.request().orElseThrow().id(), replacement.request().orElseThrow().id());
    }

    @Test
    void recipientLeavingAndRejoiningInvalidatesOldConsentWithoutReclaimingDonations() throws Exception {
        UUID listing = stored(donor, "member-lifecycle");
        UUID requestId = request(owner, listing, donor).id();
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                await(ordinary.leave(donor, "2026-W44", BigDecimal.TEN)).status());
        assertTrue(await(storage.snapshot(donor)).isEmpty());
        assertEquals("ACTIVE", text("SELECT state FROM rpg_market_listings"));
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                await(ordinary.invite(owner, donor, 3, 24, "2026-W44", BigDecimal.TEN)).status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                await(ordinary.accept(donor, "WAY", "2026-W44", BigDecimal.TEN)).status());
        assertEquals(Status.CONDITIONS_CHANGED, await(storage.approveWithdrawal(officer, requestId)).status());
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals("ACTIVE", text("SELECT state FROM rpg_market_listings"));
    }

    @Test
    void proposerLosingMembershipCannotLeaveAnExecutableOldSignature() throws Exception {
        UUID listing = stored(donor, "stale-signer");
        UUID request = request(owner, listing, recipient).id();
        await(ordinary.transfer(owner, officer, "2026-W44", BigDecimal.TEN));
        await(ordinary.leave(owner, "2026-W44", BigDecimal.TEN));
        assertEquals(Status.CONDITIONS_CHANGED, await(storage.approveWithdrawal(officer, request)).status());
        assertEquals(0, count("rpg_market_deliveries"));
    }

    @Test
    void competingRequestsAndRepeatedSecondSignaturesProduceOneDelivery() throws Exception {
        UUID listing = stored(donor, "racing-supplies");
        List<CompletableFuture<CommunityStorageResult>> requests = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            requests.add(storage.requestWithdrawal(owner, listing, recipient, "competing:" + index).toCompletableFuture());
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, requests.stream().map(CompletableFuture::join).filter(result -> result.status() == Status.SUCCESS).count());
        UUID requestId = requests.getFirst().join().request().orElseThrow().id();
        List<CompletableFuture<CommunityStorageResult>> approvals = new ArrayList<>();
        for (int index = 0; index < 16; index++) approvals.add(storage.approveWithdrawal(officer, requestId).toCompletableFuture());
        CompletableFuture.allOf(approvals.toArray(CompletableFuture[]::new)).join();
        assertTrue(approvals.stream().allMatch(result -> result.join().status() == Status.DELIVERED));
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_community_approvals WHERE action = 'community.withdrawal'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'STORAGE_WITHDRAWN'"));
    }

    @Test
    void failureAfterMailInsertRollsBackDeliveryEscrowAndSecondSignatureTogether() throws Exception {
        UUID listing = stored(donor, "rollback-custody");
        UUID request = request(owner, listing, recipient).id();
        DataSource failure = wrap((connection, method, args) -> {
            if (method.getName().equals("prepareStatement")
                    && ((String) args[0]).contains("UPDATE rpg_market_listings SET state = 'CANCELLED'")) {
                throw new SQLException("injected after mailbox insert");
            }
            return invoke(connection, method, args);
        });
        assertThrows(CompletionException.class, () -> await(service(failure, true, 128).approveWithdrawal(officer, request)));
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals("ACTIVE", text("SELECT state FROM rpg_market_listings"));
        assertEquals("PENDING", text("SELECT state FROM rpg_community_storage_requests"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_community_approvals WHERE action = 'community.withdrawal'"));
        assertEquals(Status.DELIVERED, await(storage.approveWithdrawal(officer, request)).status());
        assertEquals(1, count("rpg_market_deliveries"));
    }

    @Test
    void lostDeliveryCommitAcknowledgementReplaysOnePersistedOutcome() throws Exception {
        UUID listing = stored(donor, "uncertain-commit");
        UUID request = request(owner, listing, recipient).id();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        DataSource uncertain = wrap((connection, method, args) -> {
            Object value = invoke(connection, method, args);
            if (method.getName().equals("commit") && failOnce.getAndSet(false)) throw new SQLException("lost commit reply");
            return value;
        });
        assertThrows(CompletionException.class, () -> await(service(uncertain, true, 128).approveWithdrawal(officer, request)));
        assertEquals(Status.DELIVERED, await(storage.approveWithdrawal(officer, request)).status());
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'STORAGE_WITHDRAWN'"));
    }

    @Test
    void unresolvedDepositBlocksDissolutionBeforeAnyOtherStoredStackIsReturned() throws Exception {
        stored(donor, "already-stored");
        UUID unresolved = prepared(recipient, "not-yet-removed");
        UUID dissolution = approvedDissolution();
        assertEquals(CommunityLifecycleResult.Status.STORAGE_PENDING, await(lifecycle.execute(owner, dissolution)).status());
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals(4, count("rpg_community_members"));
        assertEquals(MarketOperationResult.Status.SUCCESS, await(storage.activate(unresolved, recipient)).status());
        assertEquals(CommunityLifecycleResult.Status.EXECUTED, await(lifecycle.execute(owner, dissolution)).status());
        assertEquals(2, count("rpg_market_deliveries"));
    }

    @Test
    void quarantinedCustodyCannotBeActivatedOrAutomaticallyRefundedByDissolution() throws Exception {
        UUID listing = prepared(donor, "ambiguous-custody");
        assertEquals(MarketOperationResult.Status.SUCCESS, await(storage.quarantinePrepared(listing, donor)).status());
        assertEquals(MarketOperationResult.Status.SUCCESS, await(storage.quarantinePrepared(listing, donor)).status());
        assertEquals(MarketOperationResult.Status.NOT_ACTIVE, await(storage.activate(listing, donor)).status());
        assertTrue(await(storage.preparedDeposits(donor)).isEmpty());
        UUID dissolution = approvedDissolution();
        assertEquals(CommunityLifecycleResult.Status.STORAGE_PENDING, await(lifecycle.execute(owner, dissolution)).status());
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'STORAGE_QUARANTINED'"));
    }

    @Test
    void dissolutionReturnsOnlyRemainingStocksToOriginalDonorsAndVoidsUnfinishedWithdrawalConsent() throws Exception {
        UUID first = stored(donor, "donor-one");
        UUID second = stored(recipient, "donor-two");
        request(owner, first, officer);
        UUID dissolution = approvedDissolution();
        assertEquals(CommunityLifecycleResult.Status.EXECUTED, await(lifecycle.execute(owner, dissolution)).status());
        assertEquals(CommunityLifecycleResult.Status.EXECUTED, await(lifecycle.execute(owner, dissolution)).status());
        assertEquals(0, count("rpg_community_members"));
        assertEquals("CANCELLED", text("SELECT state FROM rpg_community_storage_requests"));
        assertEquals(1, await(market.pendingDeliveries(donor, 10)).size());
        assertEquals("community-return:" + first, await(market.pendingDeliveries(donor, 10)).getFirst().source());
        assertEquals("community-return:" + second, await(market.pendingDeliveries(recipient, 10)).getFirst().source());
        assertEquals(2, count("rpg_market_deliveries"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_market_listings WHERE state = 'CANCELLED'"));
        assertEquals("4000.00", text("SELECT SUM(balance) FROM rpg_accounts"));
    }

    @Test
    void failedSecondDissolutionReturnRollsBackEveryReturnAndTheCommunityTombstone() throws Exception {
        stored(donor, "first-return"); stored(recipient, "second-return");
        UUID dissolution = approvedDissolution();
        AtomicInteger mails = new AtomicInteger();
        DataSource failing = wrap((connection, method, args) -> {
            if (method.getName().equals("prepareStatement") && ((String) args[0]).contains("INSERT INTO rpg_market_deliveries")
                    && mails.incrementAndGet() == 2) throw new SQLException("second return failed");
            return invoke(connection, method, args);
        });
        var failingLifecycle = new JdbcCommunityLifecycleService(failing, executor, true,
                CommunityLifecyclePolicy.defaults(), clock);
        assertThrows(CompletionException.class, () -> await(failingLifecycle.execute(owner, dissolution)));
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals(4, count("rpg_community_members"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_market_listings WHERE state = 'ACTIVE'"));
        assertNull(text("SELECT CAST(dissolved_at AS VARCHAR) FROM rpg_communities"));
        assertEquals(CommunityLifecycleResult.Status.EXECUTED, await(lifecycle.execute(owner, dissolution)).status());
        assertEquals(2, count("rpg_market_deliveries"));
    }

    @Test
    void vetoReleasesTheApprovalSlotWithoutReleasingTheStoredItem() throws Exception {
        UUID listing = stored(donor, "vetoed");
        UUID request = request(owner, listing, recipient).id();
        assertEquals(Status.CANCELLED, await(storage.cancelWithdrawal(officer, request)).status());
        assertEquals(Status.CANCELLED, await(storage.approveWithdrawal(officer, request)).status());
        assertEquals(0, count("rpg_market_deliveries"));
        assertEquals("ACTIVE", text("SELECT state FROM rpg_market_listings"));
        assertEquals(Status.SUCCESS, await(storage.requestWithdrawal(owner, listing, officer, "after-veto")).status());
    }

    private JdbcCommunityStorageService service(DataSource source, boolean enabled, int capacity) {
        return new JdbcCommunityStorageService(source, executor, enabled, capacity, null);
    }

    private UUID prepared(UUID player, String payload) {
        UUID id = UUID.randomUUID();
        assertEquals(MarketOperationResult.Status.SUCCESS, await(storage.prepare(id, player, payload, stone)).status());
        return id;
    }

    private UUID stored(UUID player, String payload) {
        UUID id = prepared(player, payload);
        assertEquals(MarketOperationResult.Status.SUCCESS, await(storage.activate(id, player)).status());
        return id;
    }

    private CommunityStorageRequest request(UUID actor, UUID listing, UUID receiver) {
        var result = await(storage.requestWithdrawal(actor, listing, receiver, "request:" + UUID.randomUUID()));
        assertEquals(Status.SUCCESS, result.status());
        return result.request().orElseThrow();
    }

    private UUID approvedDissolution() {
        UUID id = await(lifecycle.propose(owner, CommunityLifecycleAction.DISSOLUTION,
                "dissolve:" + UUID.randomUUID())).proposal().orElseThrow().id();
        assertEquals(CommunityLifecycleResult.Status.SUCCESS, await(lifecycle.approve(officer, id)).status());
        clock.advance(Duration.ofHours(72));
        return id;
    }

    private long count(String table) throws Exception { return scalar("SELECT COUNT(*) FROM " + table); }
    private long scalar(String query) throws Exception { return Long.parseLong(text(query)); }
    private String text(String query) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             var row = statement.executeQuery(query)) {
            assertTrue(row.next()); return row.getString(1);
        }
    }
    private static <T> T await(CompletionStage<T> result) { return result.toCompletableFuture().join(); }

    private DataSource wrap(ConnectionCall interceptor) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (self, method, args) -> {
                    Object value = invoke(dataSource, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, operation, inputs) -> interceptor.run(connection, operation, inputs));
                });
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    @FunctionalInterface private interface ConnectionCall { Object run(Connection connection, Method method, Object[] args) throws Throwable; }
    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
