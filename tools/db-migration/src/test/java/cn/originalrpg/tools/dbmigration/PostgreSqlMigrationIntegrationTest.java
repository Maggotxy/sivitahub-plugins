package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgreSqlMigrationIntegrationTest {
    @Test
    void copiesH2ToRealPostgreSqlWhenConditionalDatabaseIsProvided() throws Exception {
        String url = System.getenv("SIVITA_MIGRATION_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set SIVITA_MIGRATION_TEST_POSTGRES_URL to run");
        String user = System.getenv().getOrDefault("SIVITA_MIGRATION_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("SIVITA_MIGRATION_TEST_POSTGRES_PASSWORD", "");
        String schema = "sivita_migration_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        try {
            try (Connection admin = postgres(url, user, password)) {
                MigrationEngineTest.execute(admin, "CREATE SCHEMA " + SchemaInspector.quote(schema));
                MigrationEngineTest.createSchema(admin, schema, false);
            }
            try (
                    Connection source = MigrationEngineTest.h2("pg-source");
                    Connection target = postgres(url, user, password)
            ) {
                MigrationEngineTest.createSchema(source, "PUBLIC", false);
                MigrationEngineTest.seedSource(source);
                MigrationEngine engine = new MigrationEngine(
                        MigrationEngineTest.TABLES,
                        MigrationEngineTest.UNIQUE_KEYS,
                        MigrationEngineTest.REQUIRED_INDEXES,
                        ignored -> { }
                );
                MigrationEngine.Result result = engine.run(source, target, new MigrationEngine.Request(
                        "PUBLIC", schema, 2, false, false, false
                ));
                assertEquals(MigrationEngine.Outcome.MIGRATED, result.outcome());
                assertEquals(4, result.rows());
            }
        } finally {
            try (Connection admin = postgres(url, user, password); Statement statement = admin.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + SchemaInspector.quote(schema) + " CASCADE");
            }
        }
    }

    private static Connection postgres(String url, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", "sivita-migration-integration-test");
        return DriverManager.getConnection(url, properties);
    }
}
