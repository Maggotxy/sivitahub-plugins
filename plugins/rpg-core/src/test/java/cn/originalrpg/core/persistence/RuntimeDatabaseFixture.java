package cn.originalrpg.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/** Uses the complete production migration without reading deployment database environment variables. */
final class RuntimeDatabaseFixture implements AutoCloseable {
    final HikariDataSource dataSource;
    final Database database;

    RuntimeDatabaseFixture() throws ReflectiveOperationException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:runtime_schema_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        try {
            var constructor = Database.class.getDeclaredConstructor(HikariDataSource.class,
                    HikariDataSource.class, boolean.class, boolean.class, long.class);
            constructor.setAccessible(true);
            // Only migrate() is called. A node lease is deliberately not acquired by this fixture.
            database = constructor.newInstance(dataSource, dataSource, true, false, 30_000L);
        } catch (ReflectiveOperationException failure) {
            dataSource.close();
            throw failure;
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

    @Override
    public void close() {
        dataSource.close();
    }
}
