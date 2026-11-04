package cn.originalrpg.core.persistence;

import org.bukkit.configuration.file.YamlConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

/**
 * Phase 6 tests use the production migrations on H2 or a fresh, private PostgreSQL schema.
 * Only SIVITA_COMMUNITY_TEST_POSTGRES_* may select the optional test database; runtime
 * RPG_DATABASE_* overrides are rejected before any connection or schema is created.
 */
final class CommunityDatabaseFixture implements AutoCloseable {
    final Database database;
    final DataSource dataSource;
    final boolean postgres;
    private final String url;
    private final String user;
    private final String password;
    private final String schema;
    private boolean schemaCreated;

    CommunityDatabaseFixture() throws Exception {
        for (String variable : new String[]{"RPG_DATABASE_URL", "RPG_DATABASE_USER", "RPG_DATABASE_PASSWORD"}) {
            String override = System.getenv(variable);
            if (override != null && !override.isBlank()) {
                throw new IllegalStateException("Community tests refuse " + variable
                        + "; configure only the isolated SIVITA_COMMUNITY_TEST_POSTGRES_* variables");
            }
        }
        url = System.getenv("SIVITA_COMMUNITY_TEST_POSTGRES_URL");
        user = System.getenv().getOrDefault("SIVITA_COMMUNITY_TEST_POSTGRES_USER", "postgres");
        password = System.getenv().getOrDefault("SIVITA_COMMUNITY_TEST_POSTGRES_PASSWORD", "");
        postgres = url != null && !url.isBlank();
        schema = postgres ? "sivita_community_test_" + UUID.randomUUID().toString().replace("-", "") : null;
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", "jdbc:h2:mem:community_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        config.set("database.maximum-pool-size", 16);
        if (postgres) {
            if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase(Locale.ROOT).contains("currentschema=")) {
                throw new IllegalArgumentException("Community PostgreSQL test URL must be JDBC PostgreSQL without currentSchema");
            }
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                schemaCreated = true;
            }
            config.set("database.jdbc-url", url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            config.set("database.username", user);
            config.set("database.password", password);
        }
        Database opened = null;
        try {
            opened = Database.open(config);
            if (postgres) {
                try (Connection connection = opened.dataSource().getConnection(); var statement = connection.createStatement();
                     var row = statement.executeQuery("SELECT current_schema()")) {
                    if (!row.next() || !schema.equals(row.getString(1))) {
                        throw new SQLException("Community test pool did not select its newly created private schema");
                    }
                }
            }
            opened.migrate();
            database = opened;
            dataSource = opened.dataSource();
        } catch (Exception failure) {
            if (opened != null) opened.close();
            try { dropOwnSchema(); }
            catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void dropOwnSchema() throws SQLException {
        if (!schemaCreated) return;
        if (schema == null || !schema.matches("sivita_community_test_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to clean an unowned community test schema");
        }
        try (Connection connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
            schemaCreated = false;
        }
    }

    @Override public void close() throws SQLException {
        try { database.close(); }
        finally { dropOwnSchema(); }
    }
}
