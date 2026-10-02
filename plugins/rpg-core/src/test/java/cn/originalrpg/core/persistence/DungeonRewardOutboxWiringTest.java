package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.DungeonRewardDelivery;
import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.DungeonRewardSettlement;
import cn.originalrpg.api.model.DungeonTicketCandidate;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-102 接线行为测试：副本结算事务在 outbox 启用时登记跨服事件，
 * 关闭时（默认）零写入 —— 单实例部署无影响。
 */
class DungeonRewardOutboxWiringTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcProfileService profiles;
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:dungeon_outbox_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_profiles (
                        player_uuid VARCHAR(36) PRIMARY KEY, archetype VARCHAR(24) NOT NULL,
                        ascension INTEGER NOT NULL, level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        energy INTEGER NOT NULL, unspent_attribute_points INTEGER NOT NULL,
                        skill_points INTEGER NOT NULL, defense INTEGER NOT NULL, strength INTEGER NOT NULL,
                        agility INTEGER NOT NULL, intelligence INTEGER NOT NULL, stamina INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_skill_point_grants (
                        source VARCHAR(96) NOT NULL, idempotency_key VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL, from_local_level INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (source, idempotency_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_deliveries (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        item_payload TEXT NOT NULL, source VARCHAR(96) NOT NULL, state VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_dungeon_reward_receipts (
                        run_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        dungeon_id VARCHAR(64) NOT NULL, experience_awarded BIGINT NOT NULL,
                        queued_deliveries INTEGER NOT NULL, ticket_dropped BOOLEAN NOT NULL,
                        ticket_failures INTEGER NOT NULL,
                        settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (run_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_dungeon_ticket_pity (
                        player_uuid VARCHAR(36) NOT NULL, pity_group VARCHAR(64) NOT NULL,
                        failures INTEGER NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, pity_group)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_outbox (
                        event_id VARCHAR(36) PRIMARY KEY,
                        aggregate_type VARCHAR(32) NOT NULL,
                        aggregate_id VARCHAR(36) NOT NULL,
                        idempotency_key VARCHAR(96) NULL,
                        payload TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        last_error VARCHAR(240) NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        processed_at TIMESTAMP NULL
                    )
                    """);
            statement.execute("CREATE UNIQUE INDEX uq_rpg_outbox_idempotency ON rpg_outbox (idempotency_key)");
            connection.commit();
        }
        executor = Executors.newFixedThreadPool(4);
        profiles = new JdbcProfileService(dataSource, executor, 100, 2, 3);
        playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.WARRIOR).toCompletableFuture().join();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        dataSource.close();
    }

    @Test
    void disabledOutboxWritesNoRowsSoSingleInstanceHasZeroImpact() {
        JdbcDungeonRewardService rewards = new JdbcDungeonRewardService(
                dataSource, executor, profiles, null, null);

        DungeonRewardSettlement settlement = rewards.settle(request(UUID.randomUUID()))
                .toCompletableFuture().join();

        assertEquals(DungeonRewardSettlement.Status.SETTLED, settlement.status());
        assertEquals(0, count("rpg_outbox"), "默认关闭时不得写任何 outbox 行");
        assertEquals(1, count("rpg_dungeon_reward_receipts"));
    }

    @Test
    void enabledOutboxPublishesExactlyOneDungeonCompletionEventAtomically() {
        JdbcDungeonRewardService rewards = new JdbcDungeonRewardService(
                dataSource, executor, profiles, null, new JdbcOutboxRegistry());
        UUID runId = UUID.randomUUID();

        rewards.settle(request(runId)).toCompletableFuture().join();
        // 重放同一 runId：不得产生第二条 outbox 事件（幂等键去重）
        rewards.settle(request(runId)).toCompletableFuture().join();

        assertEquals(1, count("rpg_outbox"), "同一 runId 的事件必须幂等去重");
        assertEquals(1, count("rpg_dungeon_reward_receipts"));
        assertEquals("dungeon.completion", scalar("SELECT aggregate_type FROM rpg_outbox"));
        assertEquals(runId.toString(), scalar("SELECT aggregate_id FROM rpg_outbox"));
        assertTrue(scalar("SELECT idempotency_key FROM rpg_outbox")
                .contains(runId.toString()), "幂等键必须绑定业务 runId");
    }

    @Test
    void rollbackOfTheBusinessTransactionRemovesThePublishedEvent() throws Exception {
        JdbcDungeonRewardService rewards = new JdbcDungeonRewardService(
                dataSource, executor, profiles, null, new JdbcOutboxRegistry());
        UUID runId = UUID.randomUUID();
        // 预占 delivery id 制造冲突，使整个结算事务回滚
        DungeonRewardRequest request = request(runId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                     VALUES (?, ?, 'collision', 'test', 'PENDING')
                     """)) {
            insert.setString(1, request.deliveries().getFirst().deliveryId().toString());
            insert.setString(2, playerId.toString());
            insert.executeUpdate();
            connection.commit();
        }

        try {
            rewards.settle(request).toCompletableFuture().join();
        } catch (RuntimeException expected) {
            // 事务冲突按异常冒泡，outbox 必须随业务一并回滚
        }

        assertEquals(0, count("rpg_outbox"), "业务事务回滚后 outbox 事件必须一并回滚");
        assertEquals(0, count("rpg_dungeon_reward_receipts"));
    }

    @Test
    void publishedEventIsDeliverableByTheDispatcherExactlyOnce() throws Exception {
        JdbcDungeonRewardService rewards = new JdbcDungeonRewardService(
                dataSource, executor, profiles, null, new JdbcOutboxRegistry());
        rewards.settle(request(UUID.randomUUID())).toCompletableFuture().join();

        List<String> delivered = new ArrayList<>();
        OutboxDispatcher dispatcher = new OutboxDispatcher(dataSource, List.of(
                new OutboxDispatcher.OutboxHandler() {
                    @Override
                    public String aggregateType() {
                        return "dungeon.completion";
                    }

                    @Override
                    public boolean handle(OutboxEvent event) {
                        delivered.add(event.aggregateId());
                        return true;
                    }
                }));

        assertEquals(1, dispatcher.dispatchBatch(10).delivered());
        assertEquals(0, dispatcher.dispatchBatch(10).delivered(), "已投递事件不得再次投递");
        assertEquals(1, delivered.size());
    }

    private DungeonRewardRequest request(UUID runId) {
        DungeonRewardDelivery material = new DungeonRewardDelivery(
                UUID.nameUUIDFromBytes((runId + ":" + playerId + ":material").getBytes()),
                "material-payload", "dungeon:ancient-ruins:material");
        DungeonRewardDelivery ticket = new DungeonRewardDelivery(
                UUID.nameUUIDFromBytes((runId + ":" + playerId + ":ticket").getBytes()),
                "ticket-payload", "dungeon:ancient-ruins:hidden-ticket");
        return new DungeonRewardRequest(
                runId, "ancient-ruins", playerId, 40L,
                List.of(material),
                Optional.of(new DungeonTicketCandidate(ticket, "ancient-ruins-hidden-ticket", 0.0, 3)));
    }

    private int count(String table) {
        return Integer.parseInt(scalar("SELECT COUNT(*) FROM " + table));
    }

    private String scalar(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            String value = result.getString(1);
            connection.rollback();
            return value;
        } catch (SQLException exception) {
            throw new IllegalStateException("query failed: " + sql, exception);
        }
    }
}
