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
    private NetworkDatabaseFixture fixture;
    private javax.sql.DataSource dataSource;
    private ExecutorService executor;
    private JdbcProfileService profiles;
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new NetworkDatabaseFixture();
        dataSource = fixture.dataSource;
        executor = Executors.newFixedThreadPool(4);
        profiles = new JdbcProfileService(dataSource, executor, 100, 2, 3);
        playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.WARRIOR).toCompletableFuture().join();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        fixture.close();
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

    @Test
    void everyPartyMemberGetsADistinctEventAndTransactionalInboxWithoutRepeatingRewards() throws Exception {
        JdbcDungeonRewardService rewards = new JdbcDungeonRewardService(
                dataSource, executor, profiles, null, new JdbcOutboxRegistry());
        UUID run = UUID.randomUUID();
        UUID first = playerId;
        DungeonRewardRequest firstRequest = request(run);
        playerId = UUID.randomUUID();
        profiles.chooseArchetype(playerId, Archetype.MAGE).toCompletableFuture().join();
        DungeonRewardRequest secondRequest = request(run);
        rewards.settle(firstRequest).toCompletableFuture().join();
        rewards.settle(secondRequest).toCompletableFuture().join();
        rewards.settle(firstRequest).toCompletableFuture().join();
        rewards.settle(secondRequest).toCompletableFuture().join();

        JdbcDungeonCompletionInbox inbox = new JdbcDungeonCompletionInbox(dataSource, executor);
        OutboxDispatcher dispatcher = new OutboxDispatcher(dataSource, List.of(inbox));
        assertEquals(2, dispatcher.dispatchBatch(10).delivered());
        assertEquals(0, dispatcher.dispatchBatch(10).claimed());
        assertEquals(2, count("rpg_outbox"));
        assertEquals(2, count("rpg_dungeon_completion_inbox"));
        assertEquals(2, count("rpg_dungeon_reward_receipts"));
        assertEquals(2, count("rpg_market_deliveries"));
        var firstNotice = inbox.unread(first, 16).toCompletableFuture().join().getFirst();
        assertEquals(run, firstNotice.runId());
        assertEquals(1, firstNotice.queuedDeliveries());
        assertEquals(1, inbox.unread(playerId, 16).toCompletableFuture().join().size());
        assertFalse(inbox.acknowledge(firstNotice.eventId(), playerId).toCompletableFuture().join(),
                "Another recipient cannot acknowledge this player's notice");
        assertTrue(inbox.acknowledge(firstNotice.eventId(), first).toCompletableFuture().join());
        assertTrue(inbox.unread(first, 16).toCompletableFuture().join().isEmpty());
        assertEquals(1, inbox.unread(playerId, 16).toCompletableFuture().join().size());
    }

    @Test
    void inventedEventCannotMaterializeARewardOrNotification() throws Exception {
        UUID run = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            new JdbcOutboxRegistry().publish(connection, "dungeon.completion", run.toString(),
                    "invalid-event", new DungeonCompletionPayload(run, playerId, "ancient-ruins").json());
            connection.commit();
        }
        JdbcDungeonCompletionInbox inbox = new JdbcDungeonCompletionInbox(dataSource, executor);
        assertEquals(1, new OutboxDispatcher(dataSource, List.of(inbox)).dispatchBatch(10).retried());
        assertEquals(0, count("rpg_dungeon_completion_inbox"));
        assertEquals(0, count("rpg_dungeon_reward_receipts"));
        assertEquals(0, count("rpg_market_deliveries"));
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
