package cn.originalrpg.core;

import cn.originalrpg.core.persistence.JdbcSessionLease;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Session Lease 读侧接线（G-102）。
 *
 * <p>玩家进入本节点时判定「本节点是否持有该玩家的有效会话租约」。
 * 判定失败只记录并拒绝准入，不做任何写租约动作——写侧属多服阶段。
 * 单实例默认关闭，见 {@code session-lease.enabled}。</p>
 */
public final class SessionLeaseListener implements Listener {
    private final JdbcSessionLease sessionLease;
    private final Logger logger;

    public SessionLeaseListener(JdbcSessionLease sessionLease, Logger logger) {
        this.sessionLease = Objects.requireNonNull(sessionLease, "sessionLease");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        JdbcSessionLease.Admission admission = sessionLease.admit(event.getPlayer().getUniqueId());
        if (admission.allowed()) return;
        // fail-closed：租约不属于本节点时拒绝该玩家，避免两个节点同时加载同一存档。
        logger.warning("Rejected join for " + event.getPlayer().getName()
                + " on node " + sessionLease.nodeId() + ": " + admission);
        event.getPlayer().kick(Component.text(switch (admission) {
            case NO_VALID_LEASE -> "会话租约缺失：本节点未持有该玩家的有效会话（多服尚未启用）。";
            case STALE_PROCESS_TOKEN -> "会话租约属于本节点旧进程，已被新的进程 token 接管。";
            case HELD_BY_OTHER_NODE -> "该玩家当前由另一个节点持有会话。";
            case ALLOWED -> throw new IllegalStateException("allowed admission cannot be rejected");
        }));
    }
}
