package cn.originalrpg.community;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.service.CommunityGovernanceService;
import cn.originalrpg.api.service.CommunityRoleAssignmentService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-103 行为级证据（rpg-community 侧）：七角色能力判定与双批审批接到真实命令路径上。
 *
 * <p>本测试真的执行 {@link CommunityGovernanceHandler} 的写操作，断言渲染出的文案键
 * 与服务层收到的调用，不做源码字符串断言。</p>
 */
final class CommunityGovernanceHandlerTest {
    private static final String DISABLED = "community.governance.disabled";

    // ---------------------------------------------------------------
    // ① 能力判定真的被用上：canApprove() 为假的角色批不了
    // ---------------------------------------------------------------

    @Test
    void aRoleWithoutApproveCapabilityCannotApproveThroughTheCommandPath() {
        Fixture fixture = new Fixture();
        UUID builder = UUID.randomUUID();
        // 服务层反馈：BUILDER 没有 APPROVE 能力（与 CommunityRolePower 同源）。
        fixture.roles.put(builder, CommunityRole.BUILDER);

        CommunityGovernanceHandler.Rendering out = fixture.handler
                .approve(builder, CommunityGovernanceAction.COMMUNITY_PROJECT)
                .join();

        assertTrue(out.keys().contains("community.governance.not-allowed"), out.keys().toString());
        assertEquals(0, fixture.service.approveCalls.get(), "无能力时不得把投批发到服务层");
    }

    @Test
    void aGovernanceRoleReachesTheServiceWithTheRealPendingSubject() {
        Fixture fixture = new Fixture();
        UUID treasurer = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        fixture.roles.put(treasurer, CommunityRole.TREASURER);
        fixture.roles.put(owner, CommunityRole.OWNER);
        fixture.service.pending.put(CommunityGovernanceAction.ROLE_GRANT, owner.toString());

        CommunityGovernanceHandler.Rendering out = fixture.handler
                .approve(treasurer, CommunityGovernanceAction.ROLE_GRANT)
                .join();

        // 双批：第二票必须落在库里记录的真实目标（目标玩家 UUID），不是动作键。
        assertEquals(List.of(owner.toString()), fixture.service.approvedSubjects);
        assertEquals(CommunityApprovalOutcome.Status.NO_PENDING_ACTION, fixture.service.lastOutcome.status());
        assertTrue(out.keys().contains("community.governance.no-pending-action"));
        assertEquals(1, fixture.capability.calls.get());
    }

    // ---------------------------------------------------------------
    // ② 双批：提交 → 两个不同角色的批准 → 生效
    // ---------------------------------------------------------------

    @Test
    void twoDistinctGovernanceRolesAreReportedAsPendingUntilTheActionBecomesEffective() {
        Fixture fixture = new Fixture();
        UUID treasurer = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        fixture.roles.put(treasurer, CommunityRole.TREASURER);
        fixture.roles.put(officer, CommunityRole.OFFICER);
        fixture.service.pending.put(CommunityGovernanceAction.COMMUNITY_PROJECT,
                CommunityGovernanceAction.COMMUNITY_PROJECT.key());

        // 第一票：只有一条记录，尚不生效，命令层不应报生效。
        fixture.service.outcomes.add(new CommunityApprovalOutcome(
                CommunityApprovalOutcome.Status.SUCCESS, CommunityGovernanceAction.COMMUNITY_PROJECT,
                Optional.of(CommunityGovernanceAction.COMMUNITY_PROJECT.key()),
                List.of(new CommunityApprovalOutcome.GrantedApproval(treasurer, CommunityRole.TREASURER)),
                treasurer, CommunityRolePower.REQUIRED_APPROVALS));
        CommunityGovernanceHandler.Rendering first = fixture.handler
                .approve(treasurer, CommunityGovernanceAction.COMMUNITY_PROJECT).join();
        assertTrue(first.keys().contains("community.governance.success"));
        assertFalse(first.keys().contains("community.governance.effective"), "单票不得生效");

        // 第二票来自另一个治理角色 → 生效。
        fixture.service.outcomes.add(new CommunityApprovalOutcome(
                CommunityApprovalOutcome.Status.SUCCESS, CommunityGovernanceAction.COMMUNITY_PROJECT,
                Optional.of(CommunityGovernanceAction.COMMUNITY_PROJECT.key()),
                List.of(new CommunityApprovalOutcome.GrantedApproval(treasurer, CommunityRole.TREASURER),
                        new CommunityApprovalOutcome.GrantedApproval(officer, CommunityRole.OFFICER)),
                officer, CommunityRolePower.REQUIRED_APPROVALS));
        CommunityGovernanceHandler.Rendering second = fixture.handler
                .approve(officer, CommunityGovernanceAction.COMMUNITY_PROJECT).join();
        assertTrue(second.keys().contains("community.governance.effective"), "双批达成必须报生效");
        assertEquals(2, fixture.service.approveCalls.get());
    }

