package cn.originalrpg.community;

import cn.originalrpg.api.event.CommunityApprovalSubmittedEvent;
import cn.originalrpg.api.event.CommunityGovernanceActionEffectiveEvent;
import cn.originalrpg.api.event.CommunityRoleRejectionEvent;
import cn.originalrpg.api.event.CommunityEventCompletedEvent;
import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.service.CommunityGovernanceService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 6 治理事件消费者。
 *
 * <p>这里把「七角色权限判定」和「双批审批」挂到真实事件上：
 * 一次副本完成会为参与者的社群提交 {@code community.event} 工程审批，
 * 提交人自动成为第一票；第二票必须由另一名治理角色投出，动作才生效。</p>
 */
final class CommunityGovernanceListener implements Listener {
    private final RpgCommunityPlugin plugin;
    private final CommunityGovernanceService governance;
    private final MessageService messages;

    CommunityGovernanceListener(
            RpgCommunityPlugin plugin,
            CommunityGovernanceService governance,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.governance = governance;
        this.messages = messages;
    }

    /**
     * 副本完成 → 为每个参与者提交一次社群动作审批（幂等键按副本 + 玩家去重）。
     *
     * <p>返回本次真正提交的动作数量，便于行为级测试直接断言。</p>
     */
    int submitEventApprovals(CommunityEventCompletedEvent event) {
        // 接线但不启用：服务未安装或开关关闭时不得触达服务层。
        if (governance == null || !governance.enabled()) return 0;
        Set<UUID> recipients = event.participantIds();
        if (recipients.isEmpty()) return 0;
        int submitted = 0;
        for (UUID recipientId : recipients) {
            String key = "community-event-" + event.source() + "-" + recipientId;
            if (!CommunityRolePower.can(event.roleOf(recipientId),
                    CommunityRolePower.Capability.SUBMIT_APPROVAL)) {
                // 领域前置不满足的参与者不发起动作。
                continue;
            }
            submitted++;
            governance.submit(recipientId, CommunityGovernanceAction.COMMUNITY_EVENT,
                            CommunityGovernanceAction.COMMUNITY_EVENT.key(), key)
                    .whenComplete((outcome, error) -> plugin.onMain(() -> {
                        if (error != null || outcome == null) return;
                        if (outcome.recorded()) {
                            notifyApprovers(recipientId, outcome);
                        }
                    }));
        }
        return submitted;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommunityEventCompleted(CommunityEventCompletedEvent event) {
        submitEventApprovals(event);
    }

    /** 第二票达成 → 通知社群里的治理角色动作已生效。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffective(CommunityGovernanceActionEffectiveEvent event) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(Component.text(messages.text(online, "community.governance.effective",
                    Map.of("action", event.action().key(),
                            "player", name(event.actor()))), NamedTextColor.GREEN));
        }
    }

    /**
     * 双批通过但角色没落地 → 通知在线治理角色，人工重放。
     *
     * <p>发布者：{@code JdbcCommunityService.decide()}。没有这条消费，
     * 「审批耗完但角色没变」对玩家就是不可观测的。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRoleRejection(CommunityRoleRejectionEvent event) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(Component.text(messages.text(online, "community.governance.role-rejected",
                    Map.of("action", event.action().key(),
                            "player", name(event.actor()))), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSubmitted(CommunityApprovalSubmittedEvent event) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(event.actor())) continue;
            online.sendMessage(Component.text(messages.text(online, "community.governance.submitted",
                    Map.of("action", event.action().key(),
                            "player", name(event.actor()))), NamedTextColor.YELLOW));
        }
    }

    private void notifyApprovers(UUID actorId, CommunityApprovalOutcome outcome) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(actorId)) continue;
            online.sendMessage(Component.text(messages.text(online, "community.governance.pending-hint",
                    Map.of("action", outcome.action().key())), NamedTextColor.GOLD));
        }
    }

    private static String name(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player == null ? playerId.toString() : player.getName();
    }
}
