package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityGovernanceAction;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 治理子命令的参数解析（纯逻辑，便于行为级测试）。
 *
 * <p>命令文本只在这里解释，角色能力判定一律交给
 * {@code CommunityGovernanceService}，避免命令层重复实现权限表。</p>
 */
final class CommunityGovernanceCommandRouter {
    enum Action {
        ROLE_LOOKUP,
        ROLE_LIST,
        ASSIGN,
        APPROVE,
        APPROVALS,
        DENY
    }

    record Route(Action action, String argument, CommunityGovernanceAction governanceAction) {
    }

    private CommunityGovernanceCommandRouter() {
    }

    static Optional<Route> route(String[] args) {
        // 约定：args[0] 是 /community 子命令本身，args[1..] 是它的参数。
        if (args == null || args.length < 1 || args[0] == null) return Optional.empty();
        String sub = args[0].toLowerCase(Locale.ROOT);
        String value = args.length >= 2 ? args[1] : null;
        return switch (sub) {
            case "role" -> value == null
                    ? Optional.of(new Route(Action.ROLE_LOOKUP, null, null))
                    : Optional.of(new Route(Action.ROLE_LOOKUP, value, null));
            case "roles" -> Optional.of(new Route(Action.ROLE_LIST, null, null));
            case "assign" -> value == null
                    ? Optional.empty()
                    : Optional.of(new Route(Action.ASSIGN, value,
                            CommunityGovernanceAction.ROLE_GRANT));
            case "approve" -> Optional.of(new Route(Action.APPROVE, value,
                    value == null ? null : CommunityGovernanceAction.parse(value).orElse(null)));
            case "approvals" -> Optional.of(new Route(Action.APPROVALS, value,
                    value == null ? null : CommunityGovernanceAction.parse(value).orElse(null)));
            case "deny" -> Optional.of(new Route(Action.DENY, value,
                    value == null ? null : CommunityGovernanceAction.parse(value).orElse(null)));
            default -> Optional.empty();
        };
    }

    static List<String> governanceSubcommands() {
        return List.of("role", "roles", "assign", "approve", "approvals", "deny");
    }

    static List<String> actionNames() {
        return List.of(CommunityGovernanceAction.COMMUNITY_PROJECT.key(),
                CommunityGovernanceAction.COMMUNITY_EVENT.key(),
                CommunityGovernanceAction.ROLE_GRANT.key());
    }
}