    @Test
    void theSamePersonCannotSupplyTwoApprovalsAndTheCommandSaysSo() {
        Fixture fixture = new Fixture();
        UUID owner = UUID.randomUUID();
        fixture.roles.put(owner, CommunityRole.OWNER);
        fixture.service.pending.put(CommunityGovernanceAction.COMMUNITY_PROJECT,
                CommunityGovernanceAction.COMMUNITY_PROJECT.key());
        // 服务层按独立批准人计数：同一个人第二条票返回 ALREADY_APPROVED。
        fixture.service.outcomes.add(new CommunityApprovalOutcome(
                CommunityApprovalOutcome.Status.ALREADY_APPROVED, CommunityGovernanceAction.COMMUNITY_PROJECT,
                Optional.of(CommunityGovernanceAction.COMMUNITY_PROJECT.key()),
                List.of(new CommunityApprovalOutcome.GrantedApproval(owner, CommunityRole.OWNER)),
                owner, CommunityRolePower.REQUIRED_APPROVALS));

        CommunityGovernanceHandler.Rendering out = fixture.handler
                .approve(owner, CommunityGovernanceAction.COMMUNITY_PROJECT).join();

        assertTrue(out.keys().contains("community.governance.already-approved"), out.keys().toString());
        assertFalse(out.keys().contains("community.governance.effective"));
    }

    // ---------------------------------------------------------------
    // ③ 七角色的领域前置：BUILDER / EVENT_HOST / RECRUITER 有各自的位置
    // ---------------------------------------------------------------

    @Test
    void domainRolesCanSubmitTheirOwnActionButStillCannotApprove() {
        Fixture fixture = new Fixture();
        UUID eventHost = UUID.randomUUID();
        fixture.roles.put(eventHost, CommunityRole.EVENT_HOST);

        assertTrue(fixture.handler.assignableRoleNames().contains(CommunityRole.EVENT_HOST.name()));
        assertTrue(fixture.handler.assignableRoleNames().contains(CommunityRole.RECRUITER.name()));
        assertFalse(fixture.handler.assignableRoleNames().contains(CommunityRole.OWNER.name()));

        // 领域角色能提交本领域动作（能力表判定），但不能投批。
        assertTrue(CommunityRolePower.can(CommunityRole.EVENT_HOST,
                CommunityRolePower.Capability.SUBMIT_APPROVAL));
        assertFalse(CommunityRolePower.can(CommunityRole.EVENT_HOST,
                CommunityRolePower.Capability.APPROVE));
        assertTrue(CommunityGovernanceAction.COMMUNITY_EVENT.requiredRole().orElseThrow()
                == CommunityRole.EVENT_HOST);

        CommunityGovernanceHandler.Rendering denied = fixture.handler
                .approve(eventHost, CommunityGovernanceAction.COMMUNITY_EVENT).join();
        assertTrue(denied.keys().contains("community.governance.not-allowed"));
        assertEquals(0, fixture.service.approveCalls.get());
    }

