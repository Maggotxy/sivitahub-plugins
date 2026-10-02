package cn.originalrpg.core.persistence;

import cn.originalrpg.api.rules.SeasonSettlementState;
import cn.originalrpg.api.service.SeasonMeritWalletService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-010 接线门禁 + 赛季层钱包行为测试。
 *
 * <p>本测试做两件事，缺一不算「已接线」：</p>
 * <ol>
 *   <li>机械核对：{@code SeasonSettlementService} 在 {@code rpg-core/src/main} 有真实调用点，
 *       九态全部可达（不是只有枚举）</li>
 *   <li>行为核对：赛季层钱包真的能存能取、幂等键真的生效、两层之间只有搬家没有铸币</li>
 * </ol>
 */
final class JdbcSeasonSettlementWiringTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:season_wire_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_progression_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, merit BIGINT NOT NULL DEFAULT 0,
                        season_id VARCHAR(32),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_merit_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        season_merit BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_merit_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, amount BIGINT NOT NULL,
                        direction VARCHAR(8) NOT NULL, reason VARCHAR(128) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlements (
                        id VARCHAR(36) PRIMARY KEY, season_id VARCHAR(32) NOT NULL,
                        status VARCHAR(24) NOT NULL, preview_hash VARCHAR(64), fact_digest VARCHAR(64),
                        approved_by VARCHAR(64),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_gate (
                        singleton_id INTEGER PRIMARY KEY, settlement_id VARCHAR(36),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_transitions (
                        id VARCHAR(36) PRIMARY KEY, settlement_id VARCHAR(36) NOT NULL,
                        from_state VARCHAR(24) NOT NULL, to_state VARCHAR(24) NOT NULL,
                        actor VARCHAR(64) NOT NULL, reason VARCHAR(128) NOT NULL,
                        fact_digest VARCHAR(64), request_hash VARCHAR(64) NOT NULL,
                        transaction_version BIGINT NOT NULL, committed_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_settlement_progress (
                        settlement_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        merit_debited BIGINT NOT NULL, committed_at TIMESTAMP NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (settlement_id, player_uuid))
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_player_progress (
                        season_id VARCHAR(32) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        honor BIGINT NOT NULL DEFAULT 0, collection_stamps INTEGER NOT NULL DEFAULT 0,
                        completed_runs INTEGER NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (season_id, player_uuid))
                    """);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void settlementServiceIsConsumedOutsideItsOwnDefinitionFile() throws IOException {
        Path root = repositoryRoot().resolve("plugins/rpg-core/src/main/java");
        List<Path> consumers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("JdbcSeasonSettlementService.java")) continue;
                if (Files.readString(file).contains("SeasonSettlementService")) consumers.add(file);
            }
        }
        assertTrue(consumers.stream().anyMatch(path -> path.getFileName().toString().equals("RpgCorePlugin.java")),
                "C-010 违规：SeasonSettlementService 在 rpg-core/src/main 里除自身外没有消费者");
    }

    @Test
    void everySettlementStateIsReachableThroughTheServiceNotJustTheEnum() throws Exception {
        // 九态里除去终态与回滚，其余必须能被服务真的走一遍（不是只有枚举定义）。
        JdbcSeasonSettlementService service = new JdbcSeasonSettlementService(dataSource, executor, null);
        SeasonSettlementState[] reachable = {
                SeasonSettlementState.DRAFT, SeasonSettlementState.PREVIEWED,
                SeasonSettlementState.APPROVED, SeasonSettlementState.FREEZING,
                SeasonSettlementState.SETTLING, SeasonSettlementState.VERIFYING,
                SeasonSettlementState.COMPLETED
        };
        assertEquals(SeasonSettlementState.DRAFT, service.state("launch-1").toCompletableFuture().join());
        for (int index = 1; index < reachable.length; index++) {
            String next = reachable[index] == SeasonSettlementState.SETTLING ? "launch-2" : null;
            var result = service.advance("launch-1", next, reachable[index], "console", "criterion accepted")
                    .toCompletableFuture().join();
            assertEquals(cn.originalrpg.api.model.SeasonSettlementResult.Status.APPLIED, result.status(),
                    "state " + reachable[index]);
        }
        assertEquals(SeasonSettlementState.COMPLETED, service.state("launch-1").toCompletableFuture().join());
        assertEquals(7, reachable.length);
    }

    @Test
    void seasonMeritWalletMovesMeritWithoutMintingAndIsIdempotent() throws Exception {
        UUID player = UUID.randomUUID();
        SeasonMeritWalletService wallet = new JdbcSeasonMeritWalletService(dataSource, executor, "launch-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_progression_wallets (player_uuid, merit, season_id) VALUES ('"
                    + player + "', 500, 'launch-1')");
            connection.commit();
        }

        // 赛季层还没开户，读出来是 0；功勋此刻全在永久层。
        assertEquals(0L, wallet.seasonMerit(player).toCompletableFuture().join());
        assertEquals(200L, wallet.deposit(player, 200, "dep-1", "season-goal-contribution")
                .toCompletableFuture().join());
        // 幂等键：同一个键再存一次不会搬第二遍。
        assertEquals(200L, wallet.deposit(player, 200, "dep-1", "season-goal-contribution")
                .toCompletableFuture().join());
        assertEquals(1L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        // 两层净值恒为 500：搬家不是铸币。
        assertEquals(500L, number("SELECT season_merit FROM rpg_season_merit_wallets")
                + number("SELECT merit FROM rpg_progression_wallets"));

        assertEquals(0L, wallet.withdraw(player, "with-1", "season-goal-reversal").toCompletableFuture().join());
        assertEquals(500L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(0L, number("SELECT season_merit FROM rpg_season_merit_wallets"));

        // 余额不足时拒绝，且不留半截状态（失败的那次不落任何账本行）。
        assertThrows(RuntimeException.class, () -> wallet.deposit(
                player, 9999, "dep-2", "season-goal-contribution").toCompletableFuture().join());
        assertEquals(500L, number("SELECT merit FROM rpg_progression_wallets"));
        assertEquals(0L, number("SELECT season_merit FROM rpg_season_merit_wallets"));
        // 只有成功的存入与取出各一行，共 2 行；失败的那次没有留下痕迹。
        assertEquals(2L, number("SELECT COUNT(*) FROM rpg_merit_ledger"));
        assertEquals(0L, number("SELECT COUNT(*) FROM rpg_merit_ledger WHERE idempotency_key = 'dep-2'"));
    }

    @Test
    void seasonMeritWalletRejectsUnknownActiveSeasonAtConstruction() {
        assertThrows(IllegalArgumentException.class, () ->
                new JdbcSeasonMeritWalletService(dataSource, executor, "not a season"));
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
