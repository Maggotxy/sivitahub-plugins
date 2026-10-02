package cn.originalrpg.core.persistence;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨服玩家会话租约：**只读侧**接线（G-102 / docs/SCALE_ARCHITECTURE.md）。
 *
 * <p>文档语义（docs/SCALE_ARCHITECTURE.md「必须保持的验收不变量」）：
 * 「同一 UUID 任意时刻只有一个持有效 fencing token 的游戏节点」。
 * 本类只回答「给定玩家当前是否由本节点持租约」这一类判定问题，
 * <b>不写租约</b>——写侧（acquire / renew / handoff）要等 Velocity 多服阶段
 * 拿到真实节点身份后再落地。</p>
 *
 * <p>读侧判定 fail-closed：</p>
 * <ul>
 *   <li>无租约记录 → 不持有；</li>
 *   <li>租约已过期（{@code lease_expires_at <= now}）→ 不持有，即使 node_id 相同；</li>
 *   <li>node_id 相同但 process_token 与本地进程 token 不同 → 不持有
 *       （旧进程或替换进程不得冒充会话持有者）。</li>
 * </ul>
 */
public final class JdbcSessionLease {
    private final DataSource dataSource;
    private final String nodeId;
    private final String processToken;
    private final Clock clock;

    public JdbcSessionLease(DataSource dataSource, String nodeId, String processToken) {
        this(dataSource, nodeId, processToken, Clock.systemUTC());
    }

    public JdbcSessionLease(DataSource dataSource, String nodeId, String processToken, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.processToken = Objects.requireNonNull(processToken, "processToken");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String nodeId() {
        return nodeId;
    }

    /**
     * 该玩家此刻是否由本节点持有效租约。任何不确定都返回 false。
     */
    public boolean isLocalNodeHolder(UUID playerId) {
        return holder(playerId)
                .map(holder -> holder.nodeId().equals(nodeId)
                        && holder.processToken().equals(processToken)
                        && holder.expiresAt().isAfter(clock.instant()))
                .orElse(false);
    }

    /**
     * 租约持有者快照。无记录或已过期返回空 Optional。
     */
    public Optional<Holder> holder(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT node_id, node_process_token, lease_expires_at FROM rpg_session_leases"
                             + " WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    connection.rollback();
                    return Optional.empty();
                }
                Timestamp expiresAt = result.getTimestamp("lease_expires_at");
                String holderNode = result.getString("node_id");
                String holderToken = result.getString("node_process_token");
                connection.rollback();
                if (expiresAt == null || !expiresAt.toInstant().isAfter(clock.instant())) {
                    // 过期租约按「无有效持有者」处理：旧节点不得据此继续结算。
                    return Optional.empty();
                }
                return Optional.of(new Holder(holderNode, holderToken, expiresAt.toInstant()));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read session lease for " + playerId, exception);
        }
    }

    /**
     * 会话准入闸门：租约有效且属于本节点才放行，否则拒绝并给出原因。
     *
     * <p>这是读侧接线的真实调用点入口，见 {@code SessionLeaseListener}。</p>
     */
    public Admission admit(UUID playerId) {
        if (isLocalNodeHolder(playerId)) return Admission.ALLOWED;
        Optional<Holder> holder = holder(playerId);
        if (holder.isEmpty()) return Admission.NO_VALID_LEASE;
        return holder.get().nodeId().equals(nodeId)
                ? Admission.STALE_PROCESS_TOKEN
                : Admission.HELD_BY_OTHER_NODE;
    }

    /** 会话租约持有者。 */
    public record Holder(String nodeId, String processToken, Instant expiresAt) {
    }

    /** 读侧准入结果，供调用方决定拒绝策略。 */
    public enum Admission {
        ALLOWED,
        NO_VALID_LEASE,
        STALE_PROCESS_TOKEN,
        HELD_BY_OTHER_NODE;

        public boolean allowed() {
            return this == ALLOWED;
        }
    }
}
