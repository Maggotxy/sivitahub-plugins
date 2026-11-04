package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.ActivityRewardResult;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.EconomyPurchaseResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.h2.api.TableEngine;
import org.h2.command.ddl.CreateTableData;
import org.h2.mvstore.db.MVTable;
import org.h2.table.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real H2 2.3.232 commit/cache-invalidation window without sleeps
 * or mocked SQL results. H2's public TableEngine extension delays the first
 * table's afterCommit callback; the storage transaction is already committed,
 * but the second table's query-result cache version has not been advanced yet.
 * The account or wallet is created by a competing connection, never precreated
 * in the service connection to avoid the original race.
 */
public class JdbcActivityEconomyCreationVisibilityTest {
    private static final long MAX_MERIT = 1_000_000_000L;
    private static final BigDecimal EXISTING_GOLD = new BigDecimal("11.25");
    private static final ConcurrentHashMap<String, CommitGate> GATES = new ConcurrentHashMap<>();

    @Test
    void rewardReadsConcurrentlyCreatedAccountBeforeItsCachedMissIsInvalidated() throws Exception {
        verifyRewardAndRetry(CreatedRow.ACCOUNT);
    }

    @Test
    void rewardReadsConcurrentlyCreatedWalletBeforeItsCachedMissIsInvalidated() throws Exception {
        verifyRewardAndRetry(CreatedRow.WALLET);
    }

    private void verifyRewardAndRetry(CreatedRow row) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.pauseCreatorAfterStorageCommit(row);

            // This must finish while the creator is still paused before cache
            // invalidation. Moving the assertion after release hides the bug.
            ActivityRewardResult first = fixture.reward("visibility:first");
            assertEquals(ActivityRewardResult.Status.SUCCESS, first.status());
            assertEquals(EXISTING_GOLD, first.progress().gold());
            assertEquals(MAX_MERIT, first.progress().merit());
            assertEquals(1L, first.meritGranted());
            fixture.finishCreator();

            assertEquals(ActivityRewardResult.Status.DUPLICATE,
                    fixture.reward("visibility:first").status());
            assertEquals(ActivityRewardResult.Status.MERIT_LIMIT,
                    fixture.reward("visibility:retry").status());
            assertEquals(0L, fixture.count("SELECT COUNT(*) FROM rpg_activity_ledger "
                    + "WHERE idempotency_key = 'visibility:retry'"));

