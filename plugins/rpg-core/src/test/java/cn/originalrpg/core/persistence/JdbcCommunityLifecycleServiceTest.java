package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommunityLifecycleAction;
import cn.originalrpg.api.model.CommunityLifecyclePolicy;
import cn.originalrpg.api.model.CommunityLifecycleProposal;
import cn.originalrpg.api.model.CommunityLifecycleResult;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityContributionResult;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.originalrpg.api.model.CommunityLifecycleResult.Status;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL transactions exercise signed intent, timing, competing commands and interrupted commits. */
final class JdbcCommunityLifecycleServiceTest {
    private final UUID community = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID officer = UUID.randomUUID();
    private final UUID treasurer = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T12:00:00Z"));
    private CommunityDatabaseFixture fixture;
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcCommunityLifecycleService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new CommunityDatabaseFixture();
        dataSource = fixture.dataSource;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        service = service(dataSource, true);
        try (Connection connection = dataSource.getConnection()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO rpg_communities (id,name,normalized_name,tag,normalized_tag,owner_uuid,capacity)
                    VALUES (?, 'Wayfarers', 'wayfarers', 'WAY', 'way', ?, 20)
                    """)) {
                insert.setString(1, community.toString());
                insert.setString(2, owner.toString());
                insert.executeUpdate();
            }
            for (var entry : java.util.Map.of(owner, "OWNER", officer, "OFFICER", treasurer, "TREASURER", member, "MEMBER").entrySet()) {
                try (var insert = connection.prepareStatement("INSERT INTO rpg_community_members "
                        + "(community_id,player_uuid,role,joined_at,last_authenticated_at) VALUES (?,?,?,?,?)")) {
                    insert.setString(1, community.toString());
                    insert.setString(2, entry.getKey().toString());
                    insert.setString(3, entry.getValue());
                    insert.setTimestamp(4, Timestamp.from(clock.instant().minus(Duration.ofDays(60))));
                    insert.setTimestamp(5, Timestamp.from(clock.instant().minus(Duration.ofDays(31))));
                    insert.executeUpdate();
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
    void successionNeedsDistinctSignaturesAndSurvivesRestartUntilTheExactDeadline() throws Exception {
        var proposal = propose(officer, CommunityLifecycleAction.SUCCESSION);
        assertEquals(1, proposal.approvals().size());
        assertEquals(Status.ALREADY_APPROVED, await(service.approve(officer, proposal.id())).status());
        assertEquals(Status.APPROVALS_REQUIRED, await(service.execute(officer, proposal.id())).status());
        var second = await(service.approve(treasurer, proposal.id()));
        assertEquals(CommunityLifecycleProposal.State.COOLING, second.proposal().orElseThrow().state());
        assertEquals(clock.instant().plus(Duration.ofHours(72)), second.proposal().orElseThrow().executeAfter().orElseThrow());
        clock.advance(Duration.ofHours(72).minusSeconds(1));
        assertEquals(Status.COOLING, await(service.execute(officer, proposal.id())).status());
        service = service(dataSource, true);
        assertEquals(Status.COOLING, await(service.execute(officer, proposal.id())).status());
        clock.advance(Duration.ofSeconds(1));
        assertEquals(Status.EXECUTED, await(service.execute(officer, proposal.id())).status());
        assertEquals(officer.toString(), text("SELECT owner_uuid FROM rpg_communities"));
        assertEquals("OFFICER", text("SELECT role FROM rpg_community_members WHERE player_uuid = '" + owner + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_members WHERE role = 'OWNER'"));
        assertEquals(Status.EXECUTED, await(service.execute(treasurer, proposal.id())).status());
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'EXECUTED'"));
    }

    @Test
    void recentOwnerActivityAndRecentCandidateMembershipBlockProposals() throws Exception {
        updateTime(owner, "last_authenticated_at", clock.instant());
        assertEquals(Status.OWNER_ACTIVE, await(service.propose(officer, CommunityLifecycleAction.SUCCESSION, "active-owner")).status());
        updateTime(owner, "last_authenticated_at", clock.instant().minus(Duration.ofDays(31)));
        updateTime(officer, "joined_at", clock.instant().minus(Duration.ofDays(6)));
        assertEquals(Status.MEMBERSHIP_TOO_RECENT, await(service.propose(officer, CommunityLifecycleAction.SUCCESSION, "young-candidate")).status());
        assertEquals(Status.NOT_ALLOWED, await(service.propose(member, CommunityLifecycleAction.SUCCESSION, "member-candidate")).status());
        assertEquals(Status.NOT_ALLOWED, await(service.propose(officer, CommunityLifecycleAction.DISSOLUTION, "not-owner")).status());
        assertEquals(0, count("SELECT COUNT(*) FROM rpg_community_lifecycle_proposals"));
    }

    @Test
    void authenticatedOwnerReturnCancelsAnAlreadySignedSuccession() throws Exception {
        UUID id = approvedSuccession();
        await(service.recordAuthenticatedActivity(owner));
        clock.advance(Duration.ofDays(40));
        assertEquals(Status.CANCELLED, await(service.execute(officer, id)).status());
        assertEquals(owner.toString(), text("SELECT owner_uuid FROM rpg_communities"));
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'OWNER_RETURNED'"));
        await(service.recordAuthenticatedActivity(owner));
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'OWNER_RETURNED'"));
    }

    @Test
    void oneGovernorCannotExecuteDissolutionEvenAfterWaiting() throws Exception {
        sql("UPDATE rpg_community_members SET role = 'MEMBER' WHERE role <> 'OWNER'");
        UUID id = propose(owner, CommunityLifecycleAction.DISSOLUTION).id();
        assertEquals(Status.ALREADY_APPROVED, await(service.approve(owner, id)).status());
        assertEquals(Status.NOT_ALLOWED, await(service.approve(member, id)).status());
        clock.advance(Duration.ofDays(100));
        assertEquals(Status.APPROVALS_REQUIRED, await(service.execute(owner, id)).status());
        assertNull(text("SELECT CAST(dissolved_at AS VARCHAR) FROM rpg_communities"));
    }

    @Test
    void dissolutionDetachesMembershipAndInvitesButRetainsIdentityContributionsAndBalances() throws Exception {
        UUID outsider = UUID.randomUUID();
        sql("INSERT INTO rpg_community_invites (community_id,inviter_uuid,target_uuid,expires_at) VALUES ('"
                + community + "','" + owner + "','" + outsider + "', TIMESTAMP '2099-01-01 00:00:00')");
        sql("INSERT INTO rpg_community_contributions (id,idempotency_key,community_id,player_uuid,period_key,amount) VALUES ('"
                + UUID.randomUUID() + "','old-donation','" + community + "','" + owner + "','2026-W44',500)");
        sql("INSERT INTO rpg_accounts (player_uuid,balance) VALUES ('" + owner + "',1000)");
        UUID id = propose(owner, CommunityLifecycleAction.DISSOLUTION).id();
        await(service.approve(officer, id));
        clock.advance(Duration.ofHours(72));
        assertEquals(Status.EXECUTED, await(service.execute(owner, id)).status());
        assertEquals(0, count("SELECT COUNT(*) FROM rpg_community_members"));
        assertEquals(0, count("SELECT COUNT(*) FROM rpg_community_invites"));
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_contributions"));
        assertEquals("Wayfarers", text("SELECT name FROM rpg_communities"));
        assertEquals("1000.00", text("SELECT balance FROM rpg_accounts"));
        assertFalse(await(service.history(community, 12)).isEmpty());
        assertTrue(await(service.status(owner)).isEmpty());
        var ordinary = new JdbcCommunityService(dataSource, executor);
        assertTrue(await(ordinary.browse("2026-W44", BigDecimal.TEN, 10)).isEmpty());
        assertEquals(CommunityOperationResult.Status.NOT_MEMBER,
                await(ordinary.invite(owner, outsider, 3, 24, "2026-W44", BigDecimal.TEN)).status());
        assertEquals(CommunityOperationResult.Status.INVITE_MISSING,
                await(ordinary.accept(outsider, "WAY", "2026-W44", BigDecimal.TEN)).status());
        assertEquals(CommunityContributionResult.Status.NOT_MEMBER, await(ordinary.contributeGold(owner,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO,
                "2026-W44", "after-dissolution")).status());
    }

    @Test
    void ordinaryOwnershipTransferInvalidatesAnOldSuccession() throws Exception {
        UUID id = approvedSuccession();
        var ordinary = new JdbcCommunityService(dataSource, executor);
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                await(ordinary.transfer(owner, member, "2026-W44", BigDecimal.TEN)).status());
        clock.advance(Duration.ofHours(72));
        assertEquals(Status.CONDITIONS_CHANGED, await(service.execute(officer, id)).status());
        assertEquals(member.toString(), text("SELECT owner_uuid FROM rpg_communities"));
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_members WHERE role = 'OWNER'"));
    }

    @Test
    void demotedApproverCannotLeaveAValidOldSignature() throws Exception {
        UUID id = approvedSuccession();
        sql("UPDATE rpg_community_members SET role = 'MEMBER' WHERE player_uuid = '" + treasurer + "'");
        clock.advance(Duration.ofHours(72));
        assertEquals(Status.CONDITIONS_CHANGED, await(service.execute(officer, id)).status());
        assertEquals(owner.toString(), text("SELECT owner_uuid FROM rpg_communities"));
    }

    @Test
    void leavingAndRejoiningDoesNotResurrectAnOldSignature() throws Exception {
        UUID id = approvedSuccession();
        updateTime(treasurer, "joined_at", clock.instant().plusSeconds(1));
        clock.advance(Duration.ofDays(10));
        assertEquals(Status.CONDITIONS_CHANGED, await(service.execute(officer, id)).status());
    }

    @Test
    void concurrentProposalsUseOneCommunityLockAndOneActiveIntent() throws Exception {
        List<CompletableFuture<CommunityLifecycleResult>> attempts = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            attempts.add(service.propose(index % 2 == 0 ? officer : treasurer, CommunityLifecycleAction.SUCCESSION,
                    "competing-" + index).toCompletableFuture());
        }
        CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, attempts.stream().map(CompletableFuture::join).filter(result -> result.status() == Status.SUCCESS).count());
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_lifecycle_proposals"));
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_approvals"));
    }

    @Test
    void concurrentApprovalsAndExecutionsDoNotDuplicateSignaturesOrOwnershipChanges() throws Exception {
        UUID id = propose(officer, CommunityLifecycleAction.SUCCESSION).id();
        List<CompletableFuture<CommunityLifecycleResult>> approvals = new ArrayList<>();
        for (int index = 0; index < 12; index++) approvals.add(service.approve(treasurer, id).toCompletableFuture());
        CompletableFuture.allOf(approvals.toArray(CompletableFuture[]::new)).join();
        assertEquals(2, count("SELECT COUNT(*) FROM rpg_community_approvals"));
        clock.advance(Duration.ofHours(72));
        List<CompletableFuture<CommunityLifecycleResult>> executions = new ArrayList<>();
        for (int index = 0; index < 12; index++) executions.add(service.execute(officer, id).toCompletableFuture());
        CompletableFuture.allOf(executions.toArray(CompletableFuture[]::new)).join();
        assertTrue(executions.stream().allMatch(result -> result.join().status() == Status.EXECUTED));
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'EXECUTED'"));
    }

    @Test
    void failedFinalizationRollsBackTheTombstoneAndMemberRemoval() throws Exception {
        UUID id = propose(owner, CommunityLifecycleAction.DISSOLUTION).id();
        await(service.approve(officer, id));
        clock.advance(Duration.ofHours(72));
        DataSource failing = wrap((connection, method, args) -> {
            if (method.getName().equals("prepareStatement") && ((String) args[0]).contains("SET state = ?, active_community_id")) {
                throw new SQLException("injected failure after member deletion");
            }
            return invoke(connection, method, args);
        });
        assertThrows(CompletionException.class, () -> await(service(failing, true).execute(owner, id)));
        assertNull(text("SELECT CAST(dissolved_at AS VARCHAR) FROM rpg_communities"));
        assertEquals(4, count("SELECT COUNT(*) FROM rpg_community_members"));
        assertEquals(0, count("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'EXECUTED'"));
        assertEquals(Status.EXECUTED, await(service.execute(owner, id)).status());
    }

    @Test
    void lostCommitAcknowledgementReplaysThePersistedOutcome() throws Exception {
        UUID id = approvedSuccession();
        clock.advance(Duration.ofHours(72));
        AtomicBoolean firstCommit = new AtomicBoolean(true);
        DataSource uncertain = wrap((connection, method, args) -> {
            Object result = invoke(connection, method, args);
            if (method.getName().equals("commit") && firstCommit.getAndSet(false)) {
                throw new SQLException("connection lost after committed ownership change");
            }
            return result;
        });
        assertThrows(CompletionException.class, () -> await(service(uncertain, true).execute(officer, id)));
        assertEquals(Status.EXECUTED, await(service.execute(officer, id)).status());
        assertEquals(1, count("SELECT COUNT(*) FROM rpg_community_lifecycle_audit WHERE transition = 'EXECUTED'"));
    }

    @Test
    void disabledGovernanceStillMaintainsAuthenticatedPresenceWithoutAcceptingDecisions() throws Exception {
        var disabled = service(dataSource, false);
        assertEquals(Status.DISABLED, await(disabled.propose(owner, CommunityLifecycleAction.DISSOLUTION, "disabled")).status());
        await(disabled.recordAuthenticatedActivity(owner));
        assertEquals(Status.OWNER_ACTIVE,
                await(service.propose(officer, CommunityLifecycleAction.SUCCESSION, "presence-survives-gate")).status());
        assertEquals(0, count("SELECT COUNT(*) FROM rpg_community_lifecycle_proposals"));
    }

    @Test
    void governorVetoRemainsDurableAndReleasesTheSingleProposalSlot() {
        UUID id = approvedSuccession();
        assertEquals(Status.CANCELLED, await(service.cancel(owner, id)).status());
        clock.advance(Duration.ofDays(40));
        assertEquals(Status.CANCELLED, await(service.execute(officer, id)).status());
        assertEquals(Status.SUCCESS, await(service.propose(owner, CommunityLifecycleAction.DISSOLUTION, "next-decision")).status());
    }

    private JdbcCommunityLifecycleService service(DataSource source, boolean enabled) {
        return new JdbcCommunityLifecycleService(source, executor, enabled, CommunityLifecyclePolicy.defaults(), clock);
    }

    private CommunityLifecycleProposal propose(UUID actor, CommunityLifecycleAction action) {
        var result = await(service.propose(actor, action, "proposal:" + UUID.randomUUID()));
        assertEquals(Status.SUCCESS, result.status());
        return result.proposal().orElseThrow();
    }

    private UUID approvedSuccession() {
        UUID id = propose(officer, CommunityLifecycleAction.SUCCESSION).id();
        assertEquals(Status.SUCCESS, await(service.approve(treasurer, id)).status());
        return id;
    }

    private void updateTime(UUID player, String column, Instant time) throws Exception {
        try (Connection connection = dataSource.getConnection(); var update = connection.prepareStatement(
                "UPDATE rpg_community_members SET " + column + " = ? WHERE player_uuid = ?")) {
            update.setTimestamp(1, Timestamp.from(time));
            update.setString(2, player.toString());
            update.executeUpdate();
            connection.commit();
        }
    }

    private String text(String query) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private long count(String query) throws Exception { return Long.parseLong(text(query)); }

    private void sql(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
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

    @FunctionalInterface
    private interface ConnectionCall { Object run(Connection connection, Method method, Object[] args) throws Throwable; }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
