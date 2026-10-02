package cn.originalrpg.core.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-102 行为级测试：Transactional Outbox 的投递失败重试与幂等键去重。
 *
 * <p>全部断言都执行真实 SQL 与真实投递处理器，不做源码字符串断言。</p>
 */
class TransactionalOutboxTest {
    private DataSource dataSource;
    private final JdbcOutboxRegistry outbox = new JdbcOutboxRegistry();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:outbox-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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
    }

    @Test
    void failedDeliveryIsRetriedWithIncrementingAttemptsAndNeverLosesTheEvent() throws Exception {
        publish("dungeon.completion", "run-1", "dungeon.completion:run-1:settled", "{\"run\":\"1\"}");

        int[] attemptNumbers = {-1};
        int failures = 2;
        OutboxDispatcher dispatcher = new OutboxDispatcher(dataSource, List.of(
                handler("dungeon.completion", event -> {
                    attemptNumbers[0] = event.attempts();
                    if (event.attempts() < failures) throw new IllegalStateException("simulated network failure");
                    return true;
                })));

        // 前两轮：handler 抛异常 => 事件保留、attempts 递增
        for (int round = 0; round < failures; round++) {
            OutboxDispatcher.DispatchResult result = dispatcher.dispatchBatch(10);
            assertEquals(1, result.claimed());
            assertEquals(0, result.delivered());
            assertEquals(1, result.retried());
        }
        assertEquals(1, unprocessedCount(), "失败的 outbox 事件不得被删除");
        assertEquals(2, attempts("run-1"));

        // 第三轮：handler 成功 => 标记 processed
        OutboxDispatcher.DispatchResult delivered = dispatcher.dispatchBatch(10);
        assertEquals(1, delivered.delivered());
        assertEquals(0, unprocessedCount());
        assertNotNull(processedAt("run-1"));
        assertEquals(3, attempts("run-1"), "attempts 必须累计每次投递尝试");
        assertEquals(2L, dispatcher.metrics().failed());
        assertEquals(1L, dispatcher.metrics().delivered());
    }

    @Test
    void duplicateIdempotencyKeyKeepsExactlyOneRowAndDeliversOnce() throws Exception {
        String key = JdbcOutboxRegistry.idempotencyKey("dungeon.completion", "run-dup", "settled");

        assertTrue(publish("dungeon.completion", "run-dup", key, "{\"run\":\"dup\"}"));
        // 同一业务事务重复登记（重放）必须只保留一条记录
        assertFalse(publish("dungeon.completion", "run-dup", key, "{\"run\":\"dup\"}"));
        assertFalse(publish("dungeon.completion", "run-dup", key, "{\"run\":\"dup\"}"));
        assertEquals(1, totalCount(), "幂等键重复登记不得产生第二条记录");

        List<String> deliveredPayloads = new ArrayList<>();
        OutboxDispatcher dispatcher = new OutboxDispatcher(dataSource, List.of(
                handler("dungeon.completion", event -> {
                    deliveredPayloads.add(event.payload());
                    return true;
                })));

        dispatcher.dispatchBatch(10);
        dispatcher.dispatchBatch(10); // 第二轮不应重复投递已 processed 的事件

        assertEquals(List.of("{\"run\":\"dup\"}"), deliveredPayloads, "同一幂等键只能投递一次");
    }

    @Test
    void transactionRollbackRemovesTheOutboxRowSoNoPhantomEventExists() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            outbox.publish(connection, "dungeon.completion", "run-rollback",
                    "dungeon.completion:run-rollback:settled", "{}");
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertEquals(0, totalCount(), "业务事务回滚后 outbox 记录必须一并回滚");
        assertEquals(0, new OutboxDispatcher(dataSource, List.of()).dispatchBatch(10).claimed());
    }

    @Test
    void unknownAggregateTypeIsRetainedAndReportedInsteadOfSilentlyDropped() throws Exception {
        publish("unknown.type", "x-1", "unknown.type:x-1:y", "{}");
        OutboxDispatcher dispatcher = new OutboxDispatcher(dataSource, List.of());
        OutboxDispatcher.DispatchResult result = dispatcher.dispatchBatch(10);

        assertEquals(1, result.retried());
        assertEquals(1, unprocessedCount());
        assertTrue(lastError("x-1").contains("no handler"), lastError("x-1"));
    }

    private OutboxDispatcher.OutboxHandler handler(
            String type, java.util.function.Predicate<OutboxEvent> body
    ) {
        return new OutboxDispatcher.OutboxHandler() {
            @Override
            public String aggregateType() {
                return type;
            }

            @Override
            public boolean handle(OutboxEvent event) {
                return body.test(event);
            }
        };
    }

    private boolean publish(String type, String id, String key, String payload) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean inserted = outbox.publish(connection, type, id, key, payload);
            connection.commit();
            return inserted;
        }
    }

    private int totalCount() throws SQLException {
        return intScalar("SELECT COUNT(*) FROM rpg_outbox");
    }

    private int unprocessedCount() throws SQLException {
        return intScalar("SELECT COUNT(*) FROM rpg_outbox WHERE processed_at IS NULL");
    }

    private int attempts(String aggregateId) throws SQLException {
        return intScalar("SELECT attempts FROM rpg_outbox WHERE aggregate_id = '" + aggregateId + "'");
    }

    private String processedAt(String aggregateId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT processed_at FROM rpg_outbox WHERE aggregate_id = '" + aggregateId + "'")) {
            assertTrue(result.next());
            String value = result.getString(1);
            connection.rollback();
            return value;
        }
    }

    private String lastError(String aggregateId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT last_error FROM rpg_outbox WHERE aggregate_id = '" + aggregateId + "'")) {
            assertTrue(result.next());
            String value = result.getString(1);
            connection.rollback();
            return value;
        }
    }

    private int intScalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            int value = result.getInt(1);
            connection.rollback();
            return value;
        }
    }
}
