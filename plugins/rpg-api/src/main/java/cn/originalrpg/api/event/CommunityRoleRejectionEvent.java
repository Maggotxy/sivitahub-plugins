package cn.originalrpg.api.event;

import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRole;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 双批审批已通过、但角色变更的前置不满足（目标不存在 / 角色不可任命 / 角色分配未安装），
 * 因此没有写入任何角色时发布。
 *
 * <p>这不是失败补偿：审批记录已按「已生效」清空，事件只用于让运营人工重放，
 * 避免出现「看起来批准了、实际上什么都没发生」的静默丢弃。</p>
 */
public final class CommunityRoleRejectionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID communityId;
    private final UUID actor;
    private final CommunityGovernanceAction action;
    private final String subject;
    private final Optional<UUID> roleTarget;
    private final Optional<CommunityRole> requestedRole;

    public CommunityRoleRejectionEvent(
            UUID communityId,
            UUID actor,
            CommunityGovernanceAction action,
            String subject,
            UUID roleTarget,
            CommunityRole requestedRole
    ) {
        this.communityId = Objects.requireNonNull(communityId, "communityId");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.action = Objects.requireNonNull(action, "action");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.roleTarget = Optional.ofNullable(roleTarget);
        this.requestedRole = Optional.ofNullable(requestedRole);
    }

    public UUID communityId() {
        return communityId;
    }

    public UUID actor() {
        return actor;
    }

    public CommunityGovernanceAction action() {
        return action;
    }

    public String subject() {
        return subject;
    }

    public Optional<UUID> roleTarget() {
        return roleTarget;
    }

    public Optional<CommunityRole> requestedRole() {
        return requestedRole;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
