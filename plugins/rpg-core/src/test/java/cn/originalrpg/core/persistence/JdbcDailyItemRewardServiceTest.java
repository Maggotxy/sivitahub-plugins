package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.DailyItemRewardResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JdbcDailyItemRewardServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcActivityEconomyService rewards;
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:daily_item_reward_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        config.setMaximumPoolSize(24);
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_daily_item_rewards (
                        player_uuid VARCHAR(36) NOT NULL, reward_id VARCHAR(64) NOT NULL,
                        reward_date DATE NOT NULL, used INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, reward_id, reward_date)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_daily_item_reward_receipts (
                        idempotency_key VARCHAR(96) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        reward_id VARCHAR(64) NOT NULL, reward_date DATE NOT NULL,
                        requested INTEGER NOT NULL, granted INTEGER NOT NULL,
                        used_after INTEGER NOT NULL, daily_cap INTEGER NOT NULL,
                        request_hash VARCHAR(64) NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_deliveries (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        item_payload TEXT NOT NULL CHECK (item_payload <> 'reject'),
                        source VARCHAR(96) NOT NULL, state VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        delivered_at TIMESTAMP NULL
                    )
                    """);
            connection.commit();
        }
        executor = Executors.newFixedThreadPool(24);
        rewards = new JdbcActivityEconomyService(dataSource, executor, BigDecimal.ZERO);
        playerId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dataSource.close();
    }

    @Test
    void replayReturnsTheCommittedReceiptWithoutDuplicatingQuotaOrMailbox() throws Exception {
        DailyItemRewardResult settled = reward("essence:replay", 2, 12, 0, "payload");
        DailyItemRewardResult replayed = reward("essence:replay", 2, 12, 0, "payload");

        assertEquals(DailyItemRewardResult.Status.SUCCESS, settled.status());
        assertEquals(DailyItemRewardResult.Status.DUPLICATE, replayed.status());
        assertEquals(2, replayed.granted());
        assertEquals(2, replayed.used());
        assertEquals(2, scalar("SELECT used FROM rpg_daily_item_rewards"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_daily_item_reward_receipts"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_market_deliveries"));
    }

    @Test
    void legacyFloorMigratesOnlyUpAndAQuotaReductionNeverReopensTheAllowance() throws Exception {
        DailyItemRewardResult migrated = reward("essence:migrate", 4, 12, 10, "payload");
        DailyItemRewardResult lowered = reward("essence:lower-cap", 1, 8, 0, "payload");

        assertEquals(DailyItemRewardResult.Status.SUCCESS, migrated.status());
        assertEquals(2, migrated.granted());
        assertEquals(12, migrated.used());
        assertEquals(DailyItemRewardResult.Status.DAILY_LIMIT, lowered.status());
        assertEquals(0, lowered.granted());
        assertEquals(12, lowered.used());
        assertEquals(8, lowered.dailyCap());
        assertEquals(12, scalar("SELECT used FROM rpg_daily_item_rewards"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_market_deliveries"));
    }

    @Test
    void concurrentDistinctEventsShareOneStrictDailyAllowance() throws Exception {
        List<CompletableFuture<DailyItemRewardResult>> calls = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            calls.add(rewards.rewardDailyItem(
                    playerId, "survival-resonance-essence", 2, 7, 0,
                    "payload", "essence:distinct-" + index, "vanilla-essence:mining")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        List<DailyItemRewardResult> results = calls.stream().map(CompletableFuture::join).toList();
        assertEquals(7, results.stream().mapToInt(DailyItemRewardResult::granted).sum());
        assertEquals(4, results.stream()
                .filter(result -> result.status() == DailyItemRewardResult.Status.SUCCESS).count());
        assertEquals(12, results.stream()
                .filter(result -> result.status() == DailyItemRewardResult.Status.DAILY_LIMIT).count());
        assertEquals(7, scalar("SELECT used FROM rpg_daily_item_rewards"));
        assertEquals(16, scalar("SELECT COUNT(*) FROM rpg_daily_item_reward_receipts"));
        assertEquals(7, scalar("SELECT COUNT(*) FROM rpg_market_deliveries"));
    }

    @Test
    void concurrentSameEventHasOneSettlementAndStableReplays() throws Exception {
        List<CompletableFuture<DailyItemRewardResult>> calls = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(rewards.rewardDailyItem(
                    playerId, "survival-resonance-essence", 3, 12, 0,
                    "payload", "essence:same-event", "vanilla-essence:raid")
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        List<DailyItemRewardResult> results = calls.stream().map(CompletableFuture::join).toList();
        assertEquals(1, results.stream()
                .filter(result -> result.status() == DailyItemRewardResult.Status.SUCCESS).count());
        assertEquals(23, results.stream()
                .filter(result -> result.status() == DailyItemRewardResult.Status.DUPLICATE).count());
        assertEquals(3, scalar("SELECT used FROM rpg_daily_item_rewards"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM rpg_daily_item_reward_receipts"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM rpg_market_deliveries"));
    }

    @Test
    void mailboxFailureRollsBackQuotaReceiptAndEveryDelivery() throws Exception {
        assertThrows(CompletionException.class,
                () -> reward("essence:rollback", 2, 12, 0, "reject"));

        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_daily_item_rewards"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_daily_item_reward_receipts"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM rpg_market_deliveries"));

        DailyItemRewardResult retry = reward("essence:rollback", 2, 12, 0, "payload");
        assertEquals(DailyItemRewardResult.Status.SUCCESS, retry.status());
        assertEquals(2, scalar("SELECT used FROM rpg_daily_item_rewards"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM rpg_market_deliveries"));
    }

    @Test
    void oneIdempotencyKeyCannotBeReusedForDifferentContent() {
        reward("essence:collision", 1, 12, 0, "payload");

        assertThrows(CompletionException.class,
                () -> reward("essence:collision", 2, 12, 0, "payload"));
    }

    private DailyItemRewardResult reward(
            String key, int requested, int cap, int minimumUsed, String payload
    ) {
        return rewards.rewardDailyItem(
                playerId, "survival-resonance-essence", requested, cap, minimumUsed,
                payload, key, "vanilla-essence:mining").toCompletableFuture().join();
    }

    private int scalar(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
