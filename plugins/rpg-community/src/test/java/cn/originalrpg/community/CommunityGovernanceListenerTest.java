package cn.originalrpg.community;

import cn.originalrpg.api.event.CommunityEventCompletedEvent;
import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.service.CommunityGovernanceService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-103 行为级证据：副本通关这条真实事件链把七角色能力判定用了起来。
 *
 * <p>{@link CommunityGovernanceListener#submitEventApprovals} 真的按参与者角色决定
 * 是否发起 {@code community.event} 审批，并真的调用服务层 —— 本测试断言提交次数与提交
 * 时使用的幂等键。</p>
 */
final class CommunityGovernanceListenerTest {

    @Test
    void onlyParticipantsWhoMaySubmitStartAnEventAction() {
        UUID builder = UUID.randomUUID();
        UUID eventHost = UUID.randomUUID();
        UUID plainMember = UUID.randomUUID();
        RecordingGovernance service = new RecordingGovernance(true);

        CommunityEventCompletedEvent event = new CommunityEventCompletedEvent(
                "dungeon_ashen_vault",
                Set.of(builder, eventHost, plainMember),
                "victor",
                Map.of(builder, CommunityRole.BUILDER,
                        eventHost, CommunityRole.EVENT_HOST,
                        plainMember, CommunityRole.MEMBER));

        int submitted = new CommunityGovernanceListener(null, service, null).submitEventApprovals(event);

        // SUBMIT_APPROVAL = 治理角色 ∪ 领域角色；普通 MEMBER 不发起动作。
        assertEquals(2, submitted);
        assertEquals(2, service.submitCalls.get());
        assertEquals(Set.of(builder, eventHost), Set.copyOf(service.actors));
        // 幂等键按「副本 + 玩家」去重，重复通关不会重复计数。
        assertEquals(Set.of("community-event-dungeon_ashen_vault-" + builder,
                "community-event-dungeon_ashen_vault-" + eventHost), Set.copyOf(service.keys));
        assertEquals(2, service.submittedActions.size());
        assertTrue(service.submittedActions.stream()
                .allMatch(action -> action == CommunityGovernanceAction.COMMUNITY_EVENT));
    }

    @Test
    void unknownParticipantRoleFailsClosedAsPlainMember() {
        UUID stranger = UUID.randomUUID();
        RecordingGovernance service = new RecordingGovernance(true);
        // 事件不带角色信息：必须按 MEMBER（无提交权）处理，不得默认放行。
        CommunityEventCompletedEvent event = new CommunityEventCompletedEvent(
                "dungeon_ashen_vault", Set.of(stranger), "victor");

        assertEquals(CommunityRole.MEMBER, event.roleOf(stranger));
        assertEquals(0, new CommunityGovernanceListener(null, service, null).submitEventApprovals(event));
        assertEquals(0, service.submitCalls.get());
    }

    @Test
    void disabledOrMissingGovernanceStopsTheEventActionBeforeItReachesTheService() {
        RecordingGovernance disabled = new RecordingGovernance(false);
        UUID eventHost = UUID.randomUUID();
        CommunityEventCompletedEvent event = new CommunityEventCompletedEvent(
                "dungeon_ashen_vault", Set.of(eventHost), "victor",
                Map.of(eventHost, CommunityRole.EVENT_HOST));

        assertEquals(0, new CommunityGovernanceListener(null, disabled, null).submitEventApprovals(event));
        assertEquals(0, disabled.submitCalls.get());

        // 服务未安装（未接线）同样不得抛异常。
        assertEquals(0, new CommunityGovernanceListener(null, null, null).submitEventApprovals(event));
    }

    private static final class RecordingGovernance implements CommunityGovernanceService {
        private final AtomicInteger submitCalls = new AtomicInteger();
        private final List<UUID> actors = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private final List<CommunityGovernanceAction> submittedActions = new ArrayList<>();
        private final boolean enabled;

        private RecordingGovernance(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public CompletionStage<Boolean> can(UUID playerId, CommunityRolePower.Capability capability) {
            return CompletableFuture.completedFuture(true);
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
            actors.add(actor);
            keys.add(idempotencyKey);
            submittedActions.add(action);
            return CompletableFuture.completedFuture(new CommunityApprovalOutcome(
                    CommunityApprovalOutcome.Status.SUCCESS, action, Optional.of(subject),
                    List.of(new CommunityApprovalOutcome.GrantedApproval(actor, CommunityRole.EVENT_HOST)),
                    actor, CommunityRolePower.REQUIRED_APPROVALS));
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> approve(
                UUID actor, CommunityGovernanceAction action, String subject
        ) {
            throw new UnsupportedOperationException("本次测试只走 submit 路径");
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> approveAssignment(
                UUID actor, UUID target, String targetRole, String idempotencyKey
        ) {
            throw new UnsupportedOperationException("本次测试只走 submit 路径");
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> deny(
                UUID actor, CommunityGovernanceAction action, String subject
        ) {
            throw new UnsupportedOperationException("本次测试只走 submit 路径");
        }

        @Override
        public CompletionStage<Optional<String>> subjects(UUID playerId, CommunityGovernanceAction action) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<List<CommunityApprovalOutcome.GrantedApproval>> approvals(
                UUID playerId, CommunityGovernanceAction action, String subject
        ) {
            return CompletableFuture.completedFuture(List.of());
        }
    }
}
