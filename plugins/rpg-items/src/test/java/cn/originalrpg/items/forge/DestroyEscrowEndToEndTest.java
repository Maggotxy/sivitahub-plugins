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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3-A3 行为级端到端证据：{@code /destroy} 的产物真的走「出站 -> 市场邮箱」，
 * 而不是被直接塞进玩家背包。
 *
 * <p>对照的是真实持久化实现 {@link JdbcEquipmentOperationService}（H2 内存库，
 * 不 mock、不回滚）。断言的是四条用户可见事实：</p>
 * <ol>
 *   <li>终态 {@code DESTROYED} 真的落进装备状态账本；</li>
 *   <li>产物落在 {@code rpg_market_deliveries} 且状态为 {@code PENDING}
 *       —— 也就是说它必须经 {@code /market claim} 才能进背包（C-009）；</li>
 *   <li>不结算金币、不写账本流水（这是分解到底，不是买卖）；</li>
 *   <li>被生命周期拒绝的第二笔操作走 recovery 通道把原物退回，不吞装备。</li>
 * </ol>
 */
class DestroyEscrowEndToEndTest {
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcEquipmentOperationService outbox;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:destroy-escrow-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
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
    void destroyedEssenceIsQueuedIntoTheMarketMailboxNotTheInventory() throws Exception {
        EquipmentOperationRequest request = destroyRequest();
        EquipmentOperationResult result = outbox.settle(request).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.COMMITTED, result.status());
        // ① 终态真的落库。
        assertEquals("DESTROYED", scalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + request.itemId().orElseThrow() + "'"));
        // ② 产物在邮箱里且尚未领取：C-009 唯一交付通道。
        assertEquals("PENDING", scalar("SELECT state FROM rpg_market_deliveries"));
        assertEquals("equipment-destroy:" + request.operationId(),
                scalar("SELECT source FROM rpg_market_deliveries"));
        assertNotEquals("装备已直接进背包", "PENDING");
        // ③ 分解到底不是买卖：不扣金币、不写流水。
        assertEquals(new BigDecimal("1000.00"), balance(request.playerId()));
        assertEquals(0, count("rpg_ledger"));
    }

    @Test
    void aRefusedSecondDestroyQueuesTheOriginalBackThroughRecovery() throws Exception {
        EquipmentOperationRequest first = destroyRequest();
        assertEquals(EquipmentOperationResult.Status.COMMITTED,
                outbox.settle(first).toCompletableFuture().get().status());

        EquipmentOperationRequest second = new EquipmentOperationRequest(
                UUID.randomUUID(), first.playerId(), EquipmentOperationKind.DESTROY, first.itemId(),
                "b".repeat(64), "item=" + first.itemId().orElseThrow() + ";amount=1;result=again",
                BigDecimal.ZERO.setScale(2), "destroy:" + UUID.randomUUID(),
                List.of("second-essence"), List.of("original-item"));
        EquipmentOperationResult refused = outbox.settle(second).toCompletableFuture().get();

        assertEquals(EquipmentOperationResult.Status.OPERATION_REFUSED, refused.status());
        // 拒绝收据必须有自己的 recovery 投递，否则原装备就凭空消失了。
        assertEquals("original-item", scalar("SELECT item_payload FROM rpg_market_deliveries "
                + "WHERE source = 'equipment-destroy:" + second.operationId() + "'"));
        assertEquals("PENDING", scalar("SELECT state FROM rpg_market_deliveries "
                + "WHERE source = 'equipment-destroy:" + second.operationId() + "'"));
        // 终态身份没有被第二笔操作改写。
        assertEquals("DESTROYED", scalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + first.itemId().orElseThrow() + "'"));
    }

    @Test
    void replayingTheSameDestroyOperationQueuesExactlyOneEssence() throws Exception {
        EquipmentOperationRequest request = destroyRequest();
        outbox.settle(request).toCompletableFuture().get();
        EquipmentOperationResult replay = outbox.settle(request).toCompletableFuture().get();

        assertTrue(replay.replayed());
        assertEquals(1, count("rpg_market_deliveries"));
        assertEquals("DESTROYED", scalar("SELECT state FROM rpg_equipment_states WHERE item_id = '"
                + request.itemId().orElseThrow() + "'"));
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

    private BigDecimal balance(UUID player) throws SQLException {
        return new BigDecimal(scalar("SELECT balance FROM rpg_accounts WHERE player_uuid = '" + player + "'"));
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
