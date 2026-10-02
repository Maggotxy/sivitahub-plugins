package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.ProgressionAnomaly;
import cn.originalrpg.api.model.ProgressionAnomalyResult;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.service.ProgressionAnomalyService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Phase 1 成长异常清单（{@code rpg_progression_anomalies}）的真实读写消费者。
 *
 * <p>判定口径是「能否被 {@link ProgressionRules} 唯一解释」，不做任何 {@code level % 30} 猜测：</p>
 * <ul>
 *   <li>转数不在 {@code 0..MAX_ASCENSION}：越界，语义不可解释。</li>
 *   <li>本地等级不在 {@code 1..LOCAL_LEVEL_CAP}：老口径存档（如 {@code level=31} 以上）无法唯一映射。</li>
 *   <li>经验为负：物理上不可能，说明有第三方写库或复制事故。</li>
 *   <li>经验大于本段「升到 30 级所需经验 + 本段预算」：跳变来源无法唯一归因。</li>
 * </ul>
 *
 * <p>[H] 门禁：写入只代表「交人复核」。记录的主键是 {@code player_uuid}，
 * 因此同一玩家只保留一条最新快照（UPDATED 而非 RE-CORDED），人工复核前不会被自动清掉。
 * 复核出口是 {@link #acknowledge}：把 {@code reviewer} 与 {@code reviewed_at} 写进该行，
 * 且必须有非空复核人署名 —— 自动流程没有任何路径能签这一行。</p>
 *
 * <p>读侧消费点是 {@code /rpgadmin anomaly list|ack}（{@code AnomalyReviewCommand}），
 * 本类不自带命令层；管理面换掉也不会多出第二条签署路径。</p>
 */
public final class JdbcProgressionAnomalyService implements ProgressionAnomalyService {
    private static final int REASON_MAX = 128;
    private static final int REVIEWER_MAX = 64;
    private static final int NOTE_MAX = 200;
    private static final int PAGE_MAX = 500;

    private final DataSource dataSource;
    private final Executor executor;

    public JdbcProgressionAnomalyService(DataSource dataSource, Executor executor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<ProgressionAnomalyResult> inspect(
            UUID playerId, int ascension, int level, long experience
    ) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(
                    new ProgressionAnomalyResult(ProgressionAnomalyResult.Status.INVALID, null));
        }
        // 判定口径只有一处实现（ProgressionRules.classifyAnomaly），避免「检测规则两份」漂移。
        String reason = ProgressionRules.classifyAnomaly(ascension, level, experience);
        if (reason == null) {
            return CompletableFuture.completedFuture(
                    new ProgressionAnomalyResult(ProgressionAnomalyResult.Status.HEALTHY, null));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(
                connection -> record(connection, playerId, ascension, level, experience, reason)));
    }

    @Override
    public CompletionStage<Optional<ProgressionAnomaly>> pending(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT player_uuid, ascension, level, experience, reason, detected_at,
                           reviewer, reviewed_at, review_note
                    FROM rpg_progression_anomalies
                    WHERE player_uuid = ? AND reviewed_at IS NULL
                    """)) {
                query.setString(1, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? Optional.of(mapAnomaly(result)) : Optional.<ProgressionAnomaly>empty();
                }
            }
        }));
    }

    @Override
    public CompletionStage<List<ProgressionAnomaly>> pending(int limit) {
        return pending(0, limit);
    }

    @Override
    public CompletionStage<List<ProgressionAnomaly>> pending(int offset, int limit) {
        int boundedOffset = Math.max(offset, 0);
        int boundedLimit = Math.clamp(limit, 1, PAGE_MAX);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            List<ProgressionAnomaly> anomalies = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT player_uuid, ascension, level, experience, reason, detected_at,
                           reviewer, reviewed_at, review_note
                    FROM rpg_progression_anomalies
                    WHERE reviewed_at IS NULL
                    ORDER BY detected_at, player_uuid LIMIT ? OFFSET ?
                    """)) {
                query.setInt(1, boundedLimit);
                query.setInt(2, boundedOffset);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) anomalies.add(mapAnomaly(result));
                }
            }
            return List.copyOf(anomalies);
        }));
    }

    @Override
    public CompletionStage<Long> pendingCount() {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM rpg_progression_anomalies WHERE reviewed_at IS NULL")) {
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            }
        }));
    }

    /**
     * [H] 复核签署：只更新**仍未被签署**的行（{@code reviewed_at IS NULL}）。
     *
     * <p>幂等口径 = 已签署的行不再匹配，{@code executeUpdate()==0} 即「本次没有改动」。
     * 因此重复 ack 既不报错也不覆盖首个署名/首次复核时间（先到者为准），
     * 命令层据此播报「已由 X 于 T 复核」而不是把它当成失败。</p>
     */
    @Override
    public CompletionStage<Boolean> acknowledge(UUID playerId, String reviewer, String note) {
        if (playerId == null || reviewer == null || reviewer.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String boundedReviewer = bound(reviewer.strip(), REVIEWER_MAX);
        String boundedNote = bound(Objects.requireNonNullElse(note, "").strip(), NOTE_MAX);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_progression_anomalies
                    SET reviewer = ?, review_note = ?, reviewed_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ? AND reviewed_at IS NULL
                    """)) {
                update.setString(1, boundedReviewer);
                update.setString(2, boundedNote);
                update.setString(3, playerId.toString());
                return update.executeUpdate() == 1;
            }
        }));
    }

    private ProgressionAnomalyResult record(
            Connection connection, UUID playerId, int ascension, int level, long experience, String reason
    ) throws SQLException {
        String boundedReason = reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
        // 主键是 player_uuid：同一玩家只保留一条最新快照，人工复核前不会被自动清掉。
        // 不用 ON CONFLICT DO UPDATE：H2 的 PostgreSQL 兼容模式不接受带冲突目标的
        // upsert 语法，而本项目同一份 SQL 要同时跑在 H2 与 PostgreSQL 上。
        // 因此做成「已存在则 UPDATE，否则 INSERT」——两库都原生支持，语义等价。
        boolean existed = exists(connection, playerId);
        if (existed) {
            // 刷新快照必须**清空上一次的复核结论**：档案又变成了不可解释状态，
            // 旧署名不能替这次的新事实背书，否则 [H] 门禁会被上一次的签字静默绕过。
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_progression_anomalies
                    SET ascension = ?, level = ?, experience = ?, reason = ?,
                        detected_at = CURRENT_TIMESTAMP,
                        reviewer = NULL, review_note = NULL, reviewed_at = NULL
                    WHERE player_uuid = ?
                    """)) {
                update.setInt(1, ascension);
                update.setInt(2, level);
                update.setLong(3, experience);
                update.setString(4, boundedReason);
                update.setString(5, playerId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Anomaly update matched no row for " + playerId);
                }
            }
        } else {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_progression_anomalies
                    (player_uuid, ascension, level, experience, reason)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setInt(2, ascension);
                insert.setInt(3, level);
                insert.setLong(4, experience);
                insert.setString(5, boundedReason);
                insert.executeUpdate();
            }
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, ascension, level, experience, reason, detected_at,
                       reviewer, reviewed_at, review_note
                FROM rpg_progression_anomalies WHERE player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Anomaly row disappeared after write");
                return new ProgressionAnomalyResult(existed
                        ? ProgressionAnomalyResult.Status.UPDATED
                        : ProgressionAnomalyResult.Status.RECORDED, mapAnomaly(result));
            }
        }
    }

    /** 行是否已存在：决定本次是「新落记录」还是「刷新快照」，也用于负向对照。 */
    private boolean exists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_progression_anomalies WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private ProgressionAnomaly mapAnomaly(ResultSet result) throws SQLException {
        java.sql.Timestamp reviewedAt = result.getTimestamp("reviewed_at");
        return new ProgressionAnomaly(
                UUID.fromString(result.getString("player_uuid")),
                result.getInt("ascension"),
                result.getInt("level"),
                result.getLong("experience"),
                result.getString("reason"),
                result.getTimestamp("detected_at").toInstant(),
                result.getString("reviewer"),
                reviewedAt == null ? null : reviewedAt.toInstant()
        );
    }

    private static String bound(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
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
                throw new IllegalStateException("Progression anomaly transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open progression anomaly transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
