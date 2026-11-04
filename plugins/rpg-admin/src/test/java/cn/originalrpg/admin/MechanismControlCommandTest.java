package cn.originalrpg.admin;

import cn.originalrpg.core.persistence.Database;
import cn.originalrpg.core.persistence.JdbcMechanismControlService;
import cn.originalrpg.core.persistence.MechanismRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The real console command drives the actual Core registry and SQL, with controllable worker/UI boundaries. */
final class MechanismControlCommandTest {
    private static final String ID = MechanismRegistry.MARKET_ESCROW;
    private final Thread main = Thread.currentThread();
    private final List<Runnable> workerQueue = new ArrayList<>();
    private final List<Runnable> uiQueue = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final AtomicBoolean loseCommitReply = new AtomicBoolean();
    private final AtomicBoolean rejectDispatch = new AtomicBoolean();
    private Database database;
    private DataSource dataSource;
    private MechanismControlCommand command;
    private ConsoleCommandSender console;

    @BeforeEach
    void setup() throws Exception {
        for (String variable : List.of("RPG_DATABASE_URL", "RPG_DATABASE_USER", "RPG_DATABASE_PASSWORD")) {
            String value = System.getenv(variable);
            assertTrue(value == null || value.isBlank(), "Mechanism command tests refuse runtime override " + variable);
        }
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("database.jdbc-url", "jdbc:h2:mem:mechanism_command_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        database = Database.open(configuration);
        database.migrate();
        dataSource = database.dataSource();
        MechanismRegistry registry = new MechanismRegistry(acknowledgementFaultSource());
        registry.initialize();
        JdbcMechanismControlService service = new JdbcMechanismControlService(registry, workerQueue::add);
        command = new MechanismControlCommand(service, action -> {
            if (rejectDispatch.get()) throw new IllegalStateException("Plugin scheduler closed");
            synchronized (uiQueue) { uiQueue.add(action); }
        }, Logger.getLogger("mechanism-console-test"));
        console = sender(ConsoleCommandSender.class, true);
    }

    @AfterEach
    void close() {
        if (command != null) command.close();
        if (database != null) database.close();
    }

    @Test
    void neitherAPermissionBearingPlayerNorAnUnauthorizedConsoleCanChangeTheSwitch() throws Exception {
        CommandSender nonConsole = sender(CommandSender.class, true);
        command.execute(nonConsole, change("CONFIRM", "reviewed"));
        command.execute(sender(ConsoleCommandSender.class, false), change("CONFIRM", "reviewed"));
        assertTrue(workerQueue.isEmpty());
        assertTrue(uiQueue.isEmpty());
        assertEquals(2, messages.stream().filter(line -> line.contains("authorized server console")).count());
        assertTrue(storedEnabled());
        assertEquals("system", storedActor());
    }

    @Test
    void confirmLiteralAndABoundedNonblankReasonAreMandatoryBeforeDispatch() throws Exception {
        for (String[] args : List.of(
                new String[]{"mechanism", "disable", ID},
                new String[]{"mechanism", "disable", ID, "CONFIRM"},
                change("confirm", "reviewed"), change("CONFIRM", "   "),
                change("CONFIRM", "x".repeat(57)), change("CONFIRM", "incident\nlog"))) {
            assertTrue(command.execute(console, args));
        }
        assertEquals(6, messages.size());
        assertTrue(messages.stream().allMatch(line -> line.startsWith("/rpgadmin mechanism")));
        assertTrue(workerQueue.isEmpty());
        assertTrue(storedEnabled());
        assertEquals("system", storedActor());
    }

    @Test
    void oneOperationOwnsAdmissionThroughDatabaseCompletionAndMainThreadFeedback() throws Exception {
        assertTrue(command.execute(console, change("CONFIRM", "maintenance", "reviewed")));
        assertEquals(1, workerQueue.size());
        assertTrue(storedEnabled(), "no JDBC work happens in the console handler");
        assertTrue(messages.isEmpty());
        command.execute(console, new String[]{"mechanism", "list"});
        assertEquals(1, workerQueue.size());
        runDatabaseWorker();
        assertFalse(storedEnabled());
        assertEquals("console:maintenance reviewed", storedActor());
        assertEquals(1, uiQueue.size());
        assertFalse(messages.stream().anyMatch(line -> line.startsWith(ID + "=false.")));
        command.execute(console, new String[]{"mechanism", "list"});
        assertTrue(workerQueue.isEmpty(), "a completed worker does not release the pending UI operation early");
        assertEquals(2, messages.stream().filter(line -> line.contains("operation is pending")).count());
        drainUi();
        assertTrue(messages.stream().anyMatch(line -> line.startsWith(ID + "=false.")));
        command.execute(console, new String[]{"mechanism", "list"});
        assertEquals(1, workerQueue.size());
        runDatabaseWorker(); drainUi();
        assertTrue(messages.stream().anyMatch(line -> line.startsWith(ID + "=false allowedChecks=")));
        assertTrue(messages.stream().anyMatch(line -> line.contains("local to this Paper process")));
    }

    @Test
    void unknownIdNeverCreatesASwitchAndReleasesAdmissionAfterItsErrorIsRendered() throws Exception {
        command.execute(console, new String[]{"mechanism", "disable", "typo.enabled", "CONFIRM", "reviewed"});
        assertTrue(workerQueue.isEmpty(), "the real Core adapter rejects unknown IDs before scheduling SQL");
        assertEquals(1, uiQueue.size());
        assertEquals(MechanismRegistry.DEFAULTS.size(), storedCount());
        assertTrue(storedEnabled());
        drainUi();
        assertTrue(messages.stream().anyMatch(line -> line.contains("not confirmed")));
        command.execute(console, new String[]{"mechanism", "list"});
        assertEquals(1, workerQueue.size());
        runDatabaseWorker(); drainUi();
        assertTrue(messages.stream().anyMatch(line -> line.startsWith(ID + "=true allowedChecks=")));
    }

    @Test
    void lostCommitReplyNeverPrintsSuccessOrRevertsTheDurablyChangedState() throws Exception {
        loseCommitReply.set(true);
        command.execute(console, change("CONFIRM", "unknown", "acknowledgement"));
        runDatabaseWorker();
        assertFalse(storedEnabled());
        assertEquals("console:unknown acknowledgement", storedActor());
        assertTrue(messages.isEmpty(), "the worker cannot touch the Bukkit console");
        drainUi();
        assertTrue(messages.stream().anyMatch(line -> line.contains("not confirmed") && line.contains("Read current state")));
        assertFalse(messages.stream().anyMatch(line -> line.startsWith(ID + "=false.")));
        command.execute(console, new String[]{"mechanism", "list"});
        runDatabaseWorker(); drainUi();
        assertTrue(messages.stream().anyMatch(line -> line.startsWith(ID + "=false allowedChecks=")));
        assertEquals(MechanismRegistry.DEFAULTS.size(), storedCount());
    }

    @Test
    void closingAfterCommitDropsAnAlreadyQueuedCallback() throws Exception {
        command.execute(console, change("CONFIRM", "committed", "before", "shutdown"));
        runDatabaseWorker();
        assertFalse(storedEnabled());
        assertEquals(1, uiQueue.size());
        command.close();
        drainUi();
        assertTrue(messages.isEmpty());
        command.execute(console, new String[]{"mechanism", "list"});
        assertTrue(workerQueue.isEmpty());
    }

    @Test
    void closingBeforeTheWorkerFinishesDoesNotPretendAnAcceptedOperationWasCancelled() throws Exception {
        command.execute(console, change("CONFIRM", "accepted", "before", "shutdown"));
        command.close();
        runDatabaseWorker();
        assertFalse(storedEnabled());
        assertEquals("console:accepted before shutdown", storedActor());
        assertTrue(uiQueue.isEmpty());
        assertTrue(messages.isEmpty());
    }

    @Test
    void rejectedSchedulerDispatchDoesNotSendFromTheWorkerOrPermanentlyHoldAdmission() throws Exception {
        rejectDispatch.set(true);
        command.execute(console, change("CONFIRM", "scheduler", "unavailable"));
        runDatabaseWorker();
        assertFalse(storedEnabled());
        assertTrue(messages.isEmpty());
        rejectDispatch.set(false);
        command.execute(console, new String[]{"mechanism", "list"});
        assertEquals(1, workerQueue.size());
        runDatabaseWorker(); drainUi();
        assertTrue(messages.stream().anyMatch(line -> line.startsWith(ID + "=false allowedChecks=")));
    }

    private String[] change(String confirmation, String... reason) {
        List<String> args = new ArrayList<>(List.of("mechanism", "disable", ID, confirmation));
        args.addAll(List.of(reason)); return args.toArray(String[]::new);
    }

    private void runDatabaseWorker() {
        assertEquals(1, workerQueue.size());
        Runnable task = workerQueue.removeFirst();
        CompletableFuture.runAsync(task).join();
    }

    private void drainUi() {
        assertSame(main, Thread.currentThread());
        while (!uiQueue.isEmpty()) uiQueue.removeFirst().run();
    }

    private <T extends CommandSender> T sender(Class<T> type, boolean permitted) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            assertSame(main, Thread.currentThread(), "Bukkit sender access must stay on main");
            return switch (method.getName()) {
                case "hasPermission" -> permitted;
                case "sendMessage" -> {
                    if (args != null && args.length > 0 && args[0] instanceof Component component) messages.add(plain(component));
                    yield null;
                }
                case "equals" -> self == args[0];
                case "hashCode" -> System.identityHashCode(self);
                case "toString", "getName" -> "mechanism-test-console";
                default -> method.getReturnType() == boolean.class ? false : null;
            };
        }));
    }

    private static String plain(Component component) {
        String content = component instanceof TextComponent text ? text.content() : "";
        return content + component.children().stream().map(MechanismControlCommandTest::plain).collect(java.util.stream.Collectors.joining());
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
             var query = connection.prepareStatement("SELECT updated_by FROM rpg_mechanism_switches WHERE mechanism_id = ?")) {
            query.setString(1, ID);
            try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getString(1); }
        }
    }

    private long storedCount() throws Exception {
        try (Connection connection = dataSource.getConnection(); var query = connection.createStatement();
             var row = query.executeQuery("SELECT COUNT(*) FROM rpg_mechanism_switches")) {
            assertTrue(row.next()); return row.getLong(1);
        }
    }

    private DataSource acknowledgementFaultSource() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (self, method, args) -> {
                    Object value = invoke(dataSource, method, args);
                    if (!method.getName().equals("getConnection")) return value;
                    Connection connection = (Connection) value;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (proxy, operation, inputs) -> {
                                Object result = invoke(connection, operation, inputs);
                                if (operation.getName().equals("commit") && loseCommitReply.getAndSet(false)) {
                                    throw new SQLException("injected lost commit acknowledgement");
                                }
                                return result;
                            });
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
