package cn.originalrpg.api.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Phase 6 需要双人审批的治理动作。
 *
 * <p>每个动作声明它的真实前置（例如城市工程需要 BUILDER，社群活动需要 EVENT_HOST），
 * 双批审批只负责「收集两个不同角色的批准」，门槛判定仍由
 * {@link CommunityRolePower} 与 {@link #requiredRole()} 决定。</p>
 */
public enum CommunityGovernanceAction {
    ROLE_GRANT("community.role-grant", null),
    COMMUNITY_PROJECT("community.project", CommunityRole.BUILDER),
    COMMUNITY_EVENT("community.event", CommunityRole.EVENT_HOST);

    private final String key;
    private final CommunityRole requiredRole;

    CommunityGovernanceAction(String key, CommunityRole requiredRole) {
        this.key = key;
        this.requiredRole = requiredRole;
    }

    /** 审计与 i18n 使用的稳定键，不随枚举改名。 */
    public String key() {
        return key;
    }

    /** 提交该动作所必需的角色资格；为 null 表示治理成员即可提交。 */
    public Optional<CommunityRole> requiredRole() {
        return Optional.ofNullable(requiredRole);
    }

    public static Optional<CommunityGovernanceAction> parse(String raw) {
        if (raw == null) return Optional.empty();
        String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (CommunityGovernanceAction action : values()) {
            if (action.key.equals(normalized) || action.name().toLowerCase(Locale.ROOT).replace('_', '-')
                    .equals(normalized)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
