package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 七角色能力表的行为级测试：直接调用 {@link CommunityRole#canApprove()} /
 * {@code canModerate()} / {@code isGovernance()} 并断言结果。
 */
final class CommunityRolePowerTest {

    @Test
    void onlyTheThreeGovernanceRolesCanApprove() {
        assertTrue(CommunityRole.LEADER.canApprove());
        assertTrue(CommunityRole.DEPUTY.canApprove());
        assertTrue(CommunityRole.TREASURER.canApprove());

        assertFalse(CommunityRole.BUILDER.canApprove());
        assertFalse(CommunityRole.EVENT_HOST.canApprove());
        assertFalse(CommunityRole.RECRUITER.canApprove());
        assertFalse(CommunityRole.MEMBER.canApprove());
    }

    @Test
    void moderationAndGovernanceFollowTheSameThreeRoleGate() {
        for (CommunityRole role : CommunityRole.values()) {
            assertTrue(role.canModerate() == role.canApprove(), role + " moderation 与审批口径必须一致");
            assertTrue(role.isGovernance() == role.canApprove(), role + " governance 与审批口径必须一致");
        }
    }

    @Test
    void approvalsAreCountedOnlyForApprovingRoles() {
        assertTrue(CommunityRolePower.countsAsApproval(CommunityRole.LEADER));
        assertTrue(CommunityRolePower.countsAsApproval(CommunityRole.TREASURER));
        assertFalse(CommunityRolePower.countsAsApproval(CommunityRole.BUILDER));
        assertFalse(CommunityRolePower.countsAsApproval(CommunityRole.EVENT_HOST));
        assertFalse(CommunityRolePower.countsAsApproval(CommunityRole.MEMBER));
        assertFalse(CommunityRolePower.countsAsApproval(null));
    }

    @Test
    void domainRolesCanSubmitButCannotApproveOrAssign() {
        for (CommunityRole role : new CommunityRole[]{
                CommunityRole.BUILDER, CommunityRole.EVENT_HOST, CommunityRole.RECRUITER}) {
            assertTrue(CommunityRolePower.can(role, CommunityRolePower.Capability.SUBMIT_APPROVAL), role.name());
            assertFalse(CommunityRolePower.can(role, CommunityRolePower.Capability.APPROVE), role.name());
            assertFalse(CommunityRolePower.can(role, CommunityRolePower.Capability.ASSIGN_ROLE), role.name());
        }
    }

    @Test
    void membersHaveNoGovernanceCapabilityAtAll() {
        for (CommunityRolePower.Capability capability : CommunityRolePower.Capability.values()) {
            assertFalse(CommunityRolePower.can(CommunityRole.MEMBER, capability), capability.name());
        }
    }

    @Test
    void ownerIsNotAssignableThroughTheApprovalPath() {
        assertTrue(CommunityRolePower.assignable("OWNER").isEmpty());
        assertTrue(CommunityRolePower.assignable("LEADER").isEmpty());
        assertTrue(CommunityRolePower.assignable("TREASURER").isEmpty());
        assertTrue(CommunityRolePower.assignable("not-a-role").isEmpty());
        assertTrue(CommunityRolePower.assignable(null).isEmpty());
        assertTrue(CommunityRolePower.assignable("recruiter").isPresent());
        assertTrue(CommunityRolePower.assignable("EVENT-HOST").isPresent());
    }

    @Test
    void parsingAcceptsAliasesAndRejectsGarbage() {
        assertTrue(CommunityRole.parse("LEADER").orElseThrow() == CommunityRole.OWNER);
        assertTrue(CommunityRole.parse("deputy").orElseThrow() == CommunityRole.OFFICER);
        assertTrue(CommunityRole.parse("event-host").orElseThrow() == CommunityRole.EVENT_HOST);
        assertTrue(CommunityRole.parse("  BUILDER  ").orElseThrow() == CommunityRole.BUILDER);
        assertTrue(CommunityRole.parse("nobody").isEmpty());
        assertTrue(CommunityRole.parse("").isEmpty());
        assertTrue(CommunityRole.parse(null).isEmpty());
    }

    @Test
    void everyRoleHasADistinctDisplayKey() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (CommunityRole role : CommunityRole.values()) {
            assertTrue(keys.add(CommunityRolePower.displayKey(role)), role + " 的展示键重复");
        }
    }
}
