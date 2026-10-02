package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
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

class JdbcEquipmentOperationServiceTest {
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcEquipmentOperationService service;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:equipment-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        executor = Executors.newFixedThreadPool(4);
        service = new JdbcEquipmentOperationService(dataSource, executor, new BigDecimal("1000.00"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_accounts (
                      player_uuid VARCHAR(36) PRIMARY KEY, balance DECIMAL(19,2) NOT NULL,
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_ledger (
                      id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                      sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36), amount DECIMAL(19,2) NOT NULL,
                      fee DECIMAL(19,2) NOT NULL, reason VARCHAR(128) NOT NULL,
                      reversal_of VARCHAR(96), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_deliveries (
                      id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                      item_payload TEXT NOT NULL, source VARCHAR(96) NOT NULL, state VARCHAR(16) NOT NULL,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP NULL)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_equipment_operations (
                      operation_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                      kind VARCHAR(24) NOT NULL, item_id VARCHAR(36), input_fingerprint VARCHAR(64) NOT NULL,
                      request_payload TEXT NOT NULL, gold_cost DECIMAL(19,2) NOT NULL,
                      debit_key VARCHAR(96) UNIQUE NOT NULL, state VARCHAR(40) NOT NULL,
                      queued_deliveries INTEGER NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      settled_at TIMESTAMP NULL)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_equipment_states (
                      item_id VARCHAR(36) PRIMARY KEY, state VARCHAR(24) NOT NULL,
                      owner_uuid VARCHAR(36), last_operation VARCHAR(24),
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
        }
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void commitsDebitReceiptAndResultDeliveryAtomicallyAndReplaysExactlyOnce() throws Exception {
        EquipmentOperationRequest request = request(UUID.randomUUID(), new BigDecimal("250.00"));

        EquipmentOperationResult first = service.settle(request).toCompletableFuture().get();
        EquipmentOperationResult replay = service.settle(request).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.COMMITTED, first.status());
        assertFalse(first.replayed());
        assertEquals(EquipmentOperationResult.Status.COMMITTED, replay.status());
        assertTrue(replay.replayed());
        assertEquals(new BigDecimal("750.00"), balance(request.playerId()));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(1, count("rpg_equipment_operations"));
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals("result-item", scalar("SELECT item_payload FROM rpg_market_deliveries"));
    }

    @Test
    void concurrentDoubleClickChargesAndQueuesOnlyOnce() throws Exception {
        EquipmentOperationRequest request = request(UUID.randomUUID(), new BigDecimal("250.00"));

        var left = service.settle(request).toCompletableFuture();
        var right = service.settle(request).toCompletableFuture();
        EquipmentOperationResult a = left.get();
        EquipmentOperationResult b = right.get();

        assertEquals(EquipmentOperationResult.Status.COMMITTED, a.status());
        assertEquals(EquipmentOperationResult.Status.COMMITTED, b.status());
        assertTrue(a.replayed() ^ b.replayed());
        assertEquals(new BigDecimal("750.00"), balance(request.playerId()));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(1, count("rpg_market_deliveries"));
    }

    @Test
    void insufficientFundsReturnsExactEscrowAssetsWithoutCreatingDebit() throws Exception {
        EquipmentOperationRequest request = request(UUID.randomUUID(), new BigDecimal("1250.00"));

        EquipmentOperationResult result = service.settle(request).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.REJECTED_INSUFFICIENT_FUNDS, result.status());
        assertEquals(new BigDecimal("1000.00"), balance(request.playerId()));
        assertEquals(0, count("rpg_ledger"));
        assertEquals(2, count("rpg_market_deliveries"));
        assertEquals(List.of("original-item", "protection-stone"), payloads());
    }

    @Test
    void injectedDeliveryFailureRollsBackDebitReceiptAndPartialOutbox() throws Exception {
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                UUID.randomUUID(), UUID.randomUUID(), EquipmentOperationKind.FORGE, Optional.empty(),
                "a".repeat(64), "forge", new BigDecimal("250.00"), "forge:" + UUID.randomUUID(),
                List.of("result-one", "result-two"), List.of("base"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE rpg_market_deliveries ADD CONSTRAINT fail_second CHECK (item_payload <> 'result-two')");
        }

        assertThrows(Exception.class, () -> service.settle(request).toCompletableFuture().get());
        assertNull(optionalBalance(request.playerId()), "account creation is rolled back with the failed operation");
        assertEquals(0, count("rpg_ledger"));
        assertEquals(0, count("rpg_equipment_operations"));
        assertEquals(0, count("rpg_market_deliveries"));
    }

    @Test
    void sameOperationIdWithChangedPayloadIsAConflictAndCannotAlterAssets() throws Exception {
        UUID operation = UUID.randomUUID();
        EquipmentOperationRequest original = request(operation, new BigDecimal("250.00"));
        service.settle(original).toCompletableFuture().get();
        EquipmentOperationRequest tampered = new EquipmentOperationRequest(
                operation, original.playerId(), original.kind(), original.itemId(),
                original.inputFingerprint(), "different-slot", original.goldCost(), original.debitKey(),
                List.of("duplicated-result"), original.rejectedDeliveries());

        EquipmentOperationResult result = service.settle(tampered).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.CONFLICT, result.status());
        assertEquals(new BigDecimal("750.00"), balance(original.playerId()));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(1, count("rpg_market_deliveries"));
    }

