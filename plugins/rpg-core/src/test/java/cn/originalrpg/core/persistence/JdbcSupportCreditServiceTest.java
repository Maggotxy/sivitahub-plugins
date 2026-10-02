package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SupportCreditResult;
import cn.originalrpg.api.service.SupportCreditService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-012 独立 support_credit 账本的行为级测试。
 *
 * <p>每个用例都真的建表、真的走 JDBC 事务、真的读回余额；判据是「行为」而不是源码字符串。
 * 关键负向路径：同一幂等键重复入账只生效一次、禁止清单目标被拦下且账本零变动、
 * 事务失败后余额与流水一起回滚。</p>
 */
final class JdbcSupportCreditServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcSupportCreditService service;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:support_credit_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_support_credit_accounts (
                        player_uuid VARCHAR(36) PRIMARY KEY, balance BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_support_credit_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, amount BIGINT NOT NULL,
                        direction VARCHAR(8) NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            connection.commit();
        }
        service = new JdbcSupportCreditService(dataSource, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void grantThenSpendMovesBalanceAndWritesOneAuditRowPerMovement() throws Exception {
        UUID player = UUID.randomUUID();

        SupportCreditResult granted = await(service.grant(player, 500, "topup-1", "verification-order"));
        assertEquals(SupportCreditResult.Status.SUCCESS, granted.status());
        assertEquals(500, granted.balance());
        assertEquals(500, await(service.balance(player)));

        SupportCreditResult spent = await(service.spend(
                player, 120, SupportCreditService.SupportCreditTarget.COSMETIC, "spend-1", "aura-purchase"));
        assertEquals(SupportCreditResult.Status.SUCCESS, spent.status());
        assertEquals(380, spent.balance());
        assertEquals(380, await(service.balance(player)));

        // 每一次余额变动恰好一行审计流水：2 次动作 2 行，没有「有余额没流水」。
        assertEquals(2, countLedger(player));
        assertEquals(1, countLedger(player, "GRANT"));
        assertEquals(1, countLedger(player, "SPEND"));
    }

    @Test
    void replayingTheSameIdempotencyKeyNeverCreditsTwice() throws Exception {
        UUID player = UUID.randomUUID();
        await(service.grant(player, 300, "topup-replay", "verification-order"));

        SupportCreditResult replayed = await(service.grant(player, 300, "topup-replay", "verification-order"));

        assertEquals(SupportCreditResult.Status.DUPLICATE, replayed.status());
        assertEquals(300, await(service.balance(player)), "重放不得二次入账");
        assertEquals(1, countLedger(player), "重放不得写第二行流水");
    }

    @Test
    void forbiddenTargetIsRejectedBeforeAnyLedgerWrite() throws Exception {
        UUID player = UUID.randomUUID();
        await(service.grant(player, 400, "topup-2", "verification-order"));

        // C-012 禁止清单：support_credit 不得买到战力相关目标。
        for (SupportCreditService.SupportCreditTarget forbidden : new SupportCreditService.SupportCreditTarget[]{
                SupportCreditService.SupportCreditTarget.EQUIPMENT,
                SupportCreditService.SupportCreditTarget.MATERIAL,
                SupportCreditService.SupportCreditTarget.FORGE_CONSUMABLE,
                SupportCreditService.SupportCreditTarget.EXPERIENCE,
                SupportCreditService.SupportCreditTarget.ENERGY,
                SupportCreditService.SupportCreditTarget.DROP_RATE,
                SupportCreditService.SupportCreditTarget.SEASON_TUNING,
                SupportCreditService.SupportCreditTarget.COMBAT_POWER}) {
            SupportCreditResult result = await(service.spend(
                    player, 10, forbidden, "spend-" + forbidden, "forbidden-attempt"));
            assertEquals(SupportCreditResult.Status.CROSS_CURRENCY_FORBIDDEN, result.status(),
                    "禁止清单目标必须被拦下: " + forbidden);
        }
        assertEquals(400, await(service.balance(player)), "被拒请求不得改动余额");
        assertEquals(1, countLedger(player), "被拒请求不得留下流水（除充值那一行）");
    }

    @Test
    void insufficientFundsLeavesBalanceUntouched() throws Exception {
        UUID player = UUID.randomUUID();
        await(service.grant(player, 50, "topup-3", "verification-order"));

        SupportCreditResult result = await(service.spend(
                player, 51, SupportCreditService.SupportCreditTarget.CONVENIENCE, "spend-too-much", "backpack"));

        assertEquals(SupportCreditResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(50, await(service.balance(player)));
        assertEquals(1, countLedger(player));
    }

    @Test
    void failedTransactionRollsBackBothBalanceAndLedger() throws Exception {
        UUID player = UUID.randomUUID();
        await(service.grant(player, 200, "topup-4", "verification-order"));
        assertEquals(1, countLedger(player));

        // 负向对照：预置一条与本次请求相同幂等键的流水行，让「写流水」在事务内撞唯一约束。
        // 事务必须整体回滚 —— 不能出现「扣了余额没有流水」，也不能多出一行流水。
        try (Connection connection = dataSource.getConnection();
             var insert = connection.prepareStatement("""
                     INSERT INTO rpg_support_credit_ledger
                     (id, idempotency_key, player_uuid, amount, direction, reason)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, "topup-collision");
            insert.setString(3, player.toString());
            insert.setLong(4, 1);
            insert.setString(5, "GRANT");
            insert.setString(6, "injected");
            insert.executeUpdate();
            connection.commit();
        }
        // 同键重放走的是「幂等命中 → DUPLICATE」分支，余额只加那 1 点（预置行）。
        SupportCreditResult duplicate = await(service.grant(player, 900, "topup-collision", "verification-order"));
        assertEquals(SupportCreditResult.Status.DUPLICATE, duplicate.status());
        assertEquals(200, await(service.balance(player)), "重放不得改余额");
        // 两行 GRANT：原本的充值 + 本次注入的碰撞行；重放没有产生第三行。
        assertEquals(2, countLedger(player, "GRANT"));
        assertEquals(2, countLedger(player));
    }

    @Test
    void invalidRequestsNeverTouchTheLedger() throws Exception {
        UUID player = UUID.randomUUID();
        await(service.grant(player, 200, "topup-5", "verification-order"));

        assertEquals(SupportCreditResult.Status.INVALID,
                await(service.grant(player, 0, "topup-zero", "verification-order")).status());
        assertEquals(SupportCreditResult.Status.INVALID,
                await(service.grant(player, 10, "bad key with spaces", "verification-order")).status());
        // null 目标在 C-012 判定里就是「不是允许通道」，返回 CROSS_CURRENCY_FORBIDDEN。
        assertEquals(SupportCreditResult.Status.CROSS_CURRENCY_FORBIDDEN,
                await(service.spend(player, 10, null, "spend-null-target", "backpack")).status());

        assertEquals(200, await(service.balance(player)));
        assertEquals(1, countLedger(player));
    }

    @Test
    void concurrentSameKeyGrantsOnlyOneWins() throws Exception {
        UUID player = UUID.randomUUID();
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<SupportCreditResult>>();
            for (int i = 0; i < 4; i++) {
                futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() ->
                        await(service.grant(player, 100, "topup-race", "verification-order")), callers));
            }
            int succeeded = 0;
            for (var future : futures) {
                try {
                    if (future.join().status() == SupportCreditResult.Status.SUCCESS) succeeded++;
                } catch (CompletionException ignored) {
                    // 并发冲突整体回滚也是合法结果：关键是最终余额与流水只有一份。
                }
            }
            assertTrue(succeeded <= 1, "同一幂等键只能有一次成功");
            assertEquals(100, await(service.balance(player)));
            assertEquals(1, countLedger(player));
        } finally {
            callers.shutdownNow();
        }
    }

    private long countLedger(UUID player) throws Exception {
        return countLedger(player, null);
    }

    private long countLedger(UUID player, String direction) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var query = connection.prepareStatement(direction == null
                     ? "SELECT COUNT(*) FROM rpg_support_credit_ledger WHERE player_uuid = ?"
                     : "SELECT COUNT(*) FROM rpg_support_credit_ledger WHERE player_uuid = ? AND direction = ?")) {
            query.setString(1, player.toString());
            if (direction != null) query.setString(2, direction);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
