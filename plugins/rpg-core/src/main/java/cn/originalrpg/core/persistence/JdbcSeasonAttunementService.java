package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SeasonAttunementRecord;
import cn.originalrpg.api.rules.EquipmentLifecycle;
import cn.originalrpg.api.rules.SeasonAttunementRules;
import cn.originalrpg.api.service.SeasonAttunementService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * C-008 第五条退出路径的落库实现：赛季调谐由换季结算触发。
 *
 * <p>为什么没有玩家指令（文档依据）：{@code docs/LONG_TERM_GAME_ECONOMY_PLAN.md}
 * 第 3 节把调谐与分解/传承并列成「退出四层」的一层，
 * {@code docs/CODEX_LONG_TERM_EXECUTION_PLAN.md} 的 Phase 4 把它归进装备退出工作 ——
 * 两者都把它描述成赛季收口动作，而不是玩家随时可点的按钮。本轮因此把它接成
 * <b>内部调用点</b>：换季结算（C-010）在收口旧赛季资产层时调用本服务；
 * 装备插件的 plugin.yml / config.yml 保持零入口。</p>
 *
 * <p>落库形状刻意与 {@code EquipmentStateLedger} 一致：状态由唯一权威状态机判定，
 * 幂等由唯一键 {@code idempotency_key} 保证，重放返回已存记录而不是二次推进。</p>
 *
 * <p>开关：复用 C-005 的 {@code season.settlement.enabled}
 * （{@code JdbcSeasonSettlementService} 用的是同一个开关）。Kill Switch 未登记
 * 任何机制时按 fail closed 处理 —— 传 null 表示「本次调用没有开关上下文」，
 * 只可能出现在测试装配里。</p>
 */
public final class JdbcSeasonAttunementService implements SeasonAttunementService {
    private static final Pattern SEASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");
    /** 单次结算里一名玩家可供调谐的装备上限：超过就拒绝，要求人工先处理积压。 */
    private static final int MAX_BATCH = 500;
    private static final String ACTION = "SEASON_ATTUNEMENT";

    private final DataSource dataSource;
    private final Executor executor;
    private final MechanismRegistry mechanisms;

