package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationEnvelope;
import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;
import cn.originalrpg.core.persistence.JdbcEquipmentOperationService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /destroy 已接线路径的行为级测试：真的跑一遍托管出站（不回滚、不 mock），
 * 证明 DESTROYED 会落到装备状态账本，并且重放不会二次推进或二次扣款。
 */
class DestroyOutboxIntegrationTest {
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcEquipmentOperationService outbox;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:destroy-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource = h2;
        executor = Executors.newFixedThreadPool(2);
        outbox = new JdbcEquipmentOperationService(dataSource, executor, new BigDecimal("1000.00"));
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
    void destroyCommitsIntoTheDestroyedTerminalStateAndQueuesTheEssence() throws Exception {
        EquipmentOperationRequest request = destroyRequest();
        EquipmentOperationResult result = outbox.settle(request).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.COMMITTED, result.status());
        assertEquals("DESTROYED", state(request.itemId().orElseThrow()));
        assertEquals(EquipmentOperationKind.DESTROY.name(),
                scalar("SELECT kind FROM rpg_equipment_operations WHERE operation_id = '"
                        + request.operationId() + "'"));
        // 销毁不结算金币：账本必须没有这一笔流水，余额保持初始值。
        assertEquals(new BigDecimal("1000.00"), balance(request.playerId()));
        assertEquals(0, count("rpg_ledger"));
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals("destroyed-essence", scalar("SELECT item_payload FROM rpg_market_deliveries"));
    }

    @Test
    void destroyingAnAlreadyTerminalIdentityIsRefusedWithoutCharge() throws Exception {
        EquipmentOperationRequest first = destroyRequest();
        assertEquals(EquipmentOperationResult.Status.COMMITTED,
                outbox.settle(first).toCompletableFuture().get().status());

        // 另一笔独立操作指向同一件已经终态的装备：账本必须拒绝，不能再扣金币。
        EquipmentOperationRequest second = new EquipmentOperationRequest(
                UUID.randomUUID(), first.playerId(), EquipmentOperationKind.DESTROY, first.itemId(),
                "b".repeat(64), "item=" + first.itemId().orElseThrow() + ";amount=1;result=again",
                BigDecimal.ZERO.setScale(2), "destroy:" + UUID.randomUUID(),
                List.of("second-essence"), List.of("original-item"));

        EquipmentOperationResult refused = outbox.settle(second).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED, refused.status());
        assertEquals("DESTROYED", state(first.itemId().orElseThrow()));
        // 被拒的那笔要有自己的拒绝收据（recovery 通道），否则它无法重放。
        assertEquals("OPERATION_REFUSED",
                scalar("SELECT state FROM rpg_equipment_operations WHERE operation_id = '"
                        + second.operationId() + "'"));
        assertEquals(2, count("rpg_equipment_operations"));
    }

    @Test
    void replayingTheSameDestroyOperationIsIdempotent() throws Exception {
        EquipmentOperationRequest request = destroyRequest();
        outbox.settle(request).toCompletableFuture().get();
        EquipmentOperationResult replay = outbox.settle(request).toCompletableFuture().get();

        assertTrue(replay.replayed());
        assertEquals(EquipmentOperationResult.Status.COMMITTED, replay.status());
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals("DESTROYED", state(request.itemId().orElseThrow()));
    }

    /** 与 DestroyCommand.settle 完全相同形状的请求（只把纸面载荷换成固定字符串）。 */
    private EquipmentOperationRequest destroyRequest() {
        UUID player = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        List<String> rejected = List.of("original-item");
        List<String> committed = List.of("destroyed-essence");
        String resultHash = EquipmentOperationEnvelope.fingerprint("destroy-result", committed);
        String requestPayload = "item=" + item + ";amount=3;result=" + resultHash;
        return new EquipmentOperationRequest(operationId, player, EquipmentOperationKind.DESTROY,
                Optional.of(item), EquipmentOperationEnvelope.fingerprint(requestPayload, rejected),
                requestPayload, BigDecimal.ZERO.setScale(2), "destroy:" + operationId, committed, rejected);
    }

    private String state(UUID item) throws SQLException {
        return scalar("SELECT state FROM rpg_equipment_states WHERE item_id = '" + item + "'");
    }

    private BigDecimal balance(UUID player) throws SQLException {
        return new BigDecimal(scalar(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = '" + player + "'"));
    }

    private int count(String table) throws SQLException {
        return Integer.parseInt(scalar("SELECT COUNT(*) FROM " + table));
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(sql);
             ResultSet result = query.executeQuery()) {
            if (!result.next()) throw new SQLException("no row for " + sql);
            return result.getString(1);
        }
    }
}
