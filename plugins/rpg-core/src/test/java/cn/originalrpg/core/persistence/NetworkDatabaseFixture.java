package cn.originalrpg.core.persistence;

import org.bukkit.configuration.file.YamlConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Runs the same network authority tests on production DDL in H2 or an isolated PostgreSQL schema. */
final class NetworkDatabaseFixture implements AutoCloseable {
    final Database database;
    final DataSource dataSource;
    final boolean postgres;
    private final String url;
    private final String user;
    private final String password;
    private String schema;

    NetworkDatabaseFixture() throws Exception {
        String runtimeOverride = System.getenv("RPG_DATABASE_URL");
        if (runtimeOverride != null && !runtimeOverride.isBlank()) throw new IllegalStateException(
                "Network tests refuse RPG_DATABASE_URL; use the isolated SIVITA_NETWORK_TEST_POSTGRES_URL");
        url = System.getenv("SIVITA_NETWORK_TEST_POSTGRES_URL");
        user = System.getenv().getOrDefault("SIVITA_NETWORK_TEST_POSTGRES_USER", "postgres");
        password = System.getenv().getOrDefault("SIVITA_NETWORK_TEST_POSTGRES_PASSWORD", "");
        postgres = url != null && !url.isBlank();
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.jdbc-url", "jdbc:h2:mem:network_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        if (postgres) {
            schema = "sivita_network_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection connection = DriverManager.getConnection(url, user, password);
                 Statement statement = connection.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
            config.set("database.jdbc-url", url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            config.set("database.username", user);
            config.set("database.password", password);
        }
        config.set("database.maximum-pool-size", 8);
        Database opened = null;
        try {
            opened = Database.open(config);
            opened.migrate();
            database = opened;
            dataSource = opened.dataSource();
        } catch (Exception error) {
            if (opened != null) opened.close();
            dropSchema();
            throw error;
        }
    }

    long count(String table) throws SQLException {
        if (!table.matches("rpg_[a-z_]+")) throw new IllegalArgumentException("Invalid test table name");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private void dropSchema() throws SQLException {
        if (schema == null) return;
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        schema = null;
    }

    @Override public void close() throws SQLException {
        database.close();
        dropSchema();
    }
}
