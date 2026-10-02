package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationEngineTest {
    static final List<String> TABLES = List.of(
            "rpg_accounts", "rpg_ledger", "rpg_market_deliveries"
    );
    static final Map<String, List<List<String>>> UNIQUE_KEYS = Map.of(
            "rpg_ledger", List.of(List.of("idempotency_key"), List.of("reversal_of"))
    );
    static final Map<String, List<List<String>>> REQUIRED_INDEXES = Map.of(
            "rpg_market_deliveries", List.of(List.of("player_uuid", "state"))
    );

    @Test
    void migratesEmptyTargetAndSecondRunIsIdempotent() throws Exception {
        try (Connection source = h2("source"); Connection target = h2("target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", false);
            seedSource(source);

            MigrationEngine.Result first = engine().run(source, target, request(false, false, false));
            assertEquals(MigrationEngine.Outcome.MIGRATED, first.outcome());
            assertEquals(4, first.rows());
            assertEquals(new BigDecimal("1250.50"), scalarDecimal(target, "SELECT balance FROM rpg_accounts"));

            MigrationEngine.Result second = engine().run(source, target, request(false, false, false));
            assertEquals(MigrationEngine.Outcome.ALREADY_IDENTICAL, second.outcome());
            assertEquals(4, second.rows());
        }
    }

    @Test
    void dryRunDoesNotWrite() throws Exception {
        try (Connection source = h2("dry-source"); Connection target = h2("dry-target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", false);
            seedSource(source);

            MigrationEngine.Result result = engine().run(source, target, request(true, false, false));
            assertEquals(MigrationEngine.Outcome.DRY_RUN_EMPTY_TARGET, result.outcome());
            assertEquals(0, scalarLong(target, "SELECT COUNT(*) FROM rpg_accounts"));
        }
    }

    @Test
    void nonEmptyDifferentTargetFailsClosed() throws Exception {
        try (Connection source = h2("gate-source"); Connection target = h2("gate-target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", false);
            seedSource(source);
            execute(target, "INSERT INTO rpg_accounts VALUES ('other', 9.00, TIMESTAMP '2026-01-01 00:00:00')");

            MigrationException error = assertThrows(
                    MigrationException.class,
                    () -> engine().run(source, target, request(false, false, false))
            );
            assertTrue(error.getMessage().contains("differs from source"));
            assertEquals(1, scalarLong(target, "SELECT COUNT(*) FROM rpg_accounts"));
        }
    }

    @Test
    void explicitReplaceIsAtomicAndVerified() throws Exception {
        try (Connection source = h2("replace-source"); Connection target = h2("replace-target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", false);
            seedSource(source);
            execute(target, "INSERT INTO rpg_accounts VALUES ('other', 9.00, TIMESTAMP '2026-01-01 00:00:00')");

            MigrationEngine.Result result = engine().run(source, target, request(false, false, true));
            assertEquals(MigrationEngine.Outcome.MIGRATED, result.outcome());
            assertEquals(1, scalarLong(target, "SELECT COUNT(*) FROM rpg_accounts"));
            assertEquals(new BigDecimal("1250.50"), scalarDecimal(target, "SELECT balance FROM rpg_accounts"));
        }
    }

    @Test
    void verifyOnlyRejectsMismatchAndAcceptsExactCopy() throws Exception {
        try (Connection source = h2("verify-source"); Connection target = h2("verify-target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", false);
            seedSource(source);
            assertThrows(
                    MigrationException.class,
                    () -> engine().run(source, target, request(false, true, false))
            );
            engine().run(source, target, request(false, false, false));
            MigrationEngine.Result verified = engine().run(source, target, request(false, true, false));
            assertEquals(MigrationEngine.Outcome.VERIFIED, verified.outcome());
        }
    }

    @Test
    void incompatibleSchemaFailsBeforeCopy() throws Exception {
        try (Connection source = h2("schema-source"); Connection target = h2("schema-target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", true);
            seedSource(source);

            MigrationException error = assertThrows(
                    MigrationException.class,
                    () -> engine().run(source, target, request(false, false, false))
            );
            assertTrue(error.getMessage().contains("numeric precision"));
            assertEquals(0, scalarLong(target, "SELECT COUNT(*) FROM rpg_accounts"));
        }
    }

    @Test
    void missingRequiredPerformanceIndexFailsBeforeCopy() throws Exception {
        try (Connection source = h2("index-source"); Connection target = h2("index-target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", false);
            seedSource(source);
            execute(target, "DROP INDEX idx_test_delivery_player_state");

            MigrationException error = assertThrows(
                    MigrationException.class,
                    () -> engine().run(source, target, request(false, false, false))
            );
            assertTrue(error.getMessage().contains("missing required index"));
            assertEquals(0, scalarLong(target, "SELECT COUNT(*) FROM rpg_accounts"));
        }
    }

    @Test
    void failedReplaceRollsBackPreviouslyExistingTargetRows() throws Exception {
        try (Connection source = h2("rollback-source"); Connection target = h2("rollback-target")) {
            createSchema(source, "PUBLIC", false);
            createSchema(target, "PUBLIC", false);
            seedSource(source);
            execute(target, "ALTER TABLE rpg_accounts ADD CONSTRAINT balance_guard CHECK (balance < 100)");
            execute(target, "INSERT INTO rpg_accounts VALUES ('other', 9.00, TIMESTAMP '2026-01-01 00:00:00')");

            assertThrows(
                    MigrationException.class,
                    () -> engine().run(source, target, request(false, false, true))
            );
            assertEquals(1, scalarLong(target, "SELECT COUNT(*) FROM rpg_accounts"));
            assertEquals(new BigDecimal("9.00"), scalarDecimal(target, "SELECT balance FROM rpg_accounts"));
        }
    }

    static Connection h2(String label) throws SQLException {
        String name = label.replace('-', '_') + "_" + UUID.randomUUID().toString().replace("-", "");
        return DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""
        );
    }

    static void createSchema(Connection connection, String schema, boolean narrowBalance) throws SQLException {
        String prefix = schema.equalsIgnoreCase("PUBLIC") ? "" : SchemaInspector.quote(schema) + ".";
        execute(connection, """
                CREATE TABLE %srpg_accounts (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    balance DECIMAL(%s, 2) NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """.formatted(prefix, narrowBalance ? "10" : "19"));
        execute(connection, """
                CREATE TABLE %srpg_ledger (
                    id VARCHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                    sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36),
                    amount DECIMAL(19, 2) NOT NULL, fee DECIMAL(19, 2) NOT NULL,
                    reason VARCHAR(128) NOT NULL, reversal_of VARCHAR(96),
                    created_at TIMESTAMP NOT NULL
                )
                """.formatted(prefix));
        execute(connection, "CREATE UNIQUE INDEX " + SchemaInspector.quote("uq_test_reversal_" + randomSuffix())
                + " ON " + prefix + "rpg_ledger (reversal_of)");
        execute(connection, """
                CREATE TABLE %srpg_market_deliveries (
                    id VARCHAR(36) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    item_payload TEXT NOT NULL,
                    source VARCHAR(96) NOT NULL,
                    state VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    delivered_at TIMESTAMP NULL
                )
                """.formatted(prefix));
        execute(connection, "CREATE INDEX idx_test_delivery_player_state ON "
                + prefix + "rpg_market_deliveries (player_uuid, state)");
    }

    static void seedSource(Connection source) throws SQLException {
        execute(source, "INSERT INTO rpg_accounts VALUES "
                + "('00000000-0000-0000-0000-000000000001', 1250.50, TIMESTAMP '2026-08-08 01:02:03.123456')");
        execute(source, """
                INSERT INTO rpg_ledger VALUES
                  ('10000000-0000-0000-0000-000000000001', 'seed-1', NULL,
                   '00000000-0000-0000-0000-000000000001', 1000.00, 0.00,
                   'seed', NULL, TIMESTAMP '2026-08-08 01:03:00.000001'),
                  ('10000000-0000-0000-0000-000000000002', 'market-1',
                   '00000000-0000-0000-0000-000000000001', NULL, 250.50, 25.05,
                   'market', 'seed-1', TIMESTAMP '2026-08-08 01:04:00.000002')
                """);
        execute(source, """
                INSERT INTO rpg_market_deliveries VALUES
                  ('20000000-0000-0000-0000-000000000001',
                   '00000000-0000-0000-0000-000000000001',
                   '{"item":"原创长剑","lore":"换行\\n精确保留"}',
                   'market:1', 'PENDING', TIMESTAMP '2026-08-08 01:05:00.000003', NULL)
                """);
    }

    static MigrationEngine engine() {
        return new MigrationEngine(TABLES, UNIQUE_KEYS, REQUIRED_INDEXES, ignored -> { });
    }

    static MigrationEngine.Request request(boolean dryRun, boolean verifyOnly, boolean replace) {
        return new MigrationEngine.Request("PUBLIC", "PUBLIC", 2, dryRun, verifyOnly, replace);
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static BigDecimal scalarDecimal(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getBigDecimal(1);
        }
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