    public JdbcSeasonAttunementService(DataSource dataSource, Executor executor, MechanismRegistry mechanisms) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.mechanisms = mechanisms;
    }

    @Override
    public CompletionStage<SeasonAttunementRecord> attune(
            String seasonId, UUID itemId, UUID ownerId, String idempotencyKey
    ) {
        String season = normalize(seasonId);
        if (season == null || itemId == null || ownerId == null || idempotencyKey == null
                || idempotencyKey.isBlank()) {
            return CompletableFuture.completedFuture(refused(seasonId, itemId, ownerId));
        }
        if (!enabled()) return CompletableFuture.completedFuture(refused(season, itemId, ownerId));
        return AsyncJdbc.supply(executor, () -> inTransaction(connection ->
                attuneOne(connection, season, itemId, ownerId, idempotencyKey.strip())));
    }

    @Override
    public CompletionStage<List<SeasonAttunementRecord>> attuneForSettlement(
            String seasonId, UUID ownerId, String idempotencyKey
    ) {
        String season = normalize(seasonId);
        if (season == null || ownerId == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        if (!enabled()) return CompletableFuture.completedFuture(List.of());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection ->
                attuneOwned(connection, season, ownerId, idempotencyKey.strip())));
    }

    /** 换季结算里同一玩家的整批装备：逐件落库，键各不相同，因此重放逐件命中唯一键。 */
    private List<SeasonAttunementRecord> attuneOwned(
            Connection connection, String seasonId, UUID ownerId, String keyPrefix
    ) throws SQLException {
        List<UUID> candidates = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT item_id FROM rpg_equipment_states
                WHERE owner_uuid = ? ORDER BY item_id LIMIT ?
                """)) {
            query.setString(1, ownerId.toString());
            query.setInt(2, MAX_BATCH + 1);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) candidates.add(UUID.fromString(result.getString(1)));
            }
        }
        if (candidates.size() > MAX_BATCH) {
            throw new IllegalStateException(
                    "Season attunement batch limit reached for " + ownerId + "; drain the backlog first");
        }
        List<SeasonAttunementRecord> records = new ArrayList<>();
        for (UUID itemId : candidates) {
            SeasonAttunementRecord record =
                    attuneOne(connection, seasonId, itemId, ownerId, keyPrefix + ":" + itemId);
            if (record.attuned()) records.add(record);
        }
        return List.copyOf(records);
    }

    private SeasonAttunementRecord attuneOne(
            Connection connection, String seasonId, UUID itemId, UUID ownerId, String idempotencyKey
    ) throws SQLException {
        // 重放优先：唯一键命中即返回已存记录，不重复推进、不重复投递。
        SeasonAttunementRecord existing = existing(connection, idempotencyKey);
        if (existing != null) return existing;
        EquipmentLifecycle.State outcome =
                ledger.attune(connection, itemId, ownerId, seasonId);
        if (!SeasonAttunementRules.outcomeIsAttuned(outcome)) {
            return refused(seasonId, itemId, ownerId);
        }
        persist(connection, seasonId, itemId, ownerId, idempotencyKey, seasonId + ":" + ownerId);
        insertDelivery(connection, itemId, ownerId, idempotencyKey);
        insertAudit(connection, itemId, ownerId, seasonId, idempotencyKey);
        return new SeasonAttunementRecord(seasonId, itemId, ownerId, outcome.name());
    }

    private SeasonAttunementRecord existing(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT season_id, item_id, owner_uuid, state FROM rpg_season_attunements
                WHERE idempotency_key = ?
                """)) {
            query.setString(1, idempotencyKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new SeasonAttunementRecord(
                        result.getString(1),
                        UUID.fromString(result.getString(2)),
                        UUID.fromString(result.getString(3)),
                        result.getString(4));
            }
        }
    }

    private void persist(
            Connection connection, String seasonId, UUID itemId, UUID ownerId,
            String idempotencyKey, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_attunements
                    (id, idempotency_key, season_id, item_id, owner_uuid, state, action, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, idempotencyKey);
            insert.setString(3, seasonId);
            insert.setString(4, itemId.toString());
            insert.setString(5, ownerId.toString());
            insert.setString(6, SeasonAttunementRecord.REQUIRED_STATE.name());
            insert.setString(7, ACTION);
            insert.setString(8, reason);
            insert.executeUpdate();
        }
    }

    /**
     * C-009：调谐产物与 /salvage、/destroy 同一条交付语义 —— 只提交出站请求，
     * 由 {@code /market claim} 从唯一市场邮箱取出，绝不直接改玩家背包。
     */
    private void insertDelivery(Connection connection, UUID itemId, UUID ownerId, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING')
                """)) {
            insert.setString(1, UUID.nameUUIDFromBytes(("season-attune:" + idempotencyKey).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)).toString());
            insert.setString(2, ownerId.toString());
            insert.setString(3, "season-attuned-item:" + itemId);
            insert.setString(4, "season-attunement");
            insert.executeUpdate();
        }
    }

    /** 与 C-013 的账本审计同源：调谐是一次身份态变更，必须留下 actor 与原因。 */
    private void insertAudit(
            Connection connection, UUID itemId, UUID ownerId, String seasonId, String idempotencyKey
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_equipment_lifecycle_audit
                    (id, item_id, action, from_state, to_state, actor, note)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, itemId.toString());
            insert.setString(3, "ATTUNE");
            insert.setString(4, EquipmentLifecycle.State.BOUND.name());
            insert.setString(5, SeasonAttunementRecord.REQUIRED_STATE.name());
            insert.setString(6, SeasonAttunementRules.TRIGGER);
            insert.setString(7, note(seasonId, idempotencyKey, ownerId));
            insert.executeUpdate();
        }
    }

    /** 审计备注列宽 500，超长一律截断而不是让一次调谐因为日志长度失败。 */
    private static String note(String seasonId, String idempotencyKey, UUID ownerId) {
        String text = "season-attunement-" + seasonId + " key=" + idempotencyKey + " owner=" + ownerId;
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private boolean enabled() {
        return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.SEASON_SETTLEMENT);
    }

    private static SeasonAttunementRecord refused(String seasonId, UUID itemId, UUID ownerId) {
        return new SeasonAttunementRecord(
                seasonId == null || seasonId.isBlank() ? "unknown" : seasonId,
                itemId == null ? new UUID(0L, 0L) : itemId,
                ownerId == null ? new UUID(0L, 0L) : ownerId,
                "REFUSED");
    }

    private static String normalize(String seasonId) {
        if (seasonId == null) return null;
        String stripped = seasonId.strip().toLowerCase(java.util.Locale.ROOT);
        return SEASON_ID.matcher(stripped).matches() ? stripped : null;
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                if (connection.getAutoCommit()) connection.setAutoCommit(false);
                T value = operation.apply(connection);
                connection.commit();
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Season attunement transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open season attunement transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private final EquipmentStateLedger ledger = new EquipmentStateLedger();
}
