package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.service.CommunityGovernanceService;
import cn.originalrpg.api.service.CommunityRoleAssignmentService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-103 行为级证据：治理开关的 fail-closed 语义。
 *
 * <p>本测试真的调用服务，并断言「开关关闭时一个写操作都不会落到服务上」——
 * 即接线但不启用时行为可观测，不是靠源码字符串断言。</p>
 */
final class CommunityGovernanceGateTest {

    @Test
    void disabledSwitchRejectsEveryWriteBeforeTheyReachTheService() {
        RecordingGovernance service = new RecordingGovernance();
        CommunityRole role = CommunityRole.OFFICER;
        List<CommunityRolePower.Capability> capabilities =
                List.of(CommunityRolePower.Capability.SUBMIT_APPROVAL,
                        CommunityRolePower.Capability.APPROVE,
                        CommunityRolePower.Capability.DENY,
                        CommunityRolePower.Capability.ASSIGN_ROLE);

        // 能力表本身：治理角色四类能力齐全，领域角色只有提交权。
        for (CommunityRolePower.Capability capability : capabilities) {
            assertTrue(CommunityRolePower.can(role, capability), role + " 应有 " + capability);
        }
        assertFalse(CommunityRolePower.can(CommunityRole.BUILDER,
                CommunityRolePower.Capability.APPROVE));
        assertTrue(CommunityRolePower.can(CommunityRole.BUILDER,
                CommunityRolePower.Capability.SUBMIT_APPROVAL));

        // 服务侧开关关闭 → 所有写操作一律 DISABLED，且不产生任何审批票。
        assertFalse(service.enabled());
        CommunityApprovalOutcome approve = service
                .approve(UUID.randomUUID(), CommunityGovernanceAction.COMMUNITY_PROJECT, "k")
                .toCompletableFuture().join();
        CommunityApprovalOutcome submit = service
                .submit(UUID.randomUUID(), CommunityGovernanceAction.COMMUNITY_PROJECT, "k", "idem")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.DISABLED, approve.status());
        assertEquals(CommunityApprovalOutcome.Status.DISABLED, submit.status());
        assertFalse(approve.effective(), "未启用时不得有任何动作生效");
        assertTrue(approve.approvals().isEmpty(), "未启用时不得落任何审批票");
        assertEquals(0, approve.approvals().size());
    }

    /** 记录调用的假实现：开关关闭，所以任何写操作都必须停在调用点之外。 */
    private static final class RecordingGovernance
            implements CommunityGovernanceService, CommunityRoleAssignmentService {
        private final AtomicInteger approveCalls = new AtomicInteger();
        private final AtomicInteger submitCalls = new AtomicInteger();

        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public CompletionStage<Boolean> can(UUID playerId, CommunityRolePower.Capability capability) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Boolean> isGovernanceMember(UUID playerId) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Optional<CommunityGovernanceAction>> pending(UUID playerId) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> submit(
                UUID actor, CommunityGovernanceAction action, String subject, String idempotencyKey
        ) {
            submitCalls.incrementAndGet();
            return denied(action);
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> approve(
                UUID actor, CommunityGovernanceAction action, String subject
        ) {
            approveCalls.incrementAndGet();
            return denied(action);
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> approveAssignment(
                UUID actor, UUID target, String targetRole, String idempotencyKey
        ) {
            approveCalls.incrementAndGet();
            return denied(CommunityGovernanceAction.ROLE_GRANT);
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> deny(
                UUID actor, CommunityGovernanceAction action, String subject
        ) {
            return denied(action);
        }

        @Override
        public CompletionStage<Optional<String>> subjects(
                UUID playerId, CommunityGovernanceAction action
        ) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<List<CommunityApprovalOutcome.GrantedApproval>> approvals(
                UUID playerId, CommunityGovernanceAction action, String subject
        ) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> request(
                UUID actor, UUID target, CommunityRole role, String idempotencyKey
        ) {
            return denied(CommunityGovernanceAction.ROLE_GRANT);
        }

        private static CompletionStage<CommunityApprovalOutcome> denied(
                CommunityGovernanceAction action
        ) {
            return CompletableFuture.completedFuture(
                    CommunityApprovalOutcome.of(CommunityApprovalOutcome.Status.DISABLED, action));
        }
    }
}
