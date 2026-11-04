package cn.originalrpg.core.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real production migrations and SQL, including restart/claim/rollback fault boundaries. */
final class TransactionalOutboxTest {
    private NetworkDatabaseFixture fixture;
    private final JdbcOutboxRegistry outbox = new JdbcOutboxRegistry();
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new NetworkDatabaseFixture();
        // Production-created rows are immediately eligible for this deterministic clock.
        clock = new MutableClock(Instant.now().plusSeconds(60));
        try (Connection connection = fixture.dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE test_outbox_effects (event_id VARCHAR(36) PRIMARY KEY)");
            connection.commit();
        }
    }

    @AfterEach void tearDown() throws Exception { fixture.close(); }

    @Test
    void failedDeliveryBacksOffAndNeverLosesTheEvent() throws Exception {
        publish("test.event", "run-1", "key-1", "{}");
        OutboxDispatcher dispatcher = dispatcher(handler("test.event", event -> {
            if (event.attempts() < 2) throw new IllegalStateException("simulated unavailable recipient");
            return true;
        }));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(1, dispatcher.dispatchBatch(10).retried());
            assertEquals(0, dispatcher.dispatchBatch(10).claimed(), "Retry must wait for its persisted backoff");
            clock.advance(Duration.ofSeconds(5));
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL"));
        assertEquals(1, dispatcher.dispatchBatch(10).delivered());
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL"));
        assertEquals(3, scalar("SELECT attempts FROM rpg_outbox"));
        assertEquals(2L, dispatcher.metrics().failed());
        assertEquals(1L, dispatcher.metrics().delivered());
    }

    @Test
    void duplicateKeyIsOnlyAReplayWhenItsContentIsIdentical() throws Exception {
        assertTrue(publish("test.event", "run", "fixed-key", "original"));
        assertFalse(publish("test.event", "run", "fixed-key", "original"));
        assertThrows(SQLException.class, () -> publish("test.event", "run", "fixed-key", "other player"));
        assertEquals(1, fixture.count("rpg_outbox"));
        AtomicInteger calls = new AtomicInteger();
        OutboxDispatcher dispatcher = dispatcher(handler("test.event", event -> { calls.incrementAndGet(); return true; }));
        assertEquals(1, dispatcher.dispatchBatch(10).delivered());
        assertEquals(0, dispatcher.dispatchBatch(10).claimed());
        assertEquals(1, calls.get());
    }

    @Test
    void businessRollbackRemovesTheEventAndAutoCommitPublicationIsRejected() throws Exception {
        try (Connection connection = fixture.dataSource.getConnection()) {
            outbox.publish(connection, "test.event", "rollback", "rollback-key", "{}");
            connection.rollback();
            connection.setAutoCommit(true);
            assertThrows(SQLException.class, () -> outbox.publish(connection, "test.event", "unsafe", "unsafe-key", "{}"));
        }
        assertEquals(0, fixture.count("rpg_outbox"));
    }

    @Test
    void unknownOrDeferredOldestEventCannotStarveAHealthyEvent() throws Exception {
        publish("unknown.type", "unknown", "unknown-key", "{}");
        OutboxDispatcher dispatcher = dispatcher(handler("test.event", ignored -> true));
        assertEquals(1, dispatcher.dispatchBatch(1).retried());
        publish("test.event", "healthy", "healthy-key", "{}");
        assertEquals(1, dispatcher.dispatchBatch(1).delivered());
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL"));
        assertEquals(1, dispatcher.metrics().failed());
    }

    @Test
    void crashedWorkerClaimExpiresAndItsOldTokenCannotAcknowledgeTheNewOwner() throws Exception {
        publish("test.event", "run", "key", "{}");
        AtomicInteger calls = new AtomicInteger();
        OutboxDispatcher.OutboxHandler handler = handler("test.event", ignored -> { calls.incrementAndGet(); return true; });
        OutboxDispatcher old = dispatcher(handler);
        OutboxDispatcher.Claim abandoned = old.claimBatch(1).getFirst();
        OutboxDispatcher restarted = dispatcher(handler);
        assertEquals(0, restarted.dispatchBatch(10).claimed());
        clock.advance(Duration.ofSeconds(31));
        OutboxDispatcher.Claim replacement = restarted.claimBatch(1).getFirst();
        assertNotEquals(abandoned.token(), replacement.token());
        assertEquals(OutboxDispatcher.Outcome.STALE, old.process(abandoned, handler));
        assertEquals(0, calls.get());
        assertEquals(OutboxDispatcher.Outcome.DELIVERED, restarted.process(replacement, handler));
        assertEquals(1, calls.get());
        assertEquals(2, scalar("SELECT attempts FROM rpg_outbox"));
    }

    @Test
    void concurrentWorkersDoNotDeliverTheSameLiveClaim() throws Exception {
        publish("test.event", "run", "key", "{}");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OutboxDispatcher.OutboxHandler handler = handler("test.event", ignored -> {
            calls.incrementAndGet();
            entered.countDown();
            try { if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return true;
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> dispatcher(handler).dispatchBatch(1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { assertEquals(0, workers.submit(() -> dispatcher(handler).dispatchBatch(1)).get(5, TimeUnit.SECONDS).claimed()); }
            finally { finish.countDown(); }
            assertEquals(1, first.get(5, TimeUnit.SECONDS).delivered());
        }
        assertEquals(1, calls.get());
    }

    @Test
    void sqlEffectsRollbackOnFailureAndCommitTogetherWithTheProcessedMarker() throws Exception {
        publish("test.event", "run", "key", "{}");
        AtomicBoolean fail = new AtomicBoolean(true);
        OutboxDispatcher.OutboxHandler handler = new OutboxDispatcher.OutboxHandler() {
            @Override public String aggregateType() { return "test.event"; }
            @Override public boolean handle(Connection connection, OutboxEvent event) throws SQLException {
                try (var insert = connection.prepareStatement("INSERT INTO test_outbox_effects VALUES (?)")) {
                    insert.setString(1, event.eventId());
                    insert.executeUpdate();
                }
                if (fail.getAndSet(false)) throw new SQLException("crash after handler SQL, before outbox acknowledgement");
                return true;
            }
        };
        OutboxDispatcher dispatcher = dispatcher(handler);
        assertEquals(1, dispatcher.dispatchBatch(1).retried());
        assertEquals(0, scalar("SELECT COUNT(*) FROM test_outbox_effects"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL"));
        clock.advance(Duration.ofSeconds(3));
        assertEquals(1, dispatcher.dispatchBatch(1).delivered());
        assertEquals(1, scalar("SELECT COUNT(*) FROM test_outbox_effects"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL"));
    }

    @Test
    void expiredClaimRollsBackHandlerSqlEvenWhenItsRowLockPreventedTakeover() throws Exception {
        publish("test.event", "run", "key", "{}");
        OutboxDispatcher dispatcher = dispatcher(new OutboxDispatcher.OutboxHandler() {
            @Override public String aggregateType() { return "test.event"; }
            @Override public boolean handle(Connection connection, OutboxEvent event) throws SQLException {
                try (var insert = connection.prepareStatement("INSERT INTO test_outbox_effects VALUES (?)")) {
                    insert.setString(1, event.eventId());
                    insert.executeUpdate();
                }
                clock.advance(Duration.ofSeconds(31));
                return true;
            }
        });
        assertEquals(1, dispatcher.dispatchBatch(1).retried());
        assertEquals(0, scalar("SELECT COUNT(*) FROM test_outbox_effects"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL"));
    }

    @Test
    void additiveUpgradeKeepsOldProcessedFactsAndMakesPendingRowsClaimableAfterRestart() throws Exception {
        try (Connection connection = fixture.dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE rpg_outbox");
            sql.execute("""
                    CREATE TABLE rpg_outbox (
                        event_id VARCHAR(36) PRIMARY KEY, aggregate_type VARCHAR(32) NOT NULL,
                        aggregate_id VARCHAR(36) NOT NULL, idempotency_key VARCHAR(96), payload TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0, last_error VARCHAR(240),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, processed_at TIMESTAMP)
                    """);
            for (int index = 0; index < 2; index++) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO rpg_outbox (event_id,aggregate_type,aggregate_id,idempotency_key,payload,attempts,processed_at)
                        VALUES (?, 'test.event', ?, ?, '{}', 3, ?)
                        """)) {
                    insert.setString(1, java.util.UUID.randomUUID().toString());
                    insert.setString(2, "legacy-" + index);
                    insert.setString(3, "legacy-key-" + index);
                    insert.setTimestamp(4, index == 0 ? java.sql.Timestamp.from(clock.instant().minusSeconds(1)) : null);
                    insert.executeUpdate();
                }
            }
            connection.commit();
        }
        fixture.database.migrate();
        fixture.database.migrate();
        assertEquals(1, dispatcher(handler("test.event", ignored -> true)).dispatchBatch(10).delivered());
        assertEquals(2, fixture.count("rpg_outbox"));
        assertEquals(3, scalar("SELECT attempts FROM rpg_outbox WHERE aggregate_id = 'legacy-0'"));
        assertEquals(4, scalar("SELECT attempts FROM rpg_outbox WHERE aggregate_id = 'legacy-1'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL"));
    }

    private OutboxDispatcher dispatcher(OutboxDispatcher.OutboxHandler handler) {
        return new OutboxDispatcher(fixture.dataSource, List.of(handler), Duration.ofSeconds(30), Duration.ofSeconds(2), clock);
    }

    private OutboxDispatcher.OutboxHandler handler(String type, java.util.function.Predicate<OutboxEvent> body) {
        return new OutboxDispatcher.OutboxHandler() {
            @Override public String aggregateType() { return type; }
            @Override public boolean handle(OutboxEvent event) { return body.test(event); }
        };
    }

    private boolean publish(String type, String id, String key, String payload) throws SQLException {
        try (Connection connection = fixture.dataSource.getConnection()) {
            try {
                boolean inserted = outbox.publish(connection, type, id, key, payload);
                connection.commit();
                return inserted;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = fixture.dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant value;
        MutableClock(Instant value) { this.value = value; }
        synchronized void advance(Duration delta) { value = value.plus(delta); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return value; }
    }
}
