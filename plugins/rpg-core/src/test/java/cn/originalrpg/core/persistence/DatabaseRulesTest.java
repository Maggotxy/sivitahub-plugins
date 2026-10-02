package cn.originalrpg.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseRulesTest {
    @Test
    void selectsExplicitDriversForSupportedPaperPluginLibraries() {
        assertEquals("org.h2.Driver", Database.driverClassFor("jdbc:h2:file:./data/rpg"));
        assertEquals("org.postgresql.Driver", Database.driverClassFor("jdbc:postgresql://localhost/rpg"));
        assertNull(Database.driverClassFor("jdbc:custom:example"));
        assertNull(Database.driverClassFor(null));
    }

    @Test
    void nodeLeasePoolIsASeparateSingleConnectionAuthority() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.username", "sa");
        config.set("database.password", "");
        config.set("database.maximum-pool-size", 6);
        config.set("database.connection-timeout-ms", 5_000L);
        config.set("database.validation-timeout-ms", 2_000L);
        config.set("database.idle-timeout-ms", 600_000L);
        config.set("database.max-lifetime-ms", 1_800_000L);
        config.set("database.leak-detection-threshold-ms", 15_000L);
        String jdbcUrl = "jdbc:h2:mem:pool-contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";

        HikariConfig gameplay = Database.hikariConfig(config, jdbcUrl, "rpg-database", 6);
        HikariConfig lease = Database.hikariConfig(config, jdbcUrl, "rpg-node-lease-database", 1);

        assertEquals("rpg-database", gameplay.getPoolName());
        assertEquals(6, gameplay.getMaximumPoolSize());
        assertEquals("rpg-node-lease-database", lease.getPoolName());
        assertEquals(1, lease.getMaximumPoolSize());
        assertEquals(jdbcUrl, lease.getJdbcUrl());
        assertFalse(gameplay == lease);
    }

    @Test
    void serializesPostgresMigrationsBeforeAnySchemaStatement() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(null);

        Database.executeMigrations(jdbc.connection(), List.of("CREATE TABLE example (id INTEGER)"), true, 30_000L);

        assertEquals(1, jdbc.commits.get());
        assertEquals(0, jdbc.rollbacks.get());
        assertEquals(3, jdbc.statements.size());
        assertEquals("SET LOCAL lock_timeout = '30000ms'", jdbc.statements.get(0));
        assertTrue(jdbc.statements.get(1).startsWith("SELECT pg_advisory_xact_lock("));
        assertEquals("CREATE TABLE example (id INTEGER)", jdbc.statements.get(2));
    }

    @Test
    void keepsLocalH2MigrationFreeOfPostgresCoordinationSql() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc(null);

        Database.executeMigrations(jdbc.connection(), List.of("CREATE TABLE local_only (id INTEGER)"), false, 30_000L);

        assertArrayEquals(new String[]{"CREATE TABLE local_only (id INTEGER)"}, jdbc.statements.toArray(String[]::new));
        assertEquals(1, jdbc.commits.get());
    }

    @Test
    void rollsBackAndBoundsMigrationLockWaits() {
        List<String> lowerBound = Database.migrationCoordinationStatements(true, 1L);
        List<String> upperBound = Database.migrationCoordinationStatements(true, Long.MAX_VALUE);
        assertEquals("SET LOCAL lock_timeout = '1000ms'", lowerBound.getFirst());
        assertEquals("SET LOCAL lock_timeout = '300000ms'", upperBound.getFirst());

        RecordingJdbc jdbc = new RecordingJdbc("BROKEN DDL");
        assertThrows(SQLException.class, () -> Database.executeMigrations(
                jdbc.connection(), List.of("SAFE DDL", "BROKEN DDL", "NEVER RUN"), true, 30_000L));
        assertEquals(0, jdbc.commits.get());
        assertEquals(1, jdbc.rollbacks.get());
        assertTrue(jdbc.statements.contains("BROKEN DDL"));
        assertFalse(jdbc.statements.contains("NEVER RUN"));
    }

    private static final class RecordingJdbc {
        private final List<String> statements = new ArrayList<>();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();
        private final String failingSql;

        private RecordingJdbc(String failingSql) {
            this.failingSql = failingSql;
        }

        private Connection connection() {
            Statement statement = (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("execute")) {
                            String sql = (String) arguments[0];
                            statements.add(sql);
                            if (sql.equals(failingSql)) throw new SQLException("injected migration failure");
                            return true;
                        }
                        return defaultValue(method.getReturnType());
                    });
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "createStatement" -> statement;
                            case "commit" -> {
                                commits.incrementAndGet();
                                yield null;
                            }
                            case "rollback" -> {
                                rollbacks.incrementAndGet();
                                yield null;
                            }
                            default -> defaultValue(method.getReturnType());
                        };
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0.0F;
            if (type == double.class) return 0.0D;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
