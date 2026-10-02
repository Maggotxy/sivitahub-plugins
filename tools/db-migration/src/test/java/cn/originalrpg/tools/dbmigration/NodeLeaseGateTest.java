package cn.originalrpg.tools.dbmigration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeLeaseGateTest {
    private static final List<String> TABLES = List.of("rpg_node_leases");

    @Test
    void activeSourceLeaseBlocksMigration() throws Exception {
        try (Connection source = MigrationEngineTest.h2("lease-source");
             Connection target = MigrationEngineTest.h2("lease-target")) {
            create(source);
            create(target);
            insertLease(source, Instant.now().plusSeconds(300));

            MigrationException error = assertThrows(
                    MigrationException.class,
                    () -> engine().run(source, target, request())
            );
            assertTrue(error.getMessage().contains("Source reports 1 active"));
        }
    }

    @Test
    void expiredLeaseCanBeCopiedAndVerified() throws Exception {
        try (Connection source = MigrationEngineTest.h2("expired-source");
             Connection target = MigrationEngineTest.h2("expired-target")) {
            create(source);
            create(target);
            insertLease(source, Instant.now().minusSeconds(300));

            MigrationEngine.Result result = engine().run(source, target, request());
            assertEquals(MigrationEngine.Outcome.MIGRATED, result.outcome());
        }
    }

    private static MigrationEngine engine() {
        return new MigrationEngine(TABLES, Map.of(), ignored -> { });
    }

    private static MigrationEngine.Request request() {
        return new MigrationEngine.Request("PUBLIC", "PUBLIC", 10, false, false, false);
    }

    private static void create(Connection connection) throws Exception {
        MigrationEngineTest.execute(connection, """
                CREATE TABLE rpg_node_leases (
                    node_id VARCHAR(64) PRIMARY KEY,
                    process_token VARCHAR(36) NOT NULL,
                    lease_expires_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
    }

    private static void insertLease(Connection connection, Instant expiry) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rpg_node_leases VALUES (?, ?, ?, ?)"
        )) {
            statement.setString(1, "paper-test");
            statement.setString(2, "00000000-0000-0000-0000-000000000001");
            statement.setTimestamp(3, Timestamp.from(expiry));
            statement.setTimestamp(4, Timestamp.from(Instant.now().minusSeconds(600)));
            statement.executeUpdate();
        }
    }
}
