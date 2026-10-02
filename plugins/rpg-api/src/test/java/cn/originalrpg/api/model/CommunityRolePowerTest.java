package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 七角色能力表的行为级测试：直接调用 {@link CommunityRole#canApprove()} /
 * {@code canModerate()} 并断言结果；治理通道准入由
 * {@link CommunityRolePower.Capability#GOVERNANCE_ACTION} 断言。
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
    void moderationFollowsTheSameThreeRoleGateAsApproval() {
        for (CommunityRole role : CommunityRole.values()) {
            assertTrue(role.canModerate() == role.canApprove(), role + " moderation 与审批口径必须一致");
        }
    }

    /**
     * G-115：治理通道准入（isGovernance）与投批权（canApprove）是两条独立口径。
     *
     * <p>断言的是「两条判定在七角色下各自取什么值」，而不是「它们相等」——
     * 相等是当前七角色定义的推论，不是不变式。谁把任一侧改成代入另一侧，
     * 这里的三组期望值就会有一条不成立。</p>
     */
    @Test
    void governanceChannelAdmissionIsItsOwnGateSeparateFromApproval() {
        // 治理角色：能进治理通道，也能投票批准。
        for (CommunityRole role : new CommunityRole[]{
                CommunityRole.OWNER, CommunityRole.OFFICER, CommunityRole.TREASURER}) {
            assertTrue(role.isGovernance(), role + " 必须是治理角色");
            assertTrue(role.canApprove(), role + " 治理角色应当能投票");
            assertTrue(CommunityRolePower.can(role, CommunityRolePower.Capability.GOVERNANCE_ACTION), role.name());
            assertTrue(CommunityRolePower.can(role, CommunityRolePower.Capability.APPROVE), role.name());
        }
        // 领域角色：不进治理通道，也不能投票。
        for (CommunityRole role : new CommunityRole[]{
                CommunityRole.BUILDER, CommunityRole.EVENT_HOST, CommunityRole.RECRUITER}) {
            assertFalse(role.isGovernance(), role + " 不是治理角色");
            assertFalse(role.canApprove(), role + " 领域角色不能投票");
            assertFalse(CommunityRolePower.can(role, CommunityRolePower.Capability.GOVERNANCE_ACTION), role.name());
            assertFalse(CommunityRolePower.can(role, CommunityRolePower.Capability.APPROVE), role.name());
            // 但可以在自己领域发起动作：准入比投批宽一档。
            assertTrue(CommunityRolePower.can(role, CommunityRolePower.Capability.SUBMIT_APPROVAL), role.name());
        }
        // 普通成员：两条口径都为假。
        assertFalse(CommunityRole.MEMBER.isGovernance());
        assertFalse(CommunityRole.MEMBER.canApprove());
        assertFalse(CommunityRolePower.can(CommunityRole.MEMBER,
                CommunityRolePower.Capability.GOVERNANCE_ACTION));
    }

    @Test
    void governanceChannelAdmissionFailsClosedForNull() {
        assertFalse(CommunityRolePower.can(null, CommunityRolePower.Capability.GOVERNANCE_ACTION));
        assertFalse(CommunityRolePower.can(null, CommunityRolePower.Capability.ASSIGN_ROLE));
    }

    /**
     * 治理通道准入也覆盖角色任命门槛：七角色下 isGovernance 与 canApprove 同真，
     * 因此断言「任命门槛仍只放行三个治理角色」，避免改口径时把任命权放宽或收紧。
     */
    @Test
    void assignRoleGateStaysOnTheGovernanceRolesOnly() {
        for (CommunityRole role : CommunityRole.values()) {
            assertTrue(CommunityRolePower.can(role, CommunityRolePower.Capability.ASSIGN_ROLE)
                    == role.isGovernance(), role + " 任命门槛必须与治理通道准入一致");
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
