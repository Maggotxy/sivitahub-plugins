package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SeasonAttunementRecord;
import cn.originalrpg.api.rules.EquipmentLifecycle;
import cn.originalrpg.api.rules.SeasonAttunementRules;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-008 第五条退出路径（赛季调谐）的行为级测试：真的落库、真的换季处置、
 * 未启用开关时真的零影响。
 */
final class JdbcSeasonAttunementServiceTest {
    private HikariDataSource dataSource;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:season_attune_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        config.setAutoCommit(false);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_equipment_states (
                        item_id VARCHAR(36) PRIMARY KEY, state VARCHAR(24) NOT NULL,
                        owner_uuid VARCHAR(36), last_operation VARCHAR(24),
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_equipment_lifecycle_audit (
                        id VARCHAR(36) PRIMARY KEY, item_id VARCHAR(36) NOT NULL, action VARCHAR(16) NOT NULL,
                        from_state VARCHAR(24) NOT NULL, to_state VARCHAR(24) NOT NULL,
                        actor VARCHAR(64) NOT NULL, note VARCHAR(500) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_market_deliveries (
                        id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,
                        item_payload TEXT NOT NULL, source VARCHAR(96) NOT NULL, state VARCHAR(16) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP NULL)
                    """);
            statement.execute("""
                    CREATE TABLE rpg_season_attunements (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        season_id VARCHAR(32) NOT NULL, item_id VARCHAR(36) NOT NULL,
                        owner_uuid VARCHAR(36) NOT NULL, state VARCHAR(24) NOT NULL,
                        action VARCHAR(24) NOT NULL, reason VARCHAR(160) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)
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
    void attunementMovesTheIdentityIntoSeasonAttunedAndQueuesTheMailboxDelivery() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        bind(item, owner);
        JdbcSeasonAttunementService service = service(true);

        SeasonAttunementRecord record = service
                .attune("launch-1", item, owner, "season-attune:settle-1:" + owner)
                .toCompletableFuture().join();

        assertTrue(record.attuned(), "调谐成功必须落到状态机认可的 SEASON_ATTUNED");
        assertEquals(EquipmentLifecycle.State.SEASON_ATTUNED.name(), state(item));
        assertEquals(record.state(), SeasonAttunementRecord.REQUIRED_STATE.name());
        assertEquals(1L, count("rpg_season_attunements"));
        // 不结算金币/功勋：本表之外没有账本行可被写。
        assertEquals(1L, count("rpg_market_deliveries"));
        assertEquals("season-attunement",
                scalar("SELECT source FROM rpg_market_deliveries"));
        assertEquals(1L, count("rpg_equipment_lifecycle_audit"));
        assertEquals("ATTUNE", scalar("SELECT action FROM rpg_equipment_lifecycle_audit"));
    }

    @Test
    void replayingTheSameSettlementKeyDoesNotAdvanceOrDeliverTwice() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        bind(item, owner);
        JdbcSeasonAttunementService service = service(true);
        String key = "season-attune:settle-1:" + owner;

        service.attune("launch-1", item, owner, key).toCompletableFuture().join();
        SeasonAttunementRecord replay = service.attune("launch-1", item, owner, key)
                .toCompletableFuture().join();

        assertTrue(replay.attuned(), "同键重放必须返回同一事实");
        assertEquals(1L, count("rpg_season_attunements"));
        assertEquals(1L, count("rpg_market_deliveries"));
    }

    @Test
    void terminalAndQuarantinedIdentitiesAreRefusedWithoutAnyRow() throws Exception {
        UUID owner = UUID.randomUUID();
        JdbcSeasonAttunementService service = service(true);
        for (EquipmentLifecycle.State blocked : new EquipmentLifecycle.State[]{
                EquipmentLifecycle.State.SALVAGED, EquipmentLifecycle.State.DESTROYED,
                EquipmentLifecycle.State.LEGACY_EXTRACTED, EquipmentLifecycle.State.QUARANTINED}) {
            UUID item = UUID.randomUUID();
            write(item, owner, blocked);
            SeasonAttunementRecord record = service
                    .attune("launch-1", item, owner, "season-attune:settle:" + item)
                    .toCompletableFuture().join();
            assertFalse(record.attuned(), blocked + " 不得被调谐（失败关闭）");
            assertEquals(blocked.name(), state(item), blocked + " 的状态不得被改写");
        }
        assertEquals(0L, count("rpg_season_attunements"));
        assertEquals(0L, count("rpg_market_deliveries"));
    }