    @Test
    void roleListingRendersTheCapabilityOfEveryOneOfTheSevenRoles() {
        Fixture fixture = new Fixture();
        assertEquals(7, CommunityRole.values().length, "Phase 6 是七角色");

        // 逐角色断言它被渲染成了哪一类能力行；文案键是稳定键，不随枚举改名。
        for (CommunityRole role : CommunityRole.values()) {
            CommunityGovernanceHandler.Rendering single = fixture.handler.roleLookup(role.name());
            assertEquals(List.of("community.governance.role-line"), single.keys());
            assertTrue(single.messages().contains(new CommunityGovernanceHandler.Rendering.Message(
                    "community.governance.role-line", Map.of(
                    "roleKey", CommunityRolePower.displayKey(role),
                    "detailKey", role.canApprove() ? "community.governance.role-approver"
                            : CommunityRolePower.isDomainRole(role) ? "community.governance.role-domain"
                            : "community.governance.role-member",
                    "moderateKey", role.canModerate()
                            ? "community.governance.moderator" : "community.governance.non-moderator"))),
                    "角色行渲染错误: " + role);
        }

        // 不传参数时列出全部七个角色的能力行（同键多值，按插入顺序保留）。
        List<String> rendered = new ArrayList<>();
        fixture.handler.roleLookup(null).messages().forEach(line ->
                rendered.add(String.valueOf(line.placeholders().get("roleKey"))));
        assertEquals(List.of(
                        "community.roles.leader", "community.roles.deputy", "community.roles.treasurer",
                        "community.roles.builder", "community.roles.event-host",
                        "community.roles.recruiter", "community.roles.member"),
                rendered);

        CommunityGovernanceHandler.Rendering single = fixture.handler.roleLookup("treasurer");
        assertEquals(List.of("community.governance.role-line"), single.keys());
        assertTrue(single.messages().contains(new CommunityGovernanceHandler.Rendering.Message(
                "community.governance.role-line", Map.of(
                "roleKey", "community.roles.treasurer",
                "detailKey", "community.governance.role-approver",
                "moderateKey", "community.governance.moderator"))));

        CommunityGovernanceHandler.Rendering unknown = fixture.handler.roleLookup("not-a-role");
        assertTrue(unknown.keys().contains("community.governance.role-unknown"));
    }

    // ---------------------------------------------------------------
    // ④ 接线但不启用：开关关闭时一个写操作都进不去
    // ---------------------------------------------------------------

    @Test
    void disabledSwitchStopsEveryWriteBeforeItReachesTheService() {
        Fixture fixture = new Fixture(false);
        UUID owner = UUID.randomUUID();
        fixture.roles.put(owner, CommunityRole.OWNER);
        fixture.service.pending.put(CommunityGovernanceAction.COMMUNITY_PROJECT,
                CommunityGovernanceAction.COMMUNITY_PROJECT.key());

        assertEquals(List.of(DISABLED), fixture.handler
                .approve(owner, CommunityGovernanceAction.COMMUNITY_PROJECT).join().keys());
        assertEquals(List.of(DISABLED), fixture.handler
                .deny(owner, CommunityGovernanceAction.COMMUNITY_PROJECT).join().keys());
        assertEquals(List.of(DISABLED), fixture.handler
                .approvals(owner, CommunityGovernanceAction.COMMUNITY_PROJECT).join().keys());
        assertEquals(List.of(DISABLED), fixture.handler
                .assign(owner, UUID.randomUUID(), "role-grant-off").join().keys());

        assertEquals(0, fixture.service.approveCalls.get());
        assertEquals(0, fixture.service.denyCalls.get());
        assertEquals(0, fixture.service.approvalsCalls.get());
        assertEquals(0, fixture.roleAssignments.requestCalls.get());
        assertEquals(0, fixture.capability.calls.get(), "未启用时连能力查询都不该发生");

        // 能力表展示不写库，因此未启用时仍可用（只读，不影响玩家）。
        assertFalse(fixture.handler.roleLookup(null).keys().isEmpty());
    }

    @Test
    void missingServiceProviderAlsoFailsClosedInsteadOfThrowing() {
        Fixture fixture = new Fixture(true, false);
        assertFalse(fixture.handler.available());
        assertEquals(List.of(DISABLED), fixture.handler
                .approve(UUID.randomUUID(), CommunityGovernanceAction.COMMUNITY_PROJECT).join().keys());
    }

    @Test
    void unknownActionNameStillAnswersInsteadOfSilentlyDoingNothing() {
        Fixture fixture = new Fixture();
        assertEquals(List.of("community.governance.action-unknown"),
                fixture.handler.approve(UUID.randomUUID(), null).join().keys());
    }

    // ---------------------------------------------------------------

    private static final class Fixture {
        private final Map<UUID, CommunityRole> roles = new HashMap<>();
        private final RecordingCapability capability = new RecordingCapability(roles);
        private final RecordingGovernance service = new RecordingGovernance();
        private final RecordingAssignments roleAssignments = new RecordingAssignments();
        private final CommunityGovernanceHandler handler;

        Fixture() {
            this(true, true);
        }

        Fixture(boolean enabled) {
            this(enabled, true);
        }

        Fixture(boolean enabled, boolean withService) {
            service.roles = roles;
            this.handler = new CommunityGovernanceHandler(
                    withService ? service : null,
                    withService ? roleAssignments : null,
                    capability::can, enabled);
        }
    }

