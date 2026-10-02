package cn.originalrpg.api.model;

import java.util.Optional;

/**
 * 七角色的权威能力表（Phase 6）。
 *
 * <p>{@link CommunityRole} 只描述「我是谁」，本类描述「我能做什么」。
 * 所有需要能力的调用点都必须经过这里，禁止在命令或监听器里重复写角色判断。</p>
 *
 * <p>双批规则来自 {@code docs/LONG_TERM_GAME_ECONOMY_PLAN.md} §「社群治理与文明」：
 * LEADER / DEPUTY / TREASURER 有审批权；BUILDER、EVENT_HOST、RECRUITER
 * 只能在各自领域发起动作，不能投票。</p>
 */
public final class CommunityRolePower {
    /** 双批审批所需的独立批准人数。 */
    public static final int REQUIRED_APPROVALS = 2;

    public enum Capability {
        /** 提交需要双批的治理动作。 */
        SUBMIT_APPROVAL,
        /** 对治理动作投出一次批准。 */
        APPROVE,
        /** 审批期间废除动作。 */
        DENY,
        /** 任命其他成员的角色。 */
        ASSIGN_ROLE
    }

    private CommunityRolePower() {
    }

    public static boolean can(CommunityRole role, Capability capability) {
        if (role == null) return false;
        return switch (capability) {
            // 投批与废除只认 canApprove()：这就是「双批需要两个治理角色」的门槛。
            case APPROVE, DENY -> role.canApprove();
            // 发起动作的门槛低于投批：领域角色可以提交本领域动作，是否够格由动作决定。
            case SUBMIT_APPROVAL -> role.canApprove() || isDomainRole(role);
            // 角色任命属于人事权，只有治理角色可以发起。
            case ASSIGN_ROLE -> role.canApprove();
        };
    }

    /** 领域执行角色：可发起本领域动作，但没有审批权。 */
    public static boolean isDomainRole(CommunityRole role) {
        return role == CommunityRole.BUILDER
                || role == CommunityRole.EVENT_HOST
                || role == CommunityRole.RECRUITER;
    }

    /** 双批按独立批准人计数，且必须先有 canApprove() 资格。 */
    public static boolean countsAsApproval(CommunityRole role) {
        return role != null && role.canApprove();
    }

    /** 审计与 i18n 使用的稳定键，不随枚举改名。 */
    public static String displayKey(CommunityRole role) {
        if (role == CommunityRole.OWNER) return "community.roles.leader";
        if (role == CommunityRole.OFFICER) return "community.roles.deputy";
        if (role == CommunityRole.TREASURER) return "community.roles.treasurer";
        if (role == CommunityRole.BUILDER) return "community.roles.builder";
        if (role == CommunityRole.EVENT_HOST) return "community.roles.event-host";
        if (role == CommunityRole.RECRUITER) return "community.roles.recruiter";
        return "community.roles.member";
    }

    /** 只有这些角色允许被任命；OWNER 只能通过既有转让流程更换。 */
    public static Optional<CommunityRole> assignable(String raw) {
        CommunityRole role = CommunityRole.parse(raw).orElse(null);
        if (role == null || role == CommunityRole.OWNER || role.canApprove()) return Optional.empty();
        return Optional.of(role);
    }
}
