package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.PlayerSessionLease;
import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.ProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.originalrpg.core.persistence.JdbcPlayerSessionLifecycleTest.await;
import static org.junit.jupiter.api.Assertions.*;

/** Fences real profile mutations, not an isolated token comparison or source-string assertion. */
final class SessionWriteFencingTest {
    private NetworkDatabaseFixture fixture;
    private NetworkTestClock clock;
    private JdbcNodeLease node;
    private JdbcSessionLease sessions;
    private JdbcProfileService trustedProfiles;
    private UUID player;

    @BeforeEach void setUp() throws Exception {
        fixture = new NetworkDatabaseFixture();
        clock = new NetworkTestClock();
        node = new JdbcNodeLease(fixture.dataSource, Runnable::run, "actor-node", UUID.randomUUID(),
                Duration.ofMinutes(5), clock);
        assertTrue(node.acquire());
        sessions = new JdbcSessionLease(fixture.dataSource, Runnable::run, node.nodeId(), node.processToken(),
                Duration.ofSeconds(30), clock);
        player = UUID.randomUUID();
        trustedProfiles = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
        await(trustedProfiles.chooseArchetype(player, Archetype.WARRIOR));
    }

    @AfterEach void tearDown() throws Exception {
        assertNull(SessionWriteContext.current(), "A completed test must not leak caller authority");
        fixture.close();
    }

    @Test
    void queuedOldConnectionCannotBorrowAReplacementLoginTokenOnTheSameNode() {
        PlayerSessionLease old = acquire();
        ManualExecutor worker = new ManualExecutor();
        ProfileService facade = facade(fixture.dataSource, worker);
        var pending = facade.spendEnergy(player, 25);
        assertEquals(1, worker.size());
        assertNull(SessionWriteContext.current());
        await(sessions.release(old));
        PlayerSessionLease replacement = acquire();
        assertNotEquals(old.sessionToken(), replacement.sessionToken());
        worker.runNext();
        assertThrows(CompletionException.class, () -> await(pending));
        assertEquals(100, energy());
        var fresh = facade.spendEnergy(player, 25);
        worker.runNext();
        assertTrue(await(fresh));
        assertEquals(75, energy());
    }

    @Test
    void anEnabledAuthorityWithNoAdmittedActorFailsBeforeQueuingButOfflineRewardsStillWork() {
        ManualExecutor worker = new ManualExecutor();
        ProfileService facade = facade(fixture.dataSource, worker);
        assertThrows(CompletionException.class, () -> await(facade.spendEnergy(player, 25)));
        assertEquals(0, worker.size());
        UUID offline = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        var reward = facade.grantExperienceOnce(offline, grant, 20);
        worker.runNext();
        assertEquals(20, await(reward).experience());
        var replay = facade.grantExperienceOnce(offline, grant, 20);
        worker.runNext();
        assertEquals(20, await(replay).experience());
        assertEquals(1, fixtureCount("rpg_experience_grants"));
    }

    @Test
    void disablingSessionAuthorityReturnsTheExactOriginalServiceAndPreservesStandaloneBehavior() {
        JdbcProfileService provider = new JdbcProfileService(fixture.dataSource, Runnable::run, 100, 2);
        ProfileService original = SessionGuardedService.wrap(ProfileService.class, provider, null);
        assertSame(provider, original);
        assertTrue(await(original.spendEnergy(player, 10)));
        assertEquals(90, energy());
    }

    @Test
    void explicitDelayedInvocationRejectsItsOriginalLeaseAfterReconnect() {
        PlayerSessionLease original = acquire();
        await(sessions.release(original));
        acquire();
        AtomicBoolean invoked = new AtomicBoolean();
        assertThrows(CompletionException.class, () -> await(sessions.fenced(original, () -> {
            invoked.set(true);
            return CompletableFuture.completedFuture(true);
        })));
        assertFalse(invoked.get());
    }

    @Test
    void successfulRenewalExtendsDatabaseAuthorityWithoutChangingAnAlreadyCapturedToken() {
        PlayerSessionLease original = acquire();
        ManualExecutor worker = new ManualExecutor();
        var spend = facade(fixture.dataSource, worker).spendEnergy(player, 10);
        clock.advance(Duration.ofSeconds(25));
        assertTrue(await(sessions.renew(original)).isPresent());
        clock.advance(Duration.ofSeconds(10));
        assertTrue(clock.instant().isAfter(original.expiresAt()));
        worker.runNext();
        assertTrue(await(spend));
        assertEquals(90, energy());
    }

    @Test
    void expirationBetweenTheRealProfileUpdateAndCommitRollsBackTheDebit() {
        acquire();
        AtomicBoolean expireOnce = new AtomicBoolean(true);
        DataSource delayed = afterProfileUpdate(fixture.dataSource, () -> {
            if (expireOnce.compareAndSet(true, false)) clock.advance(Duration.ofSeconds(31));
        });
        ProfileService facade = facade(delayed, Runnable::run);
        assertThrows(CompletionException.class, () -> await(facade.spendEnergy(player, 25)));
        assertEquals(100, energy());
    }

