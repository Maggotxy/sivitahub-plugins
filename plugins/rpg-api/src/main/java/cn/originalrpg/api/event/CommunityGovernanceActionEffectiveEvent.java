package cn.originalrpg.api.event;

import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRole;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 双批审批达成、动作真正生效后发布。
 *
 * <p>这是「提交 → 两个不同角色的批准 → 生效」链条的唯一生效通知点。</p>
 */
public final class CommunityGovernanceActionEffectiveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID communityId;
    private final UUID actor;
    private final CommunityGovernanceAction action;
    private final String subject;
    private final Optional<UUID> roleTarget;
    private final Optional<CommunityRole> appliedRole;

    public CommunityGovernanceActionEffectiveEvent(
            UUID communityId,
            UUID actor,
            CommunityGovernanceAction action,
            String subject,
            UUID roleTarget,
            CommunityRole appliedRole
    ) {
        this.communityId = Objects.requireNonNull(communityId, "communityId");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.action = Objects.requireNonNull(action, "action");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.roleTarget = Optional.ofNullable(roleTarget);
        this.appliedRole = Optional.ofNullable(appliedRole);
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

    public Optional<CommunityRole> appliedRole() {
        return appliedRole;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