    @Test
    void attunedGearIsSettledByTheSeasonSwitchPerTheLifecycle() throws Exception {
        // 「赛季切换时调谐装备按文档处置」：调谐态是非终态，装备离开旧赛季流通后
        // 随结算交还原持有者（SEASON_ATTUNED -> BOUND），原主不变。
        UUID owner = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        bind(item, owner);
        JdbcSeasonAttunementService service = service(true);
        service.attune("launch-1", item, owner, "season-attune:settle-1:" + owner)
                .toCompletableFuture().join();

        try (Connection connection = dataSource.getConnection()) {
            EquipmentStateLedger ledger = new EquipmentStateLedger();
            assertEquals(java.util.Optional.of(owner), ledger.attunedOwner(connection, item),
                    "调谐不改写持有者");
            assertEquals(EquipmentLifecycle.State.BOUND, ledger.releaseAttuned(connection, item));
            connection.commit();
        }
        assertEquals("BOUND", state(item));
        assertEquals(owner.toString(), scalar("SELECT owner_uuid FROM rpg_equipment_states WHERE item_id = '"
                + item + "'"));
        // 调谐态离开后不再是终态，且从未成为 SALVAGED/DESTROYED 一类终态。
        assertFalse(EquipmentLifecycle.isTerminal(EquipmentLifecycle.State.SEASON_ATTUNED));
        assertTrue(SeasonAttunementRules.attunable(EquipmentLifecycle.State.BOUND));
    }

    @Test
    void disabledKillSwitchHasZeroEffect() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        bind(item, owner);
        JdbcSeasonAttunementService service = service(false);

        SeasonAttunementRecord record = service
                .attune("launch-1", item, owner, "season-attune:settle-1:" + owner)
                .toCompletableFuture().join();

        assertFalse(record.attuned(), "开关关闭时必须 fail closed");
        assertEquals("BOUND", state(item), "未启用时装备状态一字不改");
        assertEquals(0L, count("rpg_season_attunements"));
        assertEquals(0L, count("rpg_market_deliveries"));
        assertEquals(0L, count("rpg_equipment_lifecycle_audit"));
        assertEquals(List.of(), service.attuneForSettlement("launch-1", owner, "settle-1")
                .toCompletableFuture().join());
    }

    @Test
    void settlementBatchAttunesEveryOwnedIdentityOfThePlayerOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        bind(first, owner);
        bind(second, owner);
        bind(other, stranger);
        JdbcSeasonAttunementService service = service(true);

        List<SeasonAttunementRecord> records = service
                .attuneForSettlement("launch-1", owner, "settle-1")
                .toCompletableFuture().join();

        assertEquals(2, records.size(), "只处理该玩家自己的身份：" + records);
        assertTrue(records.stream().allMatch(SeasonAttunementRecord::attuned));
        assertEquals("BOUND", state(other), "别人的装备不得被动到");
        // 同一结算重放：逐件命中唯一键，不新增记录。
        assertEquals(2, service.attuneForSettlement("launch-1", owner, "settle-1")
                .toCompletableFuture().join().size());
        assertEquals(2L, count("rpg_season_attunements"));
    }

    private JdbcSeasonAttunementService service(boolean enabled) throws Exception {
        MechanismRegistry mechanisms = new MechanismRegistry(dataSource);
        Field cache = MechanismRegistry.class.getDeclaredField("cache");
        cache.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Boolean> values = (Map<String, Boolean>) cache.get(mechanisms);
        values.put(MechanismRegistry.SEASON_SETTLEMENT, enabled);
        return new JdbcSeasonAttunementService(dataSource, executor, mechanisms);
    }

    private void bind(UUID item, UUID owner) throws SQLException {
        write(item, owner, EquipmentLifecycle.State.BOUND);
    }

    private void write(UUID item, UUID owner, EquipmentLifecycle.State state) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO rpg_equipment_states (item_id, state, owner_uuid, last_operation)"
                    + " VALUES ('" + item + "', '" + state.name() + "', '" + owner + "', 'TEST')");
            connection.commit();
        }
    }

    private String state(UUID item) throws SQLException {
        return scalar("SELECT state FROM rpg_equipment_states WHERE item_id = '" + item + "'");
    }

    private long count(String table) throws SQLException {
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) throw new SQLException("no row for " + sql);
            return result.getString(1);
        }
    }
}
