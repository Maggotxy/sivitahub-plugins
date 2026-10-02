package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SkillMutationRequest;
import cn.originalrpg.api.model.SkillMutationResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JdbcSkillMutationSettlementTest {
    private static final BigDecimal STARTING_BALANCE = new BigDecimal("1000.00");
    private static final BigDecimal MUTATION_COST = new BigDecimal("250.00");

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcSkillService skills;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:skill-mutation-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TABLE rpg_accounts (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        balance DECIMAL(19, 2) NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_ledger (
                        id VARCHAR(36) PRIMARY KEY,
                        idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36),
                        amount DECIMAL(19, 2) NOT NULL, fee DECIMAL(19, 2) NOT NULL,
                        reason VARCHAR(128) NOT NULL, reversal_of VARCHAR(96),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            sql.execute("""
                    CREATE TABLE rpg_skills (
                        player_uuid VARCHAR(36) NOT NULL,
                        skill_id VARCHAR(64) NOT NULL,
                        level INTEGER NOT NULL, point_cost INTEGER NOT NULL,
                        variant VARCHAR(64), PRIMARY KEY (player_uuid, skill_id))
                    """);
            sql.execute("""
                    CREATE TABLE rpg_skill_mutation_receipts (
                        idempotency_key VARCHAR(96) PRIMARY KEY,
                        player_uuid VARCHAR(36) NOT NULL,
                        skill_id VARCHAR(64) NOT NULL,
                        operation VARCHAR(12) NOT NULL,
                        expected_variant VARCHAR(64), candidate_variant VARCHAR(64),
                        gold_cost DECIMAL(19, 2) NOT NULL,
                        outcome VARCHAR(16) NOT NULL, resulting_variant VARCHAR(64),
                        settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            connection.commit();
        }
        skills = new JdbcSkillService(dataSource, executor, STARTING_BALANCE);
    }

    @AfterEach
    void close() {
        executor.shutdownNow();
        dataSource.close();
    }

    @Test
    void successfulMutationPersistsOneDebitAndReplaysAfterServiceRestart() throws Exception {
        UUID player = seedSkill(null);
        skills.skills(player).toCompletableFuture().join();
        SkillMutationRequest request = mutate(player, "mutation:success", Optional.of("INVERTED"));

        SkillMutationResult settled = skills.settleMutation(request).toCompletableFuture().join();
        SkillMutationResult replayed = new JdbcSkillService(dataSource, executor, STARTING_BALANCE)
                .settleMutation(request).toCompletableFuture().join();

        assertEquals(SkillMutationResult.Status.SETTLED, settled.status());
        assertEquals(SkillMutationResult.Outcome.MUTATED, settled.outcome());
        assertEquals(SkillMutationResult.Status.REPLAYED, replayed.status());
        assertEquals(settled.outcome(), replayed.outcome());
        assertEquals(settled.resultingVariant(), replayed.resultingVariant());
        assertEquals(new BigDecimal("750.00"), balance(player));
        assertEquals("INVERTED", variant(player).orElseThrow());
        assertEquals("INVERTED", skills.cachedSkills(player).orElseThrow()
                .get("heal").variant().orElseThrow());
        assertEquals(1, rows("rpg_ledger"));
        assertEquals(1, rows("rpg_skill_mutation_receipts"));
    }

    @Test
    void failedRollIsAChargedDurableOutcomeWithoutChangingTheSkill() throws Exception {
        UUID player = seedSkill(null);
        SkillMutationRequest request = mutate(player, "mutation:failed-roll", Optional.empty());

        SkillMutationResult settled = skills.settleMutation(request).toCompletableFuture().join();
        SkillMutationResult replayed = skills.settleMutation(request).toCompletableFuture().join();

        assertEquals(SkillMutationResult.Outcome.FAILED_ROLL, settled.outcome());
        assertEquals(SkillMutationResult.Status.REPLAYED, replayed.status());
        assertEquals(SkillMutationResult.Outcome.FAILED_ROLL, replayed.outcome());
        assertTrue(variant(player).isEmpty());
        assertEquals(new BigDecimal("750.00"), balance(player));
        assertEquals(1, rows("rpg_ledger"));
        assertEquals(1, rows("rpg_skill_mutation_receipts"));
    }

    @Test
    void restorationChargesAndClearsTheVariantInTheSameTransaction() throws Exception {
        UUID player = seedSkill("INVERTED");
        SkillMutationRequest request = new SkillMutationRequest(
                player, "heal", SkillMutationRequest.Operation.RESTORE,
                Optional.of("INVERTED"), Optional.empty(), MUTATION_COST, "mutation:restore");

        SkillMutationResult settled = skills.settleMutation(request).toCompletableFuture().join();
        SkillMutationResult replayed = skills.settleMutation(request).toCompletableFuture().join();

        assertEquals(SkillMutationResult.Outcome.RESTORED, settled.outcome());
        assertEquals(SkillMutationResult.Status.REPLAYED, replayed.status());
        assertTrue(variant(player).isEmpty());
        assertEquals(new BigDecimal("750.00"), balance(player));
        assertEquals(1, rows("rpg_ledger"));
        assertEquals(1, rows("rpg_skill_mutation_receipts"));
    }

    @Test
    void concurrentReplayAndDistinctDoubleClickCannotChargeTwice() throws Exception {
        UUID replayPlayer = seedSkill(null);
        SkillMutationRequest replay = mutate(
                replayPlayer, "mutation:concurrent-replay", Optional.of("INVERTED"));
        CompletableFuture<SkillMutationResult> first = skills.settleMutation(replay).toCompletableFuture();
        CompletableFuture<SkillMutationResult> second = skills.settleMutation(replay).toCompletableFuture();
        CompletableFuture.allOf(first, second).join();

        assertEquals(1L, List.of(first.join(), second.join()).stream()
                .filter(result -> result.status() == SkillMutationResult.Status.SETTLED).count());
        assertEquals(1L, List.of(first.join(), second.join()).stream()
                .filter(result -> result.status() == SkillMutationResult.Status.REPLAYED).count());
        assertEquals(new BigDecimal("750.00"), balance(replayPlayer));

        UUID doubleClickPlayer = seedSkill(null);
        CompletableFuture<SkillMutationResult> third = skills.settleMutation(mutate(
                doubleClickPlayer, "mutation:click-a", Optional.of("INVERTED"))).toCompletableFuture();
        CompletableFuture<SkillMutationResult> fourth = skills.settleMutation(mutate(
                doubleClickPlayer, "mutation:click-b", Optional.of("INVERTED"))).toCompletableFuture();
        CompletableFuture.allOf(third, fourth).join();

        assertEquals(1L, List.of(third.join(), fourth.join()).stream()
                .filter(result -> result.status() == SkillMutationResult.Status.SETTLED).count());
        assertEquals(1L, List.of(third.join(), fourth.join()).stream()
                .filter(result -> result.status() == SkillMutationResult.Status.STALE_VARIANT).count());
        assertEquals(new BigDecimal("750.00"), balance(doubleClickPlayer));
        assertEquals(2, rows("rpg_ledger"));
        assertEquals(2, rows("rpg_skill_mutation_receipts"));
    }

    @Test
    void idempotencyKeyCollisionAndStaleStateNeverCharge() throws Exception {
        UUID player = seedSkill(null);
        SkillMutationRequest original = mutate(player, "mutation:collision", Optional.empty());
        assertEquals(SkillMutationResult.Status.SETTLED,
                skills.settleMutation(original).toCompletableFuture().join().status());

        SkillMutationRequest collision = mutate(
                player, "mutation:collision", Optional.of("INVERTED"));
        assertEquals(SkillMutationResult.Status.INVALID,
                skills.settleMutation(collision).toCompletableFuture().join().status());
        SkillMutationRequest staleRestore = new SkillMutationRequest(
                player, "heal", SkillMutationRequest.Operation.RESTORE,
                Optional.of("INVERTED"), Optional.empty(), MUTATION_COST, "mutation:stale");
        assertEquals(SkillMutationResult.Status.STALE_VARIANT,
                skills.settleMutation(staleRestore).toCompletableFuture().join().status());

        assertEquals(new BigDecimal("750.00"), balance(player));
        assertEquals(1, rows("rpg_ledger"));
        assertEquals(1, rows("rpg_skill_mutation_receipts"));
    }

    @Test
    void assetWriteFailureRollsBackReceiptDebitAndBalance() throws Exception {
        UUID player = seedSkill(null);
        execute("ALTER TABLE rpg_skills ADD CONSTRAINT reject_mutation CHECK (variant IS NULL)");

        assertThrows(CompletionException.class, () -> skills.settleMutation(mutate(
                player, "mutation:asset-fault", Optional.of("INVERTED"))).toCompletableFuture().join());

        assertTrue(variant(player).isEmpty());
        assertEquals(STARTING_BALANCE, balance(player));
        assertEquals(0, rows("rpg_ledger"));
        assertEquals(0, rows("rpg_skill_mutation_receipts"));
    }

    @Test
    void balanceWriteFailureRollsBackAlreadyWrittenSkillAndReceipt() throws Exception {
        UUID player = seedSkill(null);
        execute("ALTER TABLE rpg_accounts ADD CONSTRAINT reject_debit CHECK (balance >= 900)");

        assertThrows(CompletionException.class, () -> skills.settleMutation(mutate(
                player, "mutation:balance-fault", Optional.of("INVERTED"))).toCompletableFuture().join());

        assertTrue(variant(player).isEmpty());
        assertEquals(STARTING_BALANCE, balance(player));
        assertEquals(0, rows("rpg_ledger"));
        assertEquals(0, rows("rpg_skill_mutation_receipts"));
    }

    private UUID seedSkill(String variant) throws Exception {
        UUID player = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement account = connection.prepareStatement(
                     "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?)");
             PreparedStatement skill = connection.prepareStatement("""
                     INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost, variant)
                     VALUES (?, 'heal', 1, 0, ?)
                     """)) {
            account.setString(1, player.toString());
            account.setBigDecimal(2, STARTING_BALANCE);
            account.executeUpdate();
            skill.setString(1, player.toString());
            skill.setString(2, variant);
            skill.executeUpdate();
            connection.commit();
        }
        return player;
    }

    private static SkillMutationRequest mutate(
            UUID player, String key, Optional<String> candidate
    ) {
        return new SkillMutationRequest(
                player, "heal", SkillMutationRequest.Operation.MUTATE,
                Optional.empty(), candidate, MUTATION_COST, key);
    }

    private BigDecimal balance(UUID player) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ?")) {
            query.setString(1, player.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBigDecimal(1).setScale(2);
            }
        }
    }

    private Optional<String> variant(UUID player) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(
                "SELECT variant FROM rpg_skills WHERE player_uuid = ? AND skill_id = 'heal'")) {
            query.setString(1, player.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return Optional.ofNullable(result.getString(1));
            }
        }
    }

    private int rows(String table) throws Exception {
        if (!List.of("rpg_ledger", "rpg_skill_mutation_receipts").contains(table)) {
            throw new IllegalArgumentException("Unexpected test table");
        }
        try (Connection connection = dataSource.getConnection(); Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private void execute(String statement) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute(statement);
            connection.commit();
        }
    }
}
