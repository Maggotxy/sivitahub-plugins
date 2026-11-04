package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.MechanismSwitch;
import org.bukkit.configuration.file.YamlConfiguration;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real migrated SQL proves operator writes and uncertain acknowledgements, independently of the console adapter. */
final class JdbcMechanismControlServiceTest {
    private static final String ID = MechanismRegistry.MARKET_ESCROW;
    private Database database;
    private DataSource dataSource;
    private ExecutorService executor;
    private MechanismRegistry registry;
    private JdbcMechanismControlService service;

    @BeforeEach
    void setup() throws Exception {
        for (String variable : List.of("RPG_DATABASE_URL", "RPG_DATABASE_USER", "RPG_DATABASE_PASSWORD")) {
            String value = System.getenv(variable);
            assertTrue(value == null || value.isBlank(), "Mechanism tests refuse runtime override " + variable);
        }
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database.jdbc-url", "jdbc:h2:mem:mechanism_control_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        database = Database.open(configuration);
        database.migrate();
        dataSource = database.dataSource();
        registry = new MechanismRegistry(dataSource);
        registry.initialize();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        service = new JdbcMechanismControlService(registry, executor);
    }

    @AfterEach
    void close() {
        if (executor != null) executor.close();
        if (database != null) database.close();
    }

    @Test
    void unknownIdsAndInvalidAuditLabelsAreRejectedBeforeWorkerAdmission() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        var controlled = new JdbcMechanismControlService(registry, queued::add);
        assertInvalid(controlled.setEnabled("unregistered.enabled", false, "console:incident"));
        assertInvalid(controlled.setEnabled(null, false, "console:incident"));
        for (String label : new String[]{null, "", "   ", "x".repeat(65), "console:line\nbreak", "console:\u0000"}) {
            assertInvalid(controlled.setEnabled(ID, false, label));
        }
        assertTrue(queued.isEmpty());
        assertTrue(storedEnabled());
        assertEquals("system", storedActor());
        assertEquals(MechanismRegistry.DEFAULTS.size(), storedCount());
    }

    @Test
    void explicitStateAndExactOperatorReasonReachTheExistingSwitchRow() throws Exception {
        String reason = "console:incident-104 reviewed";
        MechanismSwitch disabled = await(service.setEnabled(ID, false, reason));
        assertEquals(ID, disabled.id());
        assertFalse(disabled.enabled());
        assertFalse(storedEnabled());
        assertEquals(reason, storedActor());
        assertFalse(registry.requireEnabled(ID));
        MechanismSwitch enabled = await(service.setEnabled(ID, true, "console:review complete"));
        assertTrue(enabled.enabled());
        assertTrue(storedEnabled());
        assertEquals("console:review complete", storedActor());
        assertTrue(registry.requireEnabled(ID));
        MechanismSwitch report = await(service.switches()).stream().filter(row -> row.id().equals(ID)).findFirst().orElseThrow();
        assertEquals(1, report.allowedChecks());
        assertEquals(1, report.deniedChecks());
        assertEquals(MechanismRegistry.DEFAULTS.size(), storedCount());
    }

    @Test
    void listingRefreshesAnotherNodesDurableChangeAndPreservesOnlyLocalCounters() {
        assertTrue(registry.requireEnabled(ID));
        MechanismRegistry second = new MechanismRegistry(dataSource);
        second.initialize();
        second.setEnabled(ID, false, "console:other node review");
        assertTrue(registry.isEnabled(ID), "the test first establishes a stale local snapshot");
        var rows = await(service.switches());
        assertEquals(rows.stream().map(MechanismSwitch::id).sorted().toList(), rows.stream().map(MechanismSwitch::id).toList());
        var changed = rows.stream().filter(row -> row.id().equals(ID)).findFirst().orElseThrow();
        assertFalse(changed.enabled());
        assertFalse(registry.isEnabled(ID));
        assertEquals(1, changed.allowedChecks());
        assertEquals(0, changed.deniedChecks());
        assertThrows(UnsupportedOperationException.class, rows::clear);
    }

    @Test
    void realCommitWithLostReplyRemainsUncertainUntilTheDurableStateIsRead() throws Exception {
        AtomicBoolean loseReply = new AtomicBoolean(false);
        MechanismRegistry uncertain = new MechanismRegistry(wrap((connection, method, arguments) -> {
            Object result = invoke(connection, method, arguments);
            if (method.getName().equals("commit") && loseReply.getAndSet(false)) {
                throw new SQLException("injected lost commit acknowledgement");
            }
            return result;
        }));
        uncertain.initialize();
        var control = new JdbcMechanismControlService(uncertain, executor);
        loseReply.set(true);
        assertThrows(CompletionException.class, () -> await(control.setEnabled(ID, false, "console:unknown commit")));
        assertFalse(storedEnabled(), "a missing reply must not be interpreted as rollback");
        assertEquals("console:unknown commit", storedActor());
        assertFalse(await(control.switches()).stream().filter(row -> row.id().equals(ID)).findFirst().orElseThrow().enabled());
        assertFalse(uncertain.isEnabled(ID));
        await(control.setEnabled(ID, false, "console:verified retry"));
        assertEquals(MechanismRegistry.DEFAULTS.size(), storedCount());
        assertEquals("console:verified retry", storedActor());
    }

    @Test
    void failedCommitRollsBackTheSwitchAndClosesAdmissionUntilVerifiedRefresh() throws Exception {
        AtomicBoolean failCommit = new AtomicBoolean(false);
        MechanismRegistry failing = new MechanismRegistry(wrap((connection, method, arguments) -> {
            if (method.getName().equals("commit") && failCommit.getAndSet(false)) {
                throw new SQLException("injected before commit");
            }
            return invoke(connection, method, arguments);
        }));
        failing.initialize();
        failCommit.set(true);
        var control = new JdbcMechanismControlService(failing, executor);
        assertThrows(CompletionException.class, () -> await(control.setEnabled(ID, false, "console:uncommitted")));
        assertTrue(storedEnabled());
        assertFalse(failing.isEnabled(ID), "an uncertain control write closes cached admission until a complete read");
        assertEquals("system", storedActor());
        assertTrue(await(control.switches()).stream().filter(row -> row.id().equals(ID)).findFirst().orElseThrow().enabled());
        assertTrue(failing.isEnabled(ID));
    }

    private boolean storedEnabled() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT enabled FROM rpg_mechanism_switches WHERE mechanism_id = ?")) {
            query.setString(1, ID);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getBoolean(1); }
        }
    }

    private String storedActor() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var query = connection.prepareStatement("SELECT updated_by, updated_at FROM rpg_mechanism_switches WHERE mechanism_id = ?")) {
            query.setString(1, ID);
            try (var row = query.executeQuery()) {
                assertTrue(row.next()); assertNotNull(row.getTimestamp(2)); return row.getString(1);
            }
        }
    }

    private long storedCount() throws Exception {
        try (Connection connection = dataSource.getConnection(); var query = connection.createStatement();
             var row = query.executeQuery("SELECT COUNT(*) FROM rpg_mechanism_switches")) {
            assertTrue(row.next()); return row.getLong(1);
        }
    }

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

    private static void assertInvalid(CompletionStage<?> stage) {
        CompletionException error = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    @FunctionalInterface private interface ConnectionCall { Object run(Connection connection, Method method, Object[] args) throws Throwable; }
}
