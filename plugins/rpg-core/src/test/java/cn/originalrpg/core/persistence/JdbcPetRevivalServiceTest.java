package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PetRevivalRequest;
import cn.originalrpg.api.model.PetRevivalResult;
import cn.originalrpg.api.model.PetRevivalIntent;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class JdbcPetRevivalServiceTest {
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcPetRevivalService service;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:pet-revival-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        executor = Executors.newFixedThreadPool(8);
        service = new JdbcPetRevivalService(dataSource, executor, new BigDecimal("1000.00"));
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
            statement.execute("CREATE UNIQUE INDEX uq_test_pet_revival_reversal ON rpg_ledger (reversal_of)");
            statement.execute("""
                    CREATE TABLE rpg_pet_revival_intents (
                      operation_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                      pet_uuid VARCHAR(36) NOT NULL, active_pet_uuid VARCHAR(36) UNIQUE,
                      gold_cost DECIMAL(19,2) NOT NULL, debit_key VARCHAR(96) UNIQUE NOT NULL,
                      state VARCHAR(24) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      completed_at TIMESTAMP NULL, failure_reason VARCHAR(128))
                    """);
        }
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void beginDebitsAndPersistsIntentAtomicallyThenReplays() throws Exception {
        PetRevivalRequest request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "250.00");

        PetRevivalResult first = service.begin(request).toCompletableFuture().get();
        PetRevivalResult replay = service.begin(request).toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.STARTED, first.status());
        assertEquals(PetRevivalResult.Status.PENDING, replay.status());
        assertEquals(new BigDecimal("750.00"), balance(request.playerId()));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(1, count("rpg_pet_revival_intents"));
        assertEquals(1, service.pending(request.playerId()).toCompletableFuture().get().size());
    }

    @Test
    void insufficientFundsLeavesNoIntentLedgerOrIdempotencyClaim() throws Exception {
        UUID player = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        PetRevivalRequest expensive = request(operation, player, pet, "1250.00");

        PetRevivalResult rejected = service.begin(expensive).toCompletableFuture().get();
        PetRevivalRequest affordable = request(operation, player, pet, "250.00");
        PetRevivalResult retry = service.begin(affordable).toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.INSUFFICIENT_FUNDS, rejected.status());
        assertEquals(PetRevivalResult.Status.STARTED, retry.status());
        assertEquals(new BigDecimal("750.00"), balance(player));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(1, count("rpg_pet_revival_intents"));
    }

    @Test
    void concurrentDifferentOperationsForSamePetOnlyDebitOnce() throws Exception {
        UUID player = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        PetRevivalRequest left = request(UUID.randomUUID(), player, pet, "250.00");
        PetRevivalRequest right = request(UUID.randomUUID(), player, pet, "250.00");

        var a = service.begin(left).toCompletableFuture();
        var b = service.begin(right).toCompletableFuture();
        PetRevivalResult first = a.get();
        PetRevivalResult second = b.get();

        assertTrue(first.status() == PetRevivalResult.Status.STARTED
                || second.status() == PetRevivalResult.Status.STARTED);
        assertTrue(first.status() == PetRevivalResult.Status.PENDING
                || second.status() == PetRevivalResult.Status.PENDING);
        assertEquals(new BigDecimal("750.00"), balance(player));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(1, count("rpg_pet_revival_intents"));
    }

    @Test
    void completionReleasesPetForASeparateFutureRevival() throws Exception {
        UUID player = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        PetRevivalRequest first = request(UUID.randomUUID(), player, pet, "100.00");
        service.begin(first).toCompletableFuture().get();

        PetRevivalResult completed = service.complete(first.operationId(), player, pet).toCompletableFuture().get();
        PetRevivalResult replay = service.complete(first.operationId(), player, pet).toCompletableFuture().get();
        PetRevivalRequest second = request(UUID.randomUUID(), player, pet, "100.00");
        PetRevivalResult restarted = service.begin(second).toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.COMPLETED, completed.status());
        assertEquals(PetRevivalResult.Status.COMPLETED, replay.status());
        assertEquals(PetRevivalResult.Status.STARTED, restarted.status());
        assertEquals(new BigDecimal("800.00"), balance(player));
        assertEquals(2, count("rpg_ledger"));
    }

    @Test
    void quarantineIsDurableAndPreventsUnreviewedSecondDebit() throws Exception {
        UUID player = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        PetRevivalRequest first = request(UUID.randomUUID(), player, pet, "100.00");
        service.begin(first).toCompletableFuture().get();

        PetRevivalResult quarantined = service.quarantine(
                first.operationId(), player, pet, "owner_mismatch").toCompletableFuture().get();
        PetRevivalResult second = service.begin(
                request(UUID.randomUUID(), player, pet, "100.00")).toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.QUARANTINED, quarantined.status());
        assertEquals(PetRevivalResult.Status.QUARANTINED, second.status());
        assertEquals(new BigDecimal("900.00"), balance(player));
        assertEquals(1, count("rpg_ledger"));
    }

    @Test
    void mismatchedCompletionCannotAlterIntent() throws Exception {
        PetRevivalRequest request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "100.00");
        service.begin(request).toCompletableFuture().get();

        PetRevivalResult result = service.complete(
                request.operationId(), UUID.randomUUID(), request.petId()).toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.CONFLICT, result.status());
        assertEquals(1, service.pending(request.playerId()).toCompletableFuture().get().size());
    }

    @Test
    void reviewQueueIsBoundedOrderedAndFindAuditsEveryState() throws Exception {
        UUID player = UUID.randomUUID();
        PetRevivalRequest first = request(UUID.randomUUID(), player, UUID.randomUUID(), "10.00");
        PetRevivalRequest second = request(UUID.randomUUID(), player, UUID.randomUUID(), "20.00");
        service.begin(first).toCompletableFuture().get();
        service.quarantine(first.operationId(), player, first.petId(), "sqlite_write_failed")
                .toCompletableFuture().get();
        Thread.sleep(5L);
        service.begin(second).toCompletableFuture().get();
        service.quarantine(second.operationId(), player, second.petId(), "owner_mismatch")
                .toCompletableFuture().get();

        var queue = service.reviewQueue(1).toCompletableFuture().get();

        assertEquals(1, queue.size());
        assertEquals(first.operationId(), queue.getFirst().operationId());
        assertEquals(PetRevivalIntent.State.QUARANTINED,
                service.find(second.operationId()).toCompletableFuture().get().orElseThrow().state());
        assertTrue(service.find(UUID.randomUUID()).toCompletableFuture().get().isEmpty());
        assertTrue(service.reviewQueue(0).toCompletableFuture().get().isEmpty());
    }

    @Test
    void retryReopensOnlyQuarantinedIntentWithoutASecondDebit() throws Exception {
        PetRevivalRequest request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "100.00");
        service.begin(request).toCompletableFuture().get();
        service.quarantine(request.operationId(), request.playerId(), request.petId(), "sqlite_write_failed")
                .toCompletableFuture().get();

        PetRevivalResult retried = service.retryQuarantined(request.operationId()).toCompletableFuture().get();
        PetRevivalResult replay = service.retryQuarantined(request.operationId()).toCompletableFuture().get();
        PetRevivalResult completed = service.complete(request.operationId(), request.playerId(), request.petId())
                .toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.PENDING, retried.status());
        assertEquals(PetRevivalResult.Status.PENDING, replay.status());
        assertEquals(PetRevivalResult.Status.COMPLETED, completed.status());
        assertEquals(new BigDecimal("900.00"), balance(request.playerId()));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(PetRevivalResult.Status.CONFLICT,
                service.retryQuarantined(request.operationId()).toCompletableFuture().get().status());
    }

    @Test
    void compensationRefundsOnceReleasesPetAndReplaysAfterAmbiguousResponse() throws Exception {
        UUID player = UUID.randomUUID();
        UUID pet = UUID.randomUUID();
        PetRevivalRequest request = request(UUID.randomUUID(), player, pet, "100.00");
        service.begin(request).toCompletableFuture().get();
        service.quarantine(request.operationId(), player, pet, "sqlite_write_failed")
                .toCompletableFuture().get();

        PetRevivalResult compensated = service.compensateQuarantined(request.operationId(), "console")
                .toCompletableFuture().get();
        PetRevivalResult replay = service.compensateQuarantined(request.operationId(), "another_admin")
                .toCompletableFuture().get();
        PetRevivalResult future = service.begin(request(UUID.randomUUID(), player, pet, "50.00"))
                .toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.COMPENSATED, compensated.status());
        assertEquals(PetRevivalResult.Status.COMPENSATED, replay.status());
        assertEquals(PetRevivalResult.Status.STARTED, future.status());
        assertEquals(new BigDecimal("950.00"), balance(player));
        assertEquals(3, count("rpg_ledger"));
        assertEquals(1, reversals(request.debitKey()));
        assertEquals(PetRevivalIntent.State.COMPENSATED,
                service.find(request.operationId()).toCompletableFuture().get().orElseThrow().state());
    }

    @Test
    void compensationAtBalanceLimitFailsClosedWithoutConsumingReversal() throws Exception {
        UUID player = UUID.randomUUID();
        PetRevivalRequest request = request(UUID.randomUUID(), player, UUID.randomUUID(), "100.00");
        service.begin(request).toCompletableFuture().get();
        service.quarantine(request.operationId(), player, request.petId(), "sqlite_write_failed")
                .toCompletableFuture().get();
        setBalance(player, new BigDecimal("999999999950.00"));

        PetRevivalResult rejected = service.compensateQuarantined(request.operationId(), "console")
                .toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.BALANCE_LIMIT, rejected.status());
        assertEquals(new BigDecimal("999999999950.00"), balance(player));
        assertEquals(1, count("rpg_ledger"));
        assertEquals(PetRevivalIntent.State.QUARANTINED,
                service.find(request.operationId()).toCompletableFuture().get().orElseThrow().state());

        setBalance(player, new BigDecimal("999999999900.00"));
        assertEquals(PetRevivalResult.Status.COMPENSATED,
                service.compensateQuarantined(request.operationId(), "console").toCompletableFuture().get().status());
        assertEquals(new BigDecimal("1000000000000.00"), balance(player));
    }

    @Test
    void compensationRejectsInvalidActorAndNonQuarantinedOperations() throws Exception {
        PetRevivalRequest request = request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "100.00");
        service.begin(request).toCompletableFuture().get();

        assertEquals(PetRevivalResult.Status.INVALID,
                service.compensateQuarantined(request.operationId(), "bad actor").toCompletableFuture().get().status());
        assertEquals(PetRevivalResult.Status.CONFLICT,
                service.compensateQuarantined(request.operationId(), "console").toCompletableFuture().get().status());
        assertEquals(new BigDecimal("900.00"), balance(request.playerId()));
        assertEquals(1, count("rpg_ledger"));
    }

    private PetRevivalRequest request(UUID operation, UUID player, UUID pet, String amount) {
        return new PetRevivalRequest(operation, player, pet, new BigDecimal(amount), "pet-revive:" + operation);
    }

    private BigDecimal balance(UUID player) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ?")) {
            query.setString(1, player.toString());
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getBigDecimal(1).setScale(2);
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

    private int reversals(String debitKey) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM rpg_ledger WHERE reversal_of = ?")) {
            query.setString(1, debitKey);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private void setBalance(UUID player, BigDecimal value) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ? WHERE player_uuid = ?")) {
            update.setBigDecimal(1, value);
            update.setString(2, player.toString());
            assertEquals(1, update.executeUpdate());
        }
    }
}
