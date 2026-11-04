package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.service.DungeonEntryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static cn.originalrpg.core.persistence.JdbcPlayerSessionLifecycleTest.await;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL-only lock semantics: H2's exclusive node lock is not equivalent evidence. */
@EnabledIfEnvironmentVariable(named = "SIVITA_NETWORK_TEST_POSTGRES_URL", matches = ".+")
final class PostgresSessionLockOrderTest {
    @Test
    void simultaneousPartyReservationsKeepSharedNodeLocksWhileHeartbeatWaitsWithoutLockUpgradeDeadlock() throws Exception {
        try (NetworkDatabaseFixture fixture = new NetworkDatabaseFixture();
             var workers = Executors.newFixedThreadPool(4)) {
            try (Connection connection = fixture.dataSource.getConnection()) {
                assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            }
            NetworkTestClock clock = new NetworkTestClock();
            JdbcNodeLease node = new JdbcNodeLease(fixture.dataSource, workers, "party-node", UUID.randomUUID(),
                    Duration.ofMinutes(5), clock);
            assertTrue(node.acquire());
            JdbcSessionLease sessions = new JdbcSessionLease(fixture.dataSource, workers,
                    node.nodeId(), node.processToken(), Duration.ofSeconds(60), clock);
            JdbcProfileService profiles = new JdbcProfileService(fixture.dataSource, workers, 100, 2);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            await(profiles.chooseArchetype(first, Archetype.WARRIOR));
            await(profiles.chooseArchetype(second, Archetype.MAGE));
            await(sessions.acquire(first, UUID.randomUUID()));
            await(sessions.acquire(second, UUID.randomUUID()));

            CountDownLatch bothSessionRowsLocked = new CountDownLatch(2);
            CountDownLatch continueReservations = new CountDownLatch(1);
            DataSource controlled = pauseAfterFirstSessionLock(fixture.dataSource,
                    bothSessionRowsLocked, continueReservations);
            JdbcDungeonEntryService provider = new JdbcDungeonEntryService(new SessionFencedDataSource(controlled),
                    workers, profiles, 100, 2, node);
            DungeonEntryService entries = SessionGuardedService.wrap(DungeonEntryService.class, provider, sessions);
            var firstEntry = entries.reserve(UUID.randomUUID(), first, 25).toCompletableFuture();
            var secondEntry = entries.reserve(UUID.randomUUID(), second, 25).toCompletableFuture();
            assertTrue(bothSessionRowsLocked.await(5, TimeUnit.SECONDS),
                    "Both different players must be able to hold a shared node lock concurrently");
            var heartbeat = node.renewAsync().toCompletableFuture();
            try {
                assertThrows(TimeoutException.class, () -> heartbeat.get(150, TimeUnit.MILLISECONDS),
                        "The heartbeat must respect live gameplay node-row locks");
            } finally { continueReservations.countDown(); }
            assertEquals(DungeonEntryService.ReservationStatus.SUCCESS, firstEntry.get(10, TimeUnit.SECONDS));
            assertEquals(DungeonEntryService.ReservationStatus.SUCCESS, secondEntry.get(10, TimeUnit.SECONDS));
            assertTrue(heartbeat.get(10, TimeUnit.SECONDS));
            assertEquals(2, fixture.count("rpg_dungeon_energy_reservations"));
            assertEquals(75, await(profiles.get(first)).energy());
            assertEquals(75, await(profiles.get(second)).energy());
        }
    }

    private static DataSource pauseAfterFirstSessionLock(DataSource delegate, CountDownLatch locked, CountDownLatch release) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(delegate, args);
                        if (!(value instanceof Connection raw)) return value;
                        boolean[] paused = {false};
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                (connection, operation, parameters) -> {
                                    try {
                                        Object response = operation.invoke(raw, parameters);
                                        if (!operation.getName().equals("prepareStatement") || parameters == null
                                                || !((String) parameters[0]).contains("FROM rpg_session_leases")
                                                || !((String) parameters[0]).contains("FOR UPDATE") || paused[0]) return response;
                                        PreparedStatement statement = (PreparedStatement) response;
                                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                                new Class<?>[]{PreparedStatement.class}, (query, call, values) -> {
                                                    try {
                                                        Object result = call.invoke(statement, values);
                                                        if (call.getName().equals("executeQuery") && !paused[0]) {
                                                            paused[0] = true;
                                                            locked.countDown();
                                                            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
                                                        }
                                                        return result;
                                                    } catch (InvocationTargetException error) { throw error.getCause(); }
                                                });
                                    } catch (InvocationTargetException error) { throw error.getCause(); }
                                });
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }
}
