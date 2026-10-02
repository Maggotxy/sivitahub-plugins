package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityGovernanceAction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 治理子命令参数解析的行为级测试（真的解析输入并断言结果）。 */
final class CommunityGovernanceCommandRouterTest {

    @Test
    void roleLookupAndRoleListAreSeparated() {
        CommunityGovernanceCommandRouter.Route lookup = route("role", "treasurer").orElseThrow();
        assertEquals(CommunityGovernanceCommandRouter.Action.ROLE_LOOKUP, lookup.action());
        assertEquals("treasurer", lookup.argument());

        CommunityGovernanceCommandRouter.Route list = route("roles").orElseThrow();
        assertEquals(CommunityGovernanceCommandRouter.Action.ROLE_LIST, list.action());
    }

    @Test
    void approveAndDenyCarryTheParsedGovernanceAction() {
        assertEquals(CommunityGovernanceAction.COMMUNITY_PROJECT,
                route("approve", "community.project").orElseThrow().governanceAction());
        assertEquals(CommunityGovernanceAction.COMMUNITY_EVENT,
                route("deny", "community-event").orElseThrow().governanceAction());
        assertEquals(CommunityGovernanceAction.ROLE_GRANT,
                route("approvals", "community.role-grant").orElseThrow().governanceAction());
    }

    @Test
    void unknownArgumentsDoNotProduceAnAction() {
        assertFalse(route("approve", "not-a-real-action").orElseThrow().governanceAction() != null);
        assertFalse(route("approve").orElseThrow().governanceAction() != null);
    }

    @Test
    void nonGovernanceSubcommandsAreNotRouted() {
        assertTrue(route("status").isEmpty());
        assertTrue(route("create", "WAY", "Wayfarers").isEmpty());
        assertTrue(route("invite", "Player").isEmpty());
        assertTrue(route().isEmpty());
    }

    @Test
    void assignRequiresATarget() {
        assertEquals(CommunityGovernanceCommandRouter.Action.ASSIGN,
                route("assign", "Player").orElseThrow().action());
        assertTrue(route("assign").isEmpty());
    }

    private static Optional<CommunityGovernanceCommandRouter.Route> route(String... args) {
        return CommunityGovernanceCommandRouter.route(args);
    }
}
