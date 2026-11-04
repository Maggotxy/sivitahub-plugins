package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.FieldJournalEntry;
import cn.originalrpg.api.model.FieldJournalUpdate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class JdbcFieldJournalServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcFieldJournalService service;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:field_journal_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = Executors.newFixedThreadPool(4);
        service = new JdbcFieldJournalService(dataSource, executor);
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_field_journal_entries (
                        player_uuid VARCHAR(36) NOT NULL,
                        entry_id VARCHAR(48) NOT NULL,
                        advancement_key VARCHAR(128) NOT NULL,
                        recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, entry_id))
                    """);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void repeatedEventsAndServiceRestartPreserveOneFirstRegistration() {
        UUID player = UUID.randomUUID();
        var first = service.recordCompleted(player, Set.of(FieldJournalEntry.NATURALIST)).toCompletableFuture().join();
        var reconnected = new JdbcFieldJournalService(dataSource, executor)
                .recordCompleted(player, Set.of(FieldJournalEntry.NATURALIST)).toCompletableFuture().join();
        assertEquals(Set.of(FieldJournalEntry.NATURALIST), first.newlyRecorded());
        assertTrue(reconnected.newlyRecorded().isEmpty());
        assertEquals(first.records(), reconnected.records());
        assertEquals(first.records(), service.records(player).toCompletableFuture().join());
    }

    @Test
    void concurrentObserversProduceExactlyOneStampPerGoal() {
        UUID player = UUID.randomUUID();
        Set<FieldJournalEntry> completed = EnumSet.allOf(FieldJournalEntry.class);
        List<CompletableFuture<FieldJournalUpdate>> observations = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            observations.add(service.recordCompleted(player, completed).toCompletableFuture());
        }
        CompletableFuture.allOf(observations.toArray(CompletableFuture[]::new)).join();
        assertEquals(6, observations.stream().map(CompletableFuture::join)
                .mapToInt(update -> update.newlyRecorded().size()).sum());
        assertEquals(6, service.records(player).toCompletableFuture().join().size());
        assertTrue(service.records(UUID.randomUUID()).toCompletableFuture().join().isEmpty());
    }

    @Test
    void emptyRefreshNeverFabricatesACompletion() {
        UUID player = UUID.randomUUID();
        var empty = service.recordCompleted(player, Set.of()).toCompletableFuture().join();
        assertTrue(empty.records().isEmpty());
        assertTrue(empty.newlyRecorded().isEmpty());
        service.recordCompleted(player, Set.of(FieldJournalEntry.FORAGER)).toCompletableFuture().join();
        var after = service.recordCompleted(player, Set.of()).toCompletableFuture().join();
        assertEquals(List.of(FieldJournalEntry.FORAGER), after.records().stream().map(record -> record.entry()).toList());
        assertTrue(after.newlyRecorded().isEmpty());
    }

    @Test
    void failedSecondInsertRollsBackTheWholeObservationBatch() {
        AtomicInteger inserts = new AtomicInteger();
        DataSource failing = wrap((connection, method, args) -> {
            if (method.getName().equals("prepareStatement") && ((String) args[0]).contains("INSERT INTO")
                    && inserts.incrementAndGet() == 2) throw new SQLException("injected insert failure");
            return invoke(connection, method, args);
        });
        UUID player = UUID.randomUUID();
        assertThrows(CompletionException.class, () -> new JdbcFieldJournalService(failing, executor)
                .recordCompleted(player, EnumSet.allOf(FieldJournalEntry.class)).toCompletableFuture().join());
        assertTrue(service.records(player).toCompletableFuture().join().isEmpty());
        assertEquals(6, service.recordCompleted(player, EnumSet.allOf(FieldJournalEntry.class))
                .toCompletableFuture().join().newlyRecorded().size());
    }

    @Test
    void lostCommitAcknowledgmentCanBeReconciledWithoutAnotherStamp() {
        AtomicBoolean lost = new AtomicBoolean();
        DataSource ambiguous = wrap((connection, method, args) -> {
            Object result = invoke(connection, method, args);
            if (method.getName().equals("commit") && lost.compareAndSet(false, true)) {
                throw new SQLException("commit completed but acknowledgment was lost");
            }
            return result;
        });
        UUID player = UUID.randomUUID();
        Set<FieldJournalEntry> completed = Set.of(FieldJournalEntry.OVERWORLD_EXPLORER, FieldJournalEntry.NETHER_EXPLORER);
        assertThrows(CompletionException.class, () -> new JdbcFieldJournalService(ambiguous, executor)
                .recordCompleted(player, completed).toCompletableFuture().join());
        var committed = service.records(player).toCompletableFuture().join();
        assertEquals(2, committed.size());
        var retry = service.recordCompleted(player, completed).toCompletableFuture().join();
        assertTrue(retry.newlyRecorded().isEmpty());
        assertEquals(committed, retry.records());
    }

    @Test
    void rejectedExecutorFailsAsynchronouslyWithoutRunningDatabaseWorkOnCaller() {
        JdbcFieldJournalService saturated = new JdbcFieldJournalService(dataSource,
                task -> { throw new RejectedExecutionException("bounded queue is full"); });
        var result = saturated.recordCompleted(UUID.randomUUID(), Set.of(FieldJournalEntry.FORAGER));
        CompletionException exception = assertThrows(CompletionException.class, () -> result.toCompletableFuture().join());
        assertInstanceOf(RejectedExecutionException.class, exception.getCause());
    }

    @Test
    void receiptWithDifferentEvidenceCannotBeSilentlyReinterpreted() throws Exception {
        UUID player = UUID.randomUUID();
        service.recordCompleted(player, Set.of(FieldJournalEntry.FORAGER)).toCompletableFuture().join();
        try (Connection connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "UPDATE rpg_field_journal_entries SET advancement_key = ? WHERE player_uuid = ?")) {
            statement.setString(1, "minecraft:recipes/food/bread");
            statement.setString(2, player.toString());
            statement.executeUpdate();
            connection.commit();
        }
        assertThrows(CompletionException.class, () -> service.records(player).toCompletableFuture().join());
    }

    private DataSource wrap(ConnectionCall intercept) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (self, method, args) -> {
                    Object value = invoke(dataSource, method, args);
                    if (!(value instanceof Connection connection)) return value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, operation, inputs) -> intercept.run(connection, operation, inputs));
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    @FunctionalInterface
    private interface ConnectionCall {
        Object run(Connection connection, Method method, Object[] args) throws Throwable;
    }
}
