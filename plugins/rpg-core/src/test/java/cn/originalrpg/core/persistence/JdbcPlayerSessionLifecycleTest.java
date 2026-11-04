package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PlayerSessionLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Owns actual session rows, including ambiguous commit and handoff recovery boundaries. */
final class JdbcPlayerSessionLifecycleTest {
    private NetworkDatabaseFixture fixture;
    private NetworkTestClock clock;
    private JdbcNodeLease leftNode;
    private JdbcNodeLease rightNode;
    private JdbcSessionLease left;
    private JdbcSessionLease right;
    private UUID player;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new NetworkDatabaseFixture();
        clock = new NetworkTestClock();
        leftNode = node("left");
        rightNode = node("right");
        left = sessions(leftNode, fixture.dataSource);
        right = sessions(rightNode, fixture.dataSource);
        player = UUID.randomUUID();
    }

    @AfterEach void tearDown() throws Exception { fixture.close(); }

    @Test
    void acquireReplayRenewReleaseAndReconnectUseMonotonicFences() {
        UUID connection = UUID.randomUUID();
        PlayerSessionLease original = await(left.acquire(player, connection)).orElseThrow();
        assertEquals(original, await(left.acquire(player, connection)).orElseThrow());
        assertTrue(await(right.acquire(player, UUID.randomUUID())).isEmpty());
        assertTrue(await(left.acquire(player, UUID.randomUUID())).isEmpty());
        clock.advance(Duration.ofSeconds(5));
        PlayerSessionLease renewed = await(left.renew(original)).orElseThrow();
        assertTrue(renewed.expiresAt().isAfter(original.expiresAt()));
        assertTrue(renewed.sameAuthority(original));
        assertTrue(await(left.release(original)));
        assertTrue(await(left.release(original)), "Lost release response is an idempotent replay");
        assertTrue(await(left.acquire(player, connection)).isEmpty(), "Released connection token cannot revive");
        PlayerSessionLease replacement = await(right.acquire(player, UUID.randomUUID())).orElseThrow();
        assertTrue(replacement.fence() > original.fence());
        assertTrue(await(left.renew(original)).isEmpty());
        assertFalse(await(left.release(original)), "An old quit cannot release a replacement connection");
        assertEquals(replacement, right.current(player).orElseThrow());
    }

    @Test
    void expiredTokenCannotRenewOrAcquireAgainButFreshConnectionCanTakeOver() {
        PlayerSessionLease expired = await(left.acquire(player, UUID.randomUUID())).orElseThrow();
        clock.advance(Duration.ofSeconds(31));
        assertTrue(await(left.renew(expired)).isEmpty());
        assertTrue(await(left.acquire(player, expired.sessionToken())).isEmpty());
        PlayerSessionLease replacement = await(right.acquire(player, UUID.randomUUID())).orElseThrow();
        assertTrue(replacement.fence() > expired.fence());
        assertFalse(await(left.release(expired)));
    }

    @Test
    void simultaneousAcquiresAcrossNodesHaveOnlyOneWinner() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = workers.invokeAll(List.<java.util.concurrent.Callable<java.util.Optional<PlayerSessionLease>>>of(
                    () -> await(left.acquire(player, UUID.randomUUID())),
                    () -> await(right.acquire(player, UUID.randomUUID()))));
            long winners = 0;
            for (var result : results) if (result.get().isPresent()) winners++;
            assertEquals(1, winners);
        }
    }

    @Test
    void handoffQuiescesSourceAndOnlyTargetCanAcceptTheExactTicket() {
        PlayerSessionLease original = await(left.acquire(player, UUID.randomUUID())).orElseThrow();
        UUID handoffId = UUID.randomUUID();
        var ticket = await(left.prepareHandoff(original, "right", handoffId)).orElseThrow();
        assertTrue(left.current(player).isEmpty());
        assertTrue(await(left.renew(original)).isEmpty());
        assertTrue(await(right.acquire(player, UUID.randomUUID())).isEmpty(), "Handoff cannot be stolen by a normal login");
        assertTrue(await(left.acceptHandoff(ticket, UUID.randomUUID())).isEmpty());
        assertEquals(ticket, await(left.prepareHandoff(original, "right", handoffId)).orElseThrow());
        UUID targetConnection = UUID.randomUUID();
        PlayerSessionLease target = await(right.acceptHandoff(ticket, targetConnection)).orElseThrow();
        assertEquals(target, await(right.acceptHandoff(ticket, targetConnection)).orElseThrow());
        assertTrue(await(left.cancelHandoff(ticket, UUID.randomUUID())).isEmpty());
        assertFalse(await(left.release(original)));
        assertTrue(target.fence() > ticket.fence());
    }

    @Test
    void sourceCanCancelAbandonedHandoffOnlyWithAFreshConnectionToken() {
        PlayerSessionLease original = await(left.acquire(player, UUID.randomUUID())).orElseThrow();
        var ticket = await(left.prepareHandoff(original, "right", UUID.randomUUID())).orElseThrow();
        assertTrue(await(left.cancelHandoff(ticket, original.sessionToken())).isEmpty());
        clock.advance(Duration.ofSeconds(31));
        assertTrue(await(right.acceptHandoff(ticket, UUID.randomUUID())).isEmpty());
        PlayerSessionLease resumed = await(left.cancelHandoff(ticket, UUID.randomUUID())).orElseThrow();
        assertTrue(resumed.fence() > ticket.fence());
        assertTrue(await(right.acceptHandoff(ticket, UUID.randomUUID())).isEmpty());
        assertTrue(await(left.renew(original)).isEmpty());
    }

    @Test
    void replacementPaperProcessFencesAcquireRenewAndHandoff() {
        PlayerSessionLease original = await(left.acquire(player, UUID.randomUUID())).orElseThrow();
        clock.advance(Duration.ofSeconds(301));
        JdbcNodeLease replacement = node("left");
        assertNotEquals(leftNode.processToken(), replacement.processToken());
        assertThrows(CompletionException.class, () -> await(left.acquire(UUID.randomUUID(), UUID.randomUUID())));
        assertThrows(CompletionException.class, () -> await(left.renew(original)));
        assertThrows(CompletionException.class, () -> await(left.prepareHandoff(original, "right", UUID.randomUUID())));
    }

    @Test
    void committedAcquireWithLostResponseCanBeReplayedWithoutCreatingANewAuthority() {
        AtomicBoolean loseResponse = new AtomicBoolean(true);
        JdbcSessionLease unreliable = sessions(leftNode, loseOneCommitResponse(fixture.dataSource, loseResponse));
        UUID connection = UUID.randomUUID();
        assertThrows(CompletionException.class, () -> await(unreliable.acquire(player, connection)));
        PlayerSessionLease recovered = await(unreliable.acquire(player, connection)).orElseThrow();
        assertEquals(1, recovered.fence());
        assertTrue(await(right.acquire(player, UUID.randomUUID())).isEmpty());
        assertEquals(recovered, await(unreliable.acquire(player, connection)).orElseThrow());
    }

    @Test
    void nodeThatExpiresAfterSessionUpdateCannotCommitNewPlayerAuthority() throws Exception {
        DataSource delayed = afterSessionUpdate(fixture.dataSource, () -> clock.advance(Duration.ofSeconds(301)));
        JdbcSessionLease blocked = sessions(leftNode, delayed);
        assertThrows(CompletionException.class, () -> await(blocked.acquire(player, UUID.randomUUID())));
        assertEquals(0, fixture.count("rpg_session_leases"));
        assertTrue(blocked.current(player).isEmpty());
    }

    @Test
    void additiveUpgradePreservesLegacyOwnersAndWaitsForTheirLeaseToExpire() throws Exception {
        try (Connection connection = fixture.dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE rpg_session_leases");
            sql.execute("""
                    CREATE TABLE rpg_session_leases (
                        player_uuid VARCHAR(36) PRIMARY KEY, node_id VARCHAR(64) NOT NULL,
                        node_process_token VARCHAR(36), lease_expires_at TIMESTAMP NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            try (var insert = connection.prepareStatement("INSERT INTO rpg_session_leases (player_uuid,node_id,node_process_token,lease_expires_at) VALUES (?,?,?,?)")) {
                insert.setString(1, player.toString()); insert.setString(2, leftNode.nodeId());
                insert.setString(3, leftNode.processToken());
                insert.setTimestamp(4, java.sql.Timestamp.from(clock.instant().plusSeconds(20)));
                insert.executeUpdate();
            }
            connection.commit();
        }
        fixture.database.migrate();
        fixture.database.migrate();
        assertTrue(await(left.acquire(player, UUID.randomUUID())).isEmpty());
        assertEquals(leftNode.nodeId(), left.holder(player).orElseThrow().nodeId());
        clock.advance(Duration.ofSeconds(21));
        assertEquals(1, await(right.acquire(player, UUID.randomUUID())).orElseThrow().fence());
    }

    private JdbcNodeLease node(String id) {
        JdbcNodeLease lease = new JdbcNodeLease(fixture.dataSource, Runnable::run, id,
                UUID.randomUUID(), Duration.ofMinutes(5), clock);
        assertTrue(lease.acquire());
        return lease;
    }

    private JdbcSessionLease sessions(JdbcNodeLease node, DataSource dataSource) {
        return new JdbcSessionLease(dataSource, Runnable::run, node.nodeId(), node.processToken(),
                Duration.ofSeconds(30), clock);
    }

    static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }

    private static DataSource loseOneCommitResponse(DataSource delegate, AtomicBoolean fail) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (!(result instanceof Connection raw)) return result;
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                (connection, operation, values) -> {
                                    try {
                                        Object response = operation.invoke(raw, values);
                                        if (operation.getName().equals("commit") && fail.compareAndSet(true, false)) {
                                            throw new SQLException("Commit succeeded but reply was lost");
                                        }
                                        return response;
                                    } catch (InvocationTargetException error) { throw error.getCause(); }
                                });
                    } catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }

    private static DataSource afterSessionUpdate(DataSource delegate, Runnable callback) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (!(result instanceof Connection raw)) return result;
                        return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                                (connection, operation, values) -> {
                                    try {
                                        Object response = operation.invoke(raw, values);
                                        if (!operation.getName().equals("prepareStatement") || values == null
                                                || !((String) values[0]).stripLeading().startsWith("UPDATE rpg_session_leases")) return response;
                                        java.sql.PreparedStatement statement = (java.sql.PreparedStatement) response;
                                        return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                                                new Class<?>[]{java.sql.PreparedStatement.class}, (query, call, parameters) -> {
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
}