    /** 能力查询直接复用被测试模块依赖的能力表，不复制角色判断。 */
    private static final class RecordingCapability {
        private final Map<UUID, CommunityRole> roles;
        private final AtomicInteger calls = new AtomicInteger();

        private RecordingCapability(Map<UUID, CommunityRole> roles) {
            this.roles = roles;
        }

        CompletableFuture<Boolean> can(UUID playerId, CommunityRolePower.Capability capability) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    CommunityRolePower.can(roles.get(playerId), capability));
        }
    }

    private static final class RecordingGovernance implements CommunityGovernanceService {
        private final AtomicInteger approveCalls = new AtomicInteger();
        private final AtomicInteger denyCalls = new AtomicInteger();
        private final AtomicInteger approvalsCalls = new AtomicInteger();
        private final Map<CommunityGovernanceAction, String> pending = new HashMap<>();
        private final List<CommunityApprovalOutcome> outcomes = new ArrayList<>();
        private final List<String> approvedSubjects = new ArrayList<>();
        private Map<UUID, CommunityRole> roles = new HashMap<>();
        private CommunityApprovalOutcome lastOutcome =
                CommunityApprovalOutcome.of(CommunityApprovalOutcome.Status.NO_PENDING_ACTION,
                        CommunityGovernanceAction.COMMUNITY_PROJECT);

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CompletionStage<Boolean> can(UUID playerId, CommunityRolePower.Capability capability) {
            return CompletableFuture.completedFuture(CommunityRolePower.can(roles.get(playerId), capability));
        }

        @Override
        public CompletionStage<Optional<CommunityGovernanceAction>> pending(UUID playerId) {
            return CompletableFuture.completedFuture(pending.keySet().stream().findFirst());
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> submit(
                UUID actor, CommunityGovernanceAction action, String subject, String idempotencyKey
        ) {
            throw new UnsupportedOperationException("本次测试不经过 submit");
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> approve(
                UUID actor, CommunityGovernanceAction action, String subject
        ) {
            approveCalls.incrementAndGet();
            approvedSubjects.add(subject);
            lastOutcome = outcomes.isEmpty()
                    ? CommunityApprovalOutcome.of(CommunityApprovalOutcome.Status.NO_PENDING_ACTION, action)
                    : outcomes.remove(0);
            return CompletableFuture.completedFuture(lastOutcome);
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> approveAssignment(
                UUID actor, UUID target, String targetRole, String idempotencyKey
        ) {
            return approve(actor, CommunityGovernanceAction.ROLE_GRANT, target.toString());
        }

        @Override
        public CompletionStage<CommunityApprovalOutcome> deny(
                UUID actor, CommunityGovernanceAction action, String subject
        ) {
            denyCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    CommunityApprovalOutcome.of(CommunityApprovalOutcome.Status.SUCCESS, action));
        }

        @Override
        public CompletionStage<Optional<String>> subjects(UUID playerId, CommunityGovernanceAction action) {
            return CompletableFuture.completedFuture(Optional.ofNullable(pending.get(action)));
        }

        @Override
        public CompletionStage<List<CommunityApprovalOutcome.GrantedApproval>> approvals(
                UUID playerId, CommunityGovernanceAction action, String subject
        ) {
            approvalsCalls.incrementAndGet();
            if (!pending.containsKey(action)) return CompletableFuture.completedFuture(List.of());
            return CompletableFuture.completedFuture(List.of(
                    new CommunityApprovalOutcome.GrantedApproval(playerId,
                            roles.getOrDefault(playerId, CommunityRole.MEMBER))));
        }
    }

    private static final class RecordingAssignments implements CommunityRoleAssignmentService {
        private final AtomicInteger requestCalls = new AtomicInteger();

        @Override
        public CompletionStage<CommunityApprovalOutcome> request(
                UUID actor, UUID target, CommunityRole role, String idempotencyKey
        ) {
            requestCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new CommunityApprovalOutcome(
                    CommunityApprovalOutcome.Status.SUCCESS, CommunityGovernanceAction.ROLE_GRANT,
                    Optional.of(target.toString()),
                    List.of(new CommunityApprovalOutcome.GrantedApproval(actor, CommunityRole.OWNER)),
                    actor, CommunityRolePower.REQUIRED_APPROVALS));
        }
    }
}