            EconomyPurchaseResult spent = fixture.service.purchase(fixture.player, BigDecimal.ZERO,
                    1L, "test-item-payload", "visibility:spend", "visibility-regression")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(EconomyPurchaseResult.Status.SUCCESS, spent.status());
            assertEquals(ActivityRewardResult.Status.SUCCESS,
                    fixture.reward("visibility:retry").status());
            assertEquals(MAX_MERIT, fixture.service.progress(fixture.player)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).merit());
            assertEquals(2L, fixture.count("SELECT COUNT(*) FROM rpg_activity_ledger"));
            assertEquals(2L, fixture.count("SELECT COUNT(*) FROM rpg_merit_ledger WHERE direction = 'CREDIT'"));
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM rpg_merit_ledger WHERE direction = 'DEBIT'"));
            assertEquals(1L, fixture.count("SELECT COUNT(*) FROM rpg_market_deliveries WHERE state = 'PENDING'"));
            assertEquals(0L, fixture.count("SELECT COUNT(*) FROM rpg_ledger"));
            assertEquals(2L, fixture.count("SELECT merit_earned FROM rpg_activity_daily"));
        }
    }

    private enum CreatedRow {
        ACCOUNT("SELECT balance FROM rpg_accounts WHERE player_uuid = ?"),
        WALLET("SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ?");

        private final String select;

        CreatedRow(String select) {
            this.select = select;
        }
    }

    private static final class CommitGate {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final CountDownLatch dataCommitted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
    }

    /** Public because H2 instantiates a table engine by its class name. */
    public static final class CommitGateEngine implements TableEngine {
        @Override
        public Table createTable(CreateTableData data) {
            CommitGate gate = GATES.get(data.tableName);
            if (gate == null) throw new IllegalStateException("Unregistered test commit gate");
            return new CommitGateTable(data, gate);
        }
    }

    private static final class CommitGateTable extends MVTable {
        private final CommitGate gate;

        private CommitGateTable(CreateTableData data, CommitGate gate) {
            super(data, data.schema.getDatabase().getStore());
            this.gate = gate;
        }

        @Override
        public void afterCommit() {
            if (gate.armed.compareAndSet(true, false)) {
                gate.dataCommitted.countDown();
                try {
                    if (!gate.release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting for the visibility assertion");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Commit gate interrupted", interrupted);
                }
            }
            super.afterCommit();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final UUID player = UUID.randomUUID();
        private final CommitGate gate = new CommitGate();
        private final String gateTable = "commit_gate_" + UUID.randomUUID().toString().replace("-", "");
        private final HikariDataSource dataSource;
        private final Connection creator;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final JdbcActivityEconomyService service;
        private Future<?> creatorCommit;

        private Fixture() throws Exception {
            String url = "jdbc:h2:mem:activity_visibility_" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=4000";
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setAutoCommit(false);
            // Reuse the primed physical connection deterministically, as the
            // production pool does, rather than relying on scheduling luck.
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(2_000);
            dataSource = new HikariDataSource(config);
            creator = DriverManager.getConnection(url);
            try (Statement statement = creator.createStatement()) {
                for (String ddl : SCHEMA) statement.execute(ddl);
                GATES.put(gateTable, gate);
                try {
                    statement.execute("CREATE TABLE " + gateTable + " (id INTEGER) ENGINE \""
                            + CommitGateEngine.class.getName() + "\"");
                } finally {
                    GATES.remove(gateTable);
                }
            }
            creator.setAutoCommit(false);
            service = new JdbcActivityEconomyService(dataSource, executor, new BigDecimal("7.00"));
        }

        private void pauseCreatorAfterStorageCommit(CreatedRow row) throws Exception {
            if (row == CreatedRow.ACCOUNT) insertWallet();
            else insertAccount();
            creator.commit();
            try (Statement statement = creator.createStatement()) {
                statement.execute("INSERT INTO " + gateTable + " VALUES (1)");
            }
            if (row == CreatedRow.ACCOUNT) insertAccount();
            else insertWallet();

            try (Connection reader = dataSource.getConnection();
                 PreparedStatement query = reader.prepareStatement(row.select)) {
                query.setString(1, player.toString());
                try (ResultSet result = query.executeQuery()) {
                    assertFalse(result.next(), "An uncommitted competing creation must not be visible");
                }
                reader.rollback();
            }
            gate.armed.set(true);
            creatorCommit = executor.submit(() -> {
                try {
                    creator.commit();
                } catch (SQLException failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertTrue(gate.dataCommitted.await(5, TimeUnit.SECONDS), "Storage commit did not reach the gate");
        }

        private void insertAccount() throws SQLException {
            try (PreparedStatement insert = creator.prepareStatement(
                    "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?)")) {
                insert.setString(1, player.toString());
                insert.setBigDecimal(2, EXISTING_GOLD);
                insert.executeUpdate();
            }
        }

        private void insertWallet() throws SQLException {
            try (PreparedStatement insert = creator.prepareStatement(
                    "INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES (?, ?)")) {
                insert.setString(1, player.toString());
                insert.setLong(2, MAX_MERIT - 1L);
                insert.executeUpdate();
            }
        }

        private ActivityRewardResult reward(String key) throws Exception {
            return service.reward(player, EconomyActivity.HUNTING, BigDecimal.ZERO, 1L,
                    BigDecimal.ZERO, 100L, key, "visibility-regression")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        private long count(String sql) throws SQLException {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }

        private void finishCreator() throws Exception {
            gate.release.countDown();
            if (creatorCommit != null) creatorCommit.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                finishCreator();
            } finally {
                try {
                    creator.close();
                } finally {
                    executor.close();
                    dataSource.close();
                }
            }
        }
    }

    private static final String[] SCHEMA = {
            "CREATE TABLE rpg_accounts (player_uuid VARCHAR(36) PRIMARY KEY, balance DECIMAL(19,2) NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE rpg_progression_wallets (player_uuid VARCHAR(36) PRIMARY KEY, merit BIGINT NOT NULL, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)",
            "CREATE TABLE rpg_activity_daily (player_uuid VARCHAR(36), activity VARCHAR(24), activity_date DATE, "
                    + "gold_earned DECIMAL(19,2) NOT NULL, merit_earned BIGINT NOT NULL, "
                    + "PRIMARY KEY (player_uuid, activity, activity_date))",
            "CREATE TABLE rpg_activity_ledger (id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, activity VARCHAR(24) NOT NULL, requested_gold DECIMAL(19,2), "
                    + "granted_gold DECIMAL(19,2), requested_merit BIGINT, granted_merit BIGINT, reason VARCHAR(128))",
            "CREATE TABLE rpg_ledger (id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL, "
                    + "sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36), amount DECIMAL(19,2) NOT NULL, "
                    + "fee DECIMAL(19,2) NOT NULL, reason VARCHAR(128) NOT NULL)",
            "CREATE TABLE rpg_merit_ledger (id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, amount BIGINT NOT NULL, direction VARCHAR(8) NOT NULL, "
                    + "reason VARCHAR(128) NOT NULL)",
            "CREATE TABLE rpg_activity_purchases (id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL, "
                    + "player_uuid VARCHAR(36) NOT NULL, gold_cost DECIMAL(19,2) NOT NULL, merit_cost BIGINT NOT NULL, "
                    + "reason VARCHAR(128) NOT NULL, delivery_id VARCHAR(36) NOT NULL)",
            "CREATE TABLE rpg_market_deliveries (id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, "
                    + "item_payload TEXT NOT NULL, source VARCHAR(128) NOT NULL, state VARCHAR(16) NOT NULL)"
    };
}
