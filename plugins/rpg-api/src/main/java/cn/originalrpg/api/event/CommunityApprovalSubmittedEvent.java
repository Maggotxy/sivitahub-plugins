package cn.originalrpg.api.event;

import cn.originalrpg.api.model.CommunityGovernanceAction;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

/**
 * 一条需要双批的治理动作提交后发布（当前只有第一条审批）。
 *
 * <p>监听方（例如社群模块）据此通知其他治理角色推进第二票。</p>
 */
public final class CommunityApprovalSubmittedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID communityId;
    private final UUID actor;
    private final CommunityGovernanceAction action;
    private final String subject;

    public CommunityApprovalSubmittedEvent(
            UUID communityId, UUID actor, CommunityGovernanceAction action, String subject
    ) {
        this.communityId = Objects.requireNonNull(communityId, "communityId");
        this.actor = Objects.requireNonNull(actor, "actor");
        this.action = Objects.requireNonNull(action, "action");
        this.subject = Objects.requireNonNull(subject, "subject");
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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
