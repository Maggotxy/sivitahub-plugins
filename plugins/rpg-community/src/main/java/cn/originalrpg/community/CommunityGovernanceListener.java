package cn.originalrpg.community;

import cn.originalrpg.api.event.CommunityApprovalSubmittedEvent;
import cn.originalrpg.api.event.CommunityGovernanceActionEffectiveEvent;
import cn.originalrpg.api.event.CommunityRoleRejectionEvent;
import cn.originalrpg.api.event.CommunityEventCompletedEvent;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Phase 6 治理事件消费者。
 *
 * <p>这里把「七角色权限判定」和「双批审批」挂到真实事件上：
 * 一次副本完成会为参与者的社群提交 {@code community.event} 工程审批，
 * 提交人自动成为第一票；第二票必须由另一名治理角色投出，动作才生效。</p>
 */
final class CommunityGovernanceListener implements Listener {
    private final CommunityGovernanceService governance;
    private final Consumer<Runnable> onMain;
    private final BiConsumer<UUID, GovernanceNotice> deliver;
    private final Consumer<Throwable> reportFailure;

    CommunityGovernanceListener(
            RpgCommunityPlugin plugin,
            CommunityGovernanceService governance,
            MessageService messages
    ) {
        this(governance, action -> plugin.onMain(action), (recipient, notice) -> {
            Player player = Bukkit.getPlayer(recipient);
            // Authentication and online-session checks happen on the server thread,
            // after the asynchronous authoritative audience read has completed.
            if (!plugin.isAuthenticated(player)) return;
            player.sendMessage(Component.text(messages.text(player, notice.key(),
                    Map.of("action", notice.action().key(), "player", name(notice.actor()))), notice.color()));
        }, error -> plugin.getLogger().warning("Community governance notification failed: " + error));
    }

    CommunityGovernanceListener(
            CommunityGovernanceService governance,
            Consumer<Runnable> onMain,
            BiConsumer<UUID, GovernanceNotice> deliver,
            Consumer<Throwable> reportFailure
    ) {
        this.governance = governance;
        this.onMain = onMain;
        this.deliver = deliver;
        this.reportFailure = reportFailure;
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
                    .whenComplete((outcome, error) -> {
                        if (error != null) reportFailure.accept(error);
                        // The committed CommunityApprovalSubmittedEvent is the
                        // single notification path; do not send a second hint here.
                    });
        }
        return submitted;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommunityEventCompleted(CommunityEventCompletedEvent event) {
        submitEventApprovals(event);
    }

    /** Notify only this community's current governance members. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffective(CommunityGovernanceActionEffectiveEvent event) {
        notifyCommunity(event.communityId(), null, new GovernanceNotice(
                "community.governance.effective", event.actor(), event.action(), NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRoleRejection(CommunityRoleRejectionEvent event) {
        notifyCommunity(event.communityId(), null, new GovernanceNotice(
                "community.governance.role-rejected", event.actor(), event.action(), NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSubmitted(CommunityApprovalSubmittedEvent event) {
        notifyCommunity(event.communityId(), event.actor(), new GovernanceNotice(
                "community.governance.submitted", event.actor(), event.action(), NamedTextColor.YELLOW));
    }

    private void notifyCommunity(UUID communityId, UUID excludedActor, GovernanceNotice notice) {
        if (governance == null || !governance.enabled()) return;
        governance.notificationRecipients(communityId).whenComplete((recipients, error) -> {
            if (error != null) {
                reportFailure.accept(error);
                return;
            }
            if (recipients == null || recipients.isEmpty()) return;
            onMain.accept(() -> {
                if (!governance.enabled()) return;
                for (UUID recipient : recipients) {
                    if (!recipient.equals(excludedActor)) deliver.accept(recipient, notice);
                }
            });
        });
    }

    record GovernanceNotice(String key, UUID actor, CommunityGovernanceAction action, NamedTextColor color) {
    }

    private static String name(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        return player == null ? playerId.toString() : player.getName();
    }
}
