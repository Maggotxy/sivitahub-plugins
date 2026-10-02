package cn.originalrpg.core.persistence;

import cn.originalrpg.api.service.EquipmentLedgerAuditService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class JdbcEquipmentLedgerAuditServiceTest {
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcEquipmentLedgerAuditService service;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:ledger-audit-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        executor = Executors.newFixedThreadPool(2);
        service = new JdbcEquipmentLedgerAuditService(dataSource, executor);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_equipment_states (
                      item_id VARCHAR(36) PRIMARY KEY, state VARCHAR(24) NOT NULL,
                      owner_uuid VARCHAR(36), last_operation VARCHAR(24),
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_equipment_lifecycle_audit (
                      id VARCHAR(36) PRIMARY KEY, item_id VARCHAR(36) NOT NULL,
                      action VARCHAR(16) NOT NULL, from_state VARCHAR(24) NOT NULL,
                      to_state VARCHAR(24) NOT NULL, actor VARCHAR(64) NOT NULL,
                      note VARCHAR(500) NOT NULL,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
        }
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void freezeKeepsOwnerAndThawReturnsTheIdentityToCirculation() throws Exception {
        UUID item = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        insert(item, "UNBOUND", owner, "MARKET_RETURN");

        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.APPLIED,
                service.quarantine(item, "risk-bot", "duplicate-identity report 4711")
                        .toCompletableFuture().get().status());
        assertEquals("QUARANTINED", state(item));
        // C-013: the freeze never rewrites custody, so restore has someone to
        // hand the identity back to.
        assertEquals(owner.toString(), scalar(
                "SELECT owner_uuid FROM rpg_equipment_states WHERE item_id = '" + item + "'"));

        var restored = service.restore(item, "admin", "verified with player, item is legitimate")
                .toCompletableFuture().get();
        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.APPLIED, restored.status());
        assertEquals("RESTORED", restored.state());
        assertEquals("RESTORED", state(item));

        List<EquipmentLedgerAuditService.AuditRecord> trail =
                service.auditTrail(item, 20).toCompletableFuture().get();
        assertEquals(2, trail.size());
        // Newest first: the thaw, then the freeze that preceded it.
        assertEquals("RESTORE", trail.get(0).action());
        assertEquals("QUARANTINED", trail.get(0).fromState());
        assertEquals("admin", trail.get(0).actor());
        assertEquals("verified with player, item is legitimate", trail.get(0).note());
        assertEquals("QUARANTINE", trail.get(1).action());
        assertEquals("UNBOUND", trail.get(1).fromState());
    }

    @Test
    void everyRefusalWritesNeitherStateNorAuditTrail() throws Exception {
        UUID frozen = insert(UUID.randomUUID(), "QUARANTINED", UUID.randomUUID(), "MARKET_QUARANTINE");
        UUID terminal = insert(UUID.randomUUID(), "SALVAGED", null, "SALVAGE");
        UUID live = insert(UUID.randomUUID(), "BOUND", null, "REFINEMENT");
        UUID missing = UUID.randomUUID();
        assertEquals("SALVAGED", state(terminal));

        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.NOT_FROZEN,
                service.restore(live, "admin", "wrong target").toCompletableFuture().get().status());
        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.TERMINAL,
                service.quarantine(terminal, "admin", "spent item").toCompletableFuture().get().status());
        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.TERMINAL,
                service.restore(terminal, "admin", "spent item").toCompletableFuture().get().status());
        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.NOT_FOUND,
                service.quarantine(missing, "admin", "never registered").toCompletableFuture().get().status());
        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.ALREADY_IN_STATE,
                service.quarantine(frozen, "admin", "double freeze").toCompletableFuture().get().status());
        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.INVALID_NOTE,
                service.quarantine(live, "admin", "   ").toCompletableFuture().get().status());
        assertEquals(EquipmentLedgerAuditService.AuditResult.Status.INVALID_NOTE,
                service.restore(live, "admin", "x".repeat(501)).toCompletableFuture().get().status());

        assertEquals("BOUND", state(live));
        assertEquals("SALVAGED", state(terminal));
        assertEquals("QUARANTINED", state(frozen));
        // A refused decision must not pollute the paper trail either.
        assertEquals(0, count("rpg_equipment_lifecycle_audit"));
        assertTrue(service.auditTrail(live, 20).toCompletableFuture().get().isEmpty());
    }

    @Test
    void watchlistShowsFrozenAndTerminalIdentitiesNewestFirst() throws Exception {
        insert(UUID.randomUUID(), "UNBOUND", null, "MARKET_RETURN");
        insert(UUID.randomUUID(), "BOUND", null, "REFINEMENT");
        UUID frozen = insert(UUID.randomUUID(), "QUARANTINED", null, "MARKET_QUARANTINE");
        UUID spent = insert(UUID.randomUUID(), "DESTROYED", null, "DESTROY");
        service.quarantine(insert(UUID.randomUUID(), "LISTED", null, "MARKET_PREPARE"), "admin", "noted")
                .toCompletableFuture().get();

        List<EquipmentLedgerAuditService.Entry> entries = service.watchlist(10).toCompletableFuture().get();
        assertEquals(3, entries.size());
        assertTrue(entries.stream().anyMatch(entry -> entry.itemId().equals(frozen)));
        assertTrue(entries.stream().anyMatch(entry -> entry.itemId().equals(spent)));
        // Bounded so a huge ledger cannot flood the console.
        assertEquals(1, service.watchlist(1).toCompletableFuture().get().size());
        assertTrue(service.watchlist(0).toCompletableFuture().get().isEmpty());
    }

    @Test
    void entryReadsTheRawRowAndDistinguishesAnUnregisteredIdentity() throws Exception {
        UUID item = insert(UUID.randomUUID(), "LISTED", UUID.randomUUID(), "MARKET_PREPARE");
        Optional<EquipmentLedgerAuditService.Entry> entry =
                service.entry(item).toCompletableFuture().get();
        assertTrue(entry.isPresent());
        assertEquals("LISTED", entry.orElseThrow().state());
        assertEquals("MARKET_PREPARE", entry.orElseThrow().lastOperation());
        assertTrue(service.entry(UUID.randomUUID()).toCompletableFuture().get().isEmpty());
        assertTrue(service.entry(null).toCompletableFuture().get().isEmpty());
    }

    private UUID insert(UUID item, String state, UUID owner, String operation) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO rpg_equipment_states (item_id, state, owner_uuid, last_operation) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, item.toString());
            insert.setString(2, state);
            if (owner == null) insert.setNull(3, java.sql.Types.VARCHAR);
            else insert.setString(3, owner.toString());
            insert.setString(4, operation);
            insert.executeUpdate();
        }
        return item;
    }

    private String state(UUID item) throws SQLException {
        return scalar("SELECT state FROM rpg_equipment_states WHERE item_id = '" + item + "'");
    }

    private int count(String table) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement query = connection.createStatement();
             ResultSet result = query.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