    @Test
    void legacyCompatibleOperationAdoptsItsSeededStateAndCommits() throws Exception {
        EquipmentOperationRequest request = request(UUID.randomUUID(), new BigDecimal("250.00"));

        EquipmentOperationResult result = service.settle(request).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.COMMITTED, result.status());
        assertEquals("BOUND", lifecycleState(request.itemId().orElseThrow()));
        assertEquals(request.playerId().toString(), lifecycleOwner(request.itemId().orElseThrow()));
    }

    @Test
    void operationOnEscrowedIdentityIsRefusedWithoutChargeAndReplaysAsRefusal() throws Exception {
        EquipmentOperationRequest request = request(UUID.randomUUID(), new BigDecimal("250.00"));
        UUID item = request.itemId().orElseThrow();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO rpg_equipment_states (item_id, state, owner_uuid) VALUES (?, 'LISTED', ?)")) {
            insert.setString(1, item.toString());
            insert.setString(2, UUID.randomUUID().toString());
            insert.executeUpdate();
            connection.commit();
        }

        EquipmentOperationResult refused = service.settle(request).toCompletableFuture().get();
        EquipmentOperationResult replay = service.settle(request).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED, refused.status());
        assertTrue(replay.replayed());
        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED,
                EquipmentOperationResult.Status.valueOf(scalar(
                        "SELECT state FROM rpg_equipment_operations WHERE operation_id = '" + request.operationId() + "'")));
        assertEquals(new BigDecimal("1000.00"), balance(request.playerId()));
        assertEquals(0, count("rpg_ledger"));
        // The escrowed assets return through the recovery lane (base + one aid).
        assertEquals(2, count("rpg_market_deliveries"));
        assertEquals("LISTED", lifecycleState(item));
    }

    @Test
    void operationOnTerminalIdentityIsRefused() throws Exception {
        EquipmentOperationRequest request = request(UUID.randomUUID(), new BigDecimal("250.00"));
        UUID item = request.itemId().orElseThrow();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO rpg_equipment_states (item_id, state, owner_uuid) VALUES (?, 'SALVAGED', ?)")) {
            insert.setString(1, item.toString());
            insert.setString(2, request.playerId().toString());
            insert.executeUpdate();
            connection.commit();
        }

        EquipmentOperationResult result = service.settle(request).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED, result.status());
        assertEquals(new BigDecimal("1000.00"), balance(request.playerId()));
        assertEquals("SALVAGED", lifecycleState(item));
    }

    @Test
    void paidUnbindToleratesDriftedUnboundRowAndRejectsUnownedStates() throws Exception {
        // Combat binding mutates only the PDC, so an UNBOUND row can legitimately
        // lag a re-bound item (e.g. after a market return). The lifecycle allows
        // the self-transition; the command-side owner check refuses the fee when
        // the PDC has no owner, so nothing can be charged twice for nothing.
        EquipmentOperationRequest drifted = new EquipmentOperationRequest(
                UUID.randomUUID(), UUID.randomUUID(), EquipmentOperationKind.UNBIND,
                Optional.of(UUID.randomUUID()), "a".repeat(64), "unbind",
                new BigDecimal("250.00"), "unbind:" + UUID.randomUUID(),
                List.of("unbound-item"), List.of("original-item"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO rpg_equipment_states (item_id, state, owner_uuid) VALUES (?, 'UNBOUND', ?)")) {
            insert.setString(1, drifted.itemId().orElseThrow().toString());
            insert.setString(2, drifted.playerId().toString());
            insert.executeUpdate();
        }
        assertEquals(EquipmentOperationResult.Status.COMMITTED,
                service.settle(drifted).toCompletableFuture().get().status());
        assertEquals("UNBOUND", lifecycleState(drifted.itemId().orElseThrow()));

        // CREATED gear (never bound, forge-seeded row) still cannot pay an unbind.
        EquipmentOperationRequest created = new EquipmentOperationRequest(
                UUID.randomUUID(), drifted.playerId(), EquipmentOperationKind.UNBIND,
                Optional.of(UUID.randomUUID()), "a".repeat(64), "unbind",
                new BigDecimal("250.00"), "unbind:" + UUID.randomUUID(),
                List.of("unbound-item"), List.of("original-item"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO rpg_equipment_states (item_id, state, owner_uuid) VALUES (?, 'CREATED', ?)")) {
            insert.setString(1, created.itemId().orElseThrow().toString());
            insert.setString(2, created.playerId().toString());
            insert.executeUpdate();
        }
        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED,
                service.settle(created).toCompletableFuture().get().status());
        assertEquals("CREATED", lifecycleState(created.itemId().orElseThrow()));

        // Unregistered gear adopts the bound seed the command side already
        // enforces, so the legacy-compatible unbind commits and lands UNBOUND.
        EquipmentOperationRequest legacyCompatible = new EquipmentOperationRequest(
                UUID.randomUUID(), drifted.playerId(), EquipmentOperationKind.UNBIND,
                Optional.of(UUID.randomUUID()), "a".repeat(64), "unbind",
                new BigDecimal("250.00"), "unbind:" + UUID.randomUUID(),
                List.of("unbound-item"), List.of("original-item"));
        assertEquals(EquipmentOperationResult.Status.COMMITTED,
                service.settle(legacyCompatible).toCompletableFuture().get().status());
        assertEquals("UNBOUND", lifecycleState(legacyCompatible.itemId().orElseThrow()));
    }

    private String lifecycleState(UUID item) throws SQLException {
        return scalar("SELECT state FROM rpg_equipment_states WHERE item_id = '" + item + "'");
    }

    private String lifecycleOwner(UUID item) throws SQLException {
        return scalar("SELECT owner_uuid FROM rpg_equipment_states WHERE item_id = '" + item + "'");
    }

    private EquipmentOperationRequest request(UUID operationId, BigDecimal cost) {
        UUID player = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        List<String> originals = List.of("original-item", "protection-stone");
        String requestPayload = "slot=2;safe=true;lucky=false";
        return new EquipmentOperationRequest(operationId, player, EquipmentOperationKind.REFINEMENT,
                Optional.of(item), EquipmentOperationEnvelope.fingerprint(requestPayload, originals),
                requestPayload, cost, "refine:" + operationId,
                List.of("result-item"), originals);
    }

    private BigDecimal balance(UUID player) throws SQLException {
        BigDecimal balance = optionalBalance(player);
        assertNotNull(balance);
        return balance;
    }

    private BigDecimal optionalBalance(UUID player) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ?")) {
            query.setString(1, player.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getBigDecimal(1).setScale(2) : null;
            }
        }
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

    private List<String> payloads() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT item_payload FROM rpg_market_deliveries ORDER BY id")) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (result.next()) values.add(result.getString(1));
            values.sort(String::compareTo);
            return List.copyOf(values);
        }
    }
}
