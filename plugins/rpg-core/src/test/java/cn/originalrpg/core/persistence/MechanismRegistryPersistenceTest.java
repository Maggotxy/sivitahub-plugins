package cn.originalrpg.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises persisted operator decisions and failure recovery, without mutating the registry cache. */
final class MechanismRegistryPersistenceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void initializationPreservesAnOperatorsClosedSwitchAcrossRestart(boolean postgresCompatibility) throws Exception {
        try (HikariDataSource dataSource = database(postgresCompatibility)) {
            MechanismRegistry first = new MechanismRegistry(dataSource);
            first.initialize();
            assertTrue(first.requireEnabled(MechanismRegistry.HUNT_GOLD));
            first.setEnabled(MechanismRegistry.HUNT_GOLD, false, "incident-operator");

            MechanismRegistry restarted = new MechanismRegistry(dataSource);
            restarted.initialize();
            restarted.initialize();

            assertFalse(restarted.requireEnabled(MechanismRegistry.HUNT_GOLD));
            assertEquals(new SwitchRow(false, "incident-operator"), row(dataSource, MechanismRegistry.HUNT_GOLD));
            assertEquals(MechanismRegistry.DEFAULTS.size(), rowCount(dataSource));
            assertTrue(restarted.requireEnabled(MechanismRegistry.DUNGEON_GOLD));
            assertEquals(1L, restarted.metrics().get(MechanismRegistry.HUNT_GOLD).denied());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void settingASwitchCreatesOrUpdatesTheDurableDecision(boolean postgresCompatibility) throws Exception {
        try (HikariDataSource dataSource = database(postgresCompatibility)) {
            MechanismRegistry registry = new MechanismRegistry(dataSource);
            assertFalse(registry.isEnabled(MechanismRegistry.MARKET_ESCROW));

            registry.setEnabled(MechanismRegistry.MARKET_ESCROW, false, "freeze");
            assertFalse(registry.requireEnabled(MechanismRegistry.MARKET_ESCROW));
            assertEquals(new SwitchRow(false, "freeze"), row(dataSource, MechanismRegistry.MARKET_ESCROW));

            registry.setEnabled(MechanismRegistry.MARKET_ESCROW, true, "reviewed-reopen");
            assertTrue(registry.requireEnabled(MechanismRegistry.MARKET_ESCROW));
            assertEquals(new SwitchRow(true, "reviewed-reopen"), row(dataSource, MechanismRegistry.MARKET_ESCROW));
            assertEquals(1, rowCount(dataSource));

            MechanismRegistry reloaded = new MechanismRegistry(dataSource);
            reloaded.refresh();
            assertTrue(reloaded.isEnabled(MechanismRegistry.MARKET_ESCROW));
        }
    }

    @Test
    void refreshingRemovesDeletedSwitchesAndReadsChangedDecisions() throws Exception {
        try (HikariDataSource dataSource = database(true)) {
            MechanismRegistry registry = new MechanismRegistry(dataSource);
            registry.initialize();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM rpg_mechanism_switches WHERE mechanism_id='"
                        + MechanismRegistry.HUNT_GOLD + "'");
                statement.executeUpdate("UPDATE rpg_mechanism_switches SET enabled=FALSE WHERE mechanism_id='"
                        + MechanismRegistry.DUNGEON_GOLD + "'");
                connection.commit();
            }

            registry.refresh();

            assertFalse(registry.requireEnabled(MechanismRegistry.HUNT_GOLD), "A removed mechanism must fail closed");
            assertFalse(registry.requireEnabled(MechanismRegistry.DUNGEON_GOLD));
            assertTrue(registry.requireEnabled(MechanismRegistry.COMMISSION_GOLD));
        }
    }

    @Test
    void stoppedRefreshExpiresAdmissionWithoutDatabaseWorkOnTheReaderThread() throws Exception {
        try (HikariDataSource dataSource = database(true)) {
            AtomicLong clock = new AtomicLong();
            MechanismRegistry registry = new MechanismRegistry(dataSource, clock::get);
            registry.initialize();
            clock.set(java.time.Duration.ofSeconds(9).toNanos());
            assertTrue(registry.requireEnabled(MechanismRegistry.HUNT_GOLD));
            clock.set(java.time.Duration.ofSeconds(10).toNanos());
            assertFalse(registry.requireEnabled(MechanismRegistry.HUNT_GOLD));
            assertEquals(new SwitchRow(true, "system"), row(dataSource, MechanismRegistry.HUNT_GOLD));
            registry.refresh();
            assertTrue(registry.requireEnabled(MechanismRegistry.HUNT_GOLD));
        }
    }

    @Test
    void failedRefreshClosesAnEarlierAllowedSnapshotUntilCompleteRecovery() throws Exception {
        try (HikariDataSource dataSource = database(true)) {
            new MechanismRegistry(dataSource).initialize();
            MechanismRegistry registry = new MechanismRegistry(failOnce(dataSource, ResultSet.class, "next",
                    MechanismRegistry.DEFAULTS.size() + 3, new AtomicInteger()));
            registry.refresh();
            assertTrue(registry.isEnabled(MechanismRegistry.HUNT_GOLD));
            assertThrows(IllegalStateException.class, registry::refresh);
            assertFalse(registry.isEnabled(MechanismRegistry.HUNT_GOLD));
            registry.refresh();
            assertTrue(registry.isEnabled(MechanismRegistry.HUNT_GOLD));
        }
    }

    @Test
    void arbitraryDatabaseFlagsCannotRegisterNewRuntimeMechanisms() throws Exception {
        try (HikariDataSource dataSource = database(true)) {
            MechanismRegistry registry = new MechanismRegistry(dataSource);
            registry.initialize();
            try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO rpg_mechanism_switches (mechanism_id,enabled,updated_by)"
                        + " VALUES ('unreviewed.faucet',TRUE,'outside')");
                connection.commit();
            }
            registry.refresh();
            assertFalse(registry.isEnabled("unreviewed.faucet"));
            try (Connection connection = dataSource.getConnection()) {
                assertFalse(registry.isEnabled(connection, "unreviewed.faucet"));
                assertFalse(registry.requireEnabled(connection, "unreviewed.faucet"));
            }
            assertThrows(IllegalArgumentException.class, () -> registry.setEnabled("unreviewed.faucet", true, "console"));
        }
    }

    @Test
    void failedStatementIsReportedWithoutTryingAnotherDialectOrChangingTheDecision() throws Exception {
        try (HikariDataSource dataSource = database(true)) {
            MechanismRegistry original = new MechanismRegistry(dataSource);
            original.initialize();
            AtomicInteger attempts = new AtomicInteger();
            MechanismRegistry failing = new MechanismRegistry(failOnce(
                    dataSource, Connection.class, "prepareStatement", 1, attempts));

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> failing.setEnabled(MechanismRegistry.HUNT_GOLD, false, "operator"));

            assertEquals("injected JDBC failure", assertInstanceOf(SQLException.class, failure.getCause()).getMessage());
            assertEquals(1, attempts.get(), "A SQL failure must not be retried as another database dialect");
            assertEquals(new SwitchRow(true, "system"), row(dataSource, MechanismRegistry.HUNT_GOLD));
            assertFalse(failing.isEnabled(MechanismRegistry.HUNT_GOLD), "An unsuccessful write must not populate the cache");
        }
    }

    @Test
    void commitFailureRollsBackTheWriteAndClosesAdmissionUntilACompleteRefresh() throws Exception {
        try (HikariDataSource dataSource = database(true)) {
            MechanismRegistry original = new MechanismRegistry(dataSource);
            original.initialize();
            MechanismRegistry failing = new MechanismRegistry(failOnce(
                    dataSource, Connection.class, "commit", 1, new AtomicInteger()));
            failing.refresh();

            assertThrows(IllegalStateException.class,
                    () -> failing.setEnabled(MechanismRegistry.HUNT_GOLD, false, "operator"));

            assertFalse(failing.isEnabled(MechanismRegistry.HUNT_GOLD));
            assertEquals(new SwitchRow(true, "system"), row(dataSource, MechanismRegistry.HUNT_GOLD));
            failing.refresh();
            assertTrue(failing.isEnabled(MechanismRegistry.HUNT_GOLD));
        }
    }

    @Test
    void anInterruptedRefreshDoesNotPublishAPartiallyLoadedSnapshot() throws Exception {
        try (HikariDataSource dataSource = database(true)) {
            new MechanismRegistry(dataSource).initialize();
            MechanismRegistry failing = new MechanismRegistry(failOnce(
                    dataSource, ResultSet.class, "next", 2, new AtomicInteger()));

            assertThrows(IllegalStateException.class, failing::refresh);

            for (String mechanism : MechanismRegistry.DEFAULTS.keySet()) {
                assertFalse(failing.isEnabled(mechanism), "A failed refresh must not enable even the first loaded row");
            }
        }
    }

    @Test
    void failedInitializationRollsBackAllSeededRows() throws Exception {
        try (HikariDataSource dataSource = database(false)) {
            MechanismRegistry failing = new MechanismRegistry(failOnce(
                    dataSource, PreparedStatement.class, "executeUpdate", 3, new AtomicInteger()));

            assertThrows(IllegalStateException.class, failing::initialize);

            assertEquals(0, rowCount(dataSource));
            for (String mechanism : MechanismRegistry.DEFAULTS.keySet()) {
                assertFalse(failing.isEnabled(mechanism));
            }
        }
    }

    private static HikariDataSource database(boolean postgresCompatibility) throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:mechanisms_" + UUID.randomUUID()
                + (postgresCompatibility ? ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" : ""));
        config.setAutoCommit(false);
        // Initialization must not borrow a second connection while keeping its first one open.
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(1_000);
        HikariDataSource dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_mechanism_switches (
                        mechanism_id VARCHAR(64) PRIMARY KEY,
                        enabled BOOLEAN NOT NULL DEFAULT TRUE,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_by VARCHAR(64) NOT NULL DEFAULT 'system'
                    )
                    """);
            connection.commit();
        }
        return dataSource;
    }

    private static SwitchRow row(DataSource dataSource, String mechanism) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT enabled, updated_by FROM rpg_mechanism_switches WHERE mechanism_id=?")) {
            query.setString(1, mechanism);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return new SwitchRow(result.getBoolean(1), result.getString(2));
            }
        }
    }

    private static int rowCount(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM rpg_mechanism_switches")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private record SwitchRow(boolean enabled, String actor) { }

    /** Injects one JDBC failure while retaining the real database's transaction and rollback behavior. */
    private static DataSource failOnce(DataSource real, Class<?> targetType, String methodName,
                                       int failOnCall, AtomicInteger calls) {
        return (DataSource) wrap(real, targetType, methodName, failOnCall, calls);
    }

    private static Object wrap(Object real, Class<?> targetType, String methodName,
                               int failOnCall, AtomicInteger calls) {
        Class<?> jdbcType;
        if (real instanceof DataSource) jdbcType = DataSource.class;
        else if (real instanceof Connection) jdbcType = Connection.class;
        else if (real instanceof PreparedStatement) jdbcType = PreparedStatement.class;
        else if (real instanceof ResultSet) jdbcType = ResultSet.class;
        else return real;
        return Proxy.newProxyInstance(MechanismRegistryPersistenceTest.class.getClassLoader(), new Class<?>[]{jdbcType},
                (proxy, method, arguments) -> {
                    if (targetType.isInstance(real) && method.getName().equals(methodName)
                            && calls.incrementAndGet() == failOnCall) {
                        throw new SQLException("injected JDBC failure");
                    }
                    try {
                        return wrap(method.invoke(real, arguments), targetType, methodName, failOnCall, calls);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
