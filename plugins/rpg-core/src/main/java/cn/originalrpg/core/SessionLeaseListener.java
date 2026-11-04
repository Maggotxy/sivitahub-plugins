package cn.originalrpg.core;

import cn.originalrpg.core.persistence.JdbcSessionLease;
import cn.originalrpg.api.service.MessageService;
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
    private final MessageService messages;

    public SessionLeaseListener(JdbcSessionLease sessionLease, Logger logger, MessageService messages) {
        this.sessionLease = Objects.requireNonNull(sessionLease, "sessionLease");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        JdbcSessionLease.Admission admission = sessionLease.admit(event.getPlayer().getUniqueId());
        if (admission.allowed()) return;
        // fail-closed：租约不属于本节点时拒绝该玩家，避免两个节点同时加载同一存档。
        logger.warning("Rejected join for " + event.getPlayer().getName()
                + " on node " + sessionLease.nodeId() + ": " + admission);
        event.getPlayer().kick(Component.text(messages.text(event.getPlayer(), rejectionKey(admission))));
    }

    static String rejectionKey(JdbcSessionLease.Admission admission) {
        return switch (admission) {
            case NO_VALID_LEASE -> "session-lease.no-valid-lease";
            case STALE_PROCESS_TOKEN -> "session-lease.stale-process-token";
            case HELD_BY_OTHER_NODE -> "session-lease.held-by-other-node";
            case ALLOWED -> throw new IllegalStateException("allowed admission cannot be rejected");
        };
    }
}