    @Test
    void blockedRenewCannotReviveAnExpiredTransactionEvenWhileItsRowLocksPreventTakeover() throws Exception {
        PlayerSessionLease original = acquire();
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        DataSource paused = afterProfileUpdate(fixture.dataSource, () -> {
            updated.countDown();
            try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            ProfileService facade = facade(paused, workers);
            var spend = facade.spendEnergy(player, 25).toCompletableFuture();
            assertTrue(updated.await(5, TimeUnit.SECONDS));
            var renewal = workers.submit(() -> await(sessions.renew(original)));
            clock.advance(Duration.ofSeconds(31));
            finish.countDown();
            assertThrows(Exception.class, () -> spend.get(5, TimeUnit.SECONDS));
            assertTrue(renewal.get(5, TimeUnit.SECONDS).isEmpty());
        } finally { finish.countDown(); }
        assertEquals(100, energy());
    }

    @Test
    void defaultInterfaceOverloadCapturesAuthorityBeforeItsImplementationQueuesSql() {
        PlayerSessionLease original = acquire();
        ManualExecutor worker = new ManualExecutor();
        JdbcClassBranchService provider = new JdbcClassBranchService(
                new SessionFencedDataSource(fixture.dataSource), worker);
        ClassBranchService facade = SessionGuardedService.wrap(ClassBranchService.class, provider, sessions);
        var choice = facade.choose(player, Archetype.WARRIOR, 1, "vanguard");
        assertEquals(1, worker.size());
        await(sessions.release(original));
        acquire();
        worker.runNext();
        assertThrows(CompletionException.class, () -> await(choice));
        assertEquals(0, fixtureCount("rpg_class_branches"));
    }

    @Test
    void capturedContextIsClearedAfterFailureRejectionAndUnrelatedWorkerWork() throws Exception {
        PlayerSessionLease lease = acquire();
        ManualExecutor worker = new ManualExecutor();
        var failure = sessions.fenced(lease, () -> AsyncJdbc.supply(worker, () -> {
            assertTrue(SessionWriteContext.current().actor().sameAuthority(lease));
            throw new IllegalStateException("SQL failed");
        }));
        assertNull(SessionWriteContext.current());
        worker.runNext();
        assertThrows(CompletionException.class, () -> await(failure));
        assertNull(SessionWriteContext.current());
        var unrelated = AsyncJdbc.supply(worker, SessionWriteContext::current);
        worker.runNext();
        assertNull(await(unrelated));
        var rejected = sessions.fenced(lease, () -> AsyncJdbc.supply(command -> {
            throw new RejectedExecutionException("bounded queue full");
        }, () -> true));
        assertThrows(CompletionException.class, () -> await(rejected));
        assertNull(SessionWriteContext.current());
        try (var ordinaryWorker = Executors.newSingleThreadExecutor()) {
            assertNull(await(sessions.fenced(lease, () -> {
                try { return CompletableFuture.completedFuture(ordinaryWorker.submit(SessionWriteContext::current).get()); }
                catch (Exception error) { return CompletableFuture.failedFuture(error); }
            })), "A new ordinary worker cannot inherit a player's ThreadLocal token");
        }
    }

    private ProfileService facade(DataSource raw, Executor worker) {
        JdbcProfileService provider = new JdbcProfileService(new SessionFencedDataSource(raw), worker, 100, 2);
        return SessionGuardedService.wrap(ProfileService.class, provider, sessions);
    }

    private PlayerSessionLease acquire() { return await(sessions.acquire(player, UUID.randomUUID())).orElseThrow(); }

    private int energy() {
        trustedProfiles.evict(player);
        return await(trustedProfiles.get(player)).energy();
    }

    private long fixtureCount(String table) {
        try { return fixture.count(table); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static DataSource afterProfileUpdate(DataSource delegate, Runnable callback) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (source, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (!(result instanceof Connection raw)) return result;
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                (connection, operation, values) -> {
                                    try {
                                        Object response = operation.invoke(raw, values);
                                        if (!operation.getName().equals("prepareStatement") || values == null
                                                || !((String) values[0]).stripLeading().startsWith("UPDATE rpg_profiles")) return response;
                                        PreparedStatement statement = (PreparedStatement) response;
                                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                                new Class<?>[]{PreparedStatement.class}, (proxy, call, parameters) -> {
                                                    try {
                                                        Object answer = call.invoke(statement, parameters);
                                                        if (call.getName().equals("executeUpdate")) callback.run();
                                                        return answer;
                                                    } catch (InvocationTargetException error) { throw error.getCause(); }
                                                });
                                    } catch (InvocationTargetException error) { throw error.getCause(); }
                                });
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable task) { queue.addLast(task); }
        int size() { return queue.size(); }
        void runNext() { queue.removeFirst().run(); }
    }
}
