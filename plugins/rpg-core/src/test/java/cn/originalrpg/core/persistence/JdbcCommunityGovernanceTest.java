package cn.originalrpg.core.persistence;

import cn.originalrpg.api.event.CommunityApprovalSubmittedEvent;
import cn.originalrpg.api.event.CommunityGovernanceActionEffectiveEvent;
import cn.originalrpg.api.event.CommunityRoleRejectionEvent;
import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-103 行为级证据：七角色能力判定 + 双批审批 + 审计落库。
 *
 * <p>真的开事务执行服务方法并断言结果与库内记录，不做源码字符串断言。</p>
 */
class JdbcCommunityGovernanceTest {
    private static final String PERIOD = "2026-W32";
    private static final BigDecimal GOAL = new BigDecimal("250000.00");

    private CommunityDatabaseFixture fixture;
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcCommunityService communities;
    private final List<Event> published = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        fixture = new CommunityDatabaseFixture();
        dataSource = fixture.dataSource;
        executor = Executors.newVirtualThreadPerTaskExecutor();
        communities = new JdbcCommunityService(dataSource, executor, null, true, published::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executor != null) executor.close();
        if (fixture != null) fixture.close();
    }

    // ---------------------------------------------------------------
    // G-103 ② 七角色权限判定接到真实调用点
    // ---------------------------------------------------------------

    @Test
    void roleCapabilitiesDecideWhatEachRoleMayDo() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        UUID member = join(owner, "WAY");

        // canApprove() 为真的角色能批准；为假的角色不能。
        assertTrue(canApprove(owner));
        assertTrue(canApprove(treasurer));
        assertFalse(canApprove(builder));
        assertFalse(canApprove(member));

        assertTrue(can(owner, CommunityRolePower.Capability.SUBMIT_APPROVAL));
        assertTrue(can(treasurer, CommunityRolePower.Capability.ASSIGN_ROLE));
        assertFalse(can(builder, CommunityRolePower.Capability.APPROVE));
        assertFalse(can(member, CommunityRolePower.Capability.SUBMIT_APPROVAL));

        // MEMBER 有提交权都没有：投批必须被拒，且不写审计记录。
        CommunityOperationResult ignored = communities.create(
                player(9), "Other", "OTH", 10, 1, BigDecimal.ZERO, BigDecimal.ZERO, "create-other")
                .toCompletableFuture().join();
        assertNotEquals(CommunityOperationResult.Status.INVALID, ignored.status());
        CommunityApprovalOutcome rejected = communities
                .approve(member, CommunityGovernanceAction.COMMUNITY_PROJECT, "way-project")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED, rejected.status());
        assertEquals(0, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));

        // BUILDER 可以发起工程（提交即第一票），但不能投出第二票：
        // 同一名 BUILDER 再调一次必须报 ALREADY_APPROVED，而不是把票算成两票。
        CommunityApprovalOutcome submittedByBuilder = communities
                .approve(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, "way-project")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, submittedByBuilder.status());
        assertFalse(submittedByBuilder.effective());
        CommunityApprovalOutcome twiceByBuilder = communities
                .approve(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, "way-project")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.ALREADY_APPROVED, twiceByBuilder.status());
        assertEquals(1, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    @Test
    void requiredRoleGateRejectsSubmissionsFromUnqualifiedGovernanceRoles() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);

        // community.project 需要 BUILDER：TREASURER 可批准但不是 BUILDER。
        CommunityApprovalOutcome byTreasurer = communities.submit(treasurer,
                        CommunityGovernanceAction.COMMUNITY_PROJECT,
                        CommunityGovernanceAction.COMMUNITY_PROJECT.key(), "project-by-treasurer")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED, byTreasurer.status());

        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        CommunityApprovalOutcome byBuilder = communities.submit(builder,
                        CommunityGovernanceAction.COMMUNITY_PROJECT,
                        CommunityGovernanceAction.COMMUNITY_PROJECT.key(), "project-by-builder")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, byBuilder.status());
        assertEquals(1, byBuilder.approvals().size());
        assertEquals(1, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    // ---------------------------------------------------------------
    // G-103 ② 提交资格与审批资格是两条不同的判定
    // ---------------------------------------------------------------

    @Test
    void submissionNeedsTheActionRoleButTheSecondVoteNeedsApproveCapability() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        UUID recruiter = join(owner, "WAY", CommunityRole.RECRUITER);
        UUID member = join(owner, "WAY");
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        // BUILDER 能发起工程（提交即第一票），但没有 canApprove()。
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS,
                communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                        .toCompletableFuture().join().status());
        assertFalse(CommunityRolePower.can(CommunityRole.BUILDER,
                CommunityRolePower.Capability.APPROVE));

        // RECRUITER / MEMBER 都没有 APPROVE，投第二票必须被拒且不写票。
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED, communities
                .approve(recruiter, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join().status());
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED, communities
                .approve(member, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join().status());
        assertEquals(1, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));

        // TREASURER 有 APPROVE → 第二票成立，动作生效。
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);
        CommunityApprovalOutcome effective = communities
                .approve(treasurer, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, effective.status());
        assertTrue(effective.effective());
    }

    @Test
    void aDomainRoleCannotBeTheSecondVoteForItsOwnAction() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        // 工程由 BUILDER 发起；BUILDER 的第二票是重复票，不是「双批成立」。
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, communities
                .submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                .toCompletableFuture().join().status());
        CommunityApprovalOutcome again = communities
                .approve(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.ALREADY_APPROVED, again.status());
        assertFalse(again.effective(), "同一个人不能凑两次批准");
        assertEquals(1, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    // ---------------------------------------------------------------
    // G-103 ③ 双批：提交 → 两个不同角色的批准 → 生效
    // ---------------------------------------------------------------

    @Test
    void twoDistinctGovernanceRolesApproveBeforeTheActionBecomesEffective() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);

        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();
        CommunityApprovalOutcome submitted = communities.submit(builder,
                        CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, submitted.status());
        assertFalse(submitted.effective(), "单票不能生效");

        // 第二次批准来自另一个治理角色 → 双批达成。
        CommunityApprovalOutcome effective = communities
                .approve(treasurer, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, effective.status());
        assertTrue(effective.effective(), "两个不同角色的批准必须让动作生效");
        assertEquals(2, effective.approvals().size());

        // 生效事件已发布，审计记录被清空（不留下 PENDING 残留）。
        assertTrue(published.stream().anyMatch(event ->
                event instanceof CommunityGovernanceActionEffectiveEvent));
        assertTrue(published.stream().anyMatch(event ->
                event instanceof CommunityApprovalSubmittedEvent));
        assertEquals(0, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    @Test
    void theSamePersonCannotSupplyTwoApprovals() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                .toCompletableFuture().join();
        // 提交人自己再批一次。
        CommunityApprovalOutcome selfApprove = communities
                .approve(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.ALREADY_APPROVED, selfApprove.status());
        assertFalse(selfApprove.effective());
        assertEquals(1, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    @Test
    void submitterCannotBeCountedAsTheSecondApprovalEvenWhenAnotherRoleSubmittedIt() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                .toCompletableFuture().join();
        // 同一个人用不同的幂等键再投一票：仍必须被拒绝。
        CommunityApprovalOutcome twice = communities
                .approve(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.ALREADY_APPROVED, twice.status());
        assertEquals(1, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    @Test
    void singleApprovalAloneNeverBecomesEffectiveAndCanBeDenied() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                .toCompletableFuture().join();
        assertFalse(published.stream().anyMatch(event ->
                event instanceof CommunityGovernanceActionEffectiveEvent));

        CommunityApprovalOutcome denied = communities
                .deny(owner, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, denied.status());
        assertEquals(0, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    // ---------------------------------------------------------------
    // G-115 治理通道准入（isGovernance）与投批权（canApprove）分工
    // ---------------------------------------------------------------

    @Test
    void governanceChannelAdmissionIsReadablePerMemberAndFollowsTheSevenRoleGate() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);
        UUID officer = join(owner, "WAY", CommunityRole.OFFICER);
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        UUID eventHost = join(owner, "WAY", CommunityRole.EVENT_HOST);
        UUID recruiter = join(owner, "WAY", CommunityRole.RECRUITER);
        UUID member = join(owner, "WAY");

        // 治理通道准入：三个治理角色为真，领域角色与普通成员为假。
        for (UUID actor : List.of(owner, treasurer, officer)) {
            assertTrue(isGovernanceMember(actor), "治理角色应当能进治理通道");
        }
        for (UUID actor : List.of(builder, eventHost, recruiter, member)) {
            assertFalse(isGovernanceMember(actor), "非治理角色不得进治理通道");
        }
        // 非成员与 null 必须失败关闭。
        assertFalse(isGovernanceMember(UUID.randomUUID()));
        assertFalse(communities.isGovernanceMember(null).toCompletableFuture().join());

        // 与投批权对照：七角色下两条口径同真同假，但判定是分别取值的。
        assertEquals(isGovernanceMember(builder), canApprove(builder));
        assertFalse(isGovernanceMember(builder));
    }

    @Test
    void governanceChannelAdmissionIsTheRolePredicatesOwnValueNotAnApprovalAlias() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);

        // 两条独立断言，不是「彼此相等」：
        //   ① 成员资格 == 该成员角色自己的 isGovernance()（准入是角色谓词的取值）；
        //   ② 成员资格 == 能力表的 GOVERNANCE_ACTION（服务层与能力表口径同源）。
        // 谁把服务层改成恒真/恒假，或者改成代入 isDomainRole()，这两组期望值都会有一条崩掉。
        for (UUID actor : List.of(owner, treasurer, builder)) {
            assertEquals(roleOf(actor).isGovernance(), isGovernanceMember(actor),
                    "成员资格必须等于该角色自己的 isGovernance()");
            assertEquals(
                    CommunityRolePower.can(roleOf(actor), CommunityRolePower.Capability.GOVERNANCE_ACTION),
                    isGovernanceMember(actor),
                    "成员资格必须与治理通道能力一致");
        }
        // 七角色里只放行三个治理角色（用库内真实角色枚举逐个对照，不写死成员列表）。
        for (CommunityRole role : CommunityRole.values()) {
            boolean expected = role == CommunityRole.OWNER
                    || role == CommunityRole.OFFICER
                    || role == CommunityRole.TREASURER;
            assertEquals(expected, role.isGovernance(), role + " 的准入取值");
        }
    }

    @Test
    void roleGrantAdmissionUsesTheGovernanceChannelNotTheApprovalCapability() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        UUID member = join(owner, "WAY");
        UUID target = join(owner, "WAY");

        // 领域角色能在自己领域提交工程（SUBMIT_APPROVAL），但进不了角色任命通道。
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS,
                communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT,
                        CommunityGovernanceAction.COMMUNITY_PROJECT.key(), "project-by-builder")
                        .toCompletableFuture().join().status());
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED,
                communities.approveAssignment(builder, target, CommunityRole.RECRUITER.name(), "grant-by-builder")
                        .toCompletableFuture().join().status());
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED,
                communities.approveAssignment(member, target, CommunityRole.RECRUITER.name(), "grant-by-member")
                        .toCompletableFuture().join().status());

        // 治理角色过准入后进入真实的审批记录（提交人第一票）。角色变更要双批，
        // 因此此时角色还没落地 —— 这里只断言「准入放行且票真的写下」。
        CommunityApprovalOutcome byOwner = communities
                .approveAssignment(owner, target, CommunityRole.RECRUITER.name(), "grant-by-owner")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, byOwner.status());
        assertEquals(1, byOwner.approvals().size());
        assertEquals(CommunityRole.MEMBER, roleOf(target), "单票不得落地角色变更");
        assertEquals(1, approvalRows(CommunityGovernanceAction.ROLE_GRANT.key()));
    }

    @Test
    void governanceAdmissionIsAlsoExposedThroughTheCapabilityTable() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID builder = join(owner, "WAY", CommunityRole.BUILDER);
        UUID member = join(owner, "WAY");

        assertTrue(can(owner, CommunityRolePower.Capability.GOVERNANCE_ACTION));
        assertFalse(can(builder, CommunityRolePower.Capability.GOVERNANCE_ACTION));
        assertFalse(can(member, CommunityRolePower.Capability.GOVERNANCE_ACTION));
        // 领域角色的准入线比投批线宽一档：能提交但进不了治理通道。
        assertTrue(can(builder, CommunityRolePower.Capability.SUBMIT_APPROVAL));
        assertFalse(can(builder, CommunityRolePower.Capability.APPROVE));
    }

    // ---------------------------------------------------------------
    // G-103 ① 角色变更只能经双批生效
    // ---------------------------------------------------------------

    @Test
    void roleChangeOnlyAppliesAfterTwoDistinctApprovals() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID deputy = join(owner, "WAY", CommunityRole.OFFICER);
        UUID target = join(owner, "WAY");
        assertEquals(CommunityRole.MEMBER, roleOf(target));

        CommunityApprovalOutcome first = communities.approveAssignment(
                        owner, target, CommunityRole.RECRUITER.name(), "role-grant-1")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, first.status());
        assertFalse(first.effective());
        assertEquals(CommunityRole.MEMBER, roleOf(target), "单票不得改角色");

        CommunityApprovalOutcome second = communities.approveAssignment(
                        deputy, target, CommunityRole.RECRUITER.name(), "role-grant-2")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, second.status());
        assertTrue(second.effective());
        assertEquals(CommunityRole.RECRUITER, roleOf(target));
    }

    @Test
    void ownerCannotBeGrantedThroughTheApprovalPath() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID deputy = join(owner, "WAY", CommunityRole.OFFICER);
        UUID target = join(owner, "WAY");

        CommunityApprovalOutcome outcome = communities.approveAssignment(
                        owner, target, CommunityRole.OWNER.name(), "role-grant-owner")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.INVALID, outcome.status());
        assertEquals(CommunityRole.MEMBER, roleOf(target));
    }

    @Test
    void officerAndTreasurerAssignmentsKeepTwoCurrentGovernorsAndTheSignedRole() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID deputy = join(owner, "WAY", CommunityRole.OFFICER);
        UUID target = join(owner, "WAY");
        var first = communities.request(owner, target, CommunityRole.TREASURER, "treasurer-request")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS, first.status());
        assertEquals(target + ":TREASURER", first.subject().orElseThrow());
        assertEquals(CommunityRole.MEMBER, roleOf(target));
        var substituted = communities.approveAssignment(deputy, target, "OFFICER", "swapped-role")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.INVALID, substituted.status());
        assertEquals(CommunityRole.MEMBER, roleOf(target));
        var second = communities.approve(deputy, CommunityGovernanceAction.ROLE_GRANT,
                communities.subjects(deputy, CommunityGovernanceAction.ROLE_GRANT).toCompletableFuture().join().orElseThrow())
                .toCompletableFuture().join();
        assertTrue(second.effective());
        assertEquals(CommunityRole.TREASURER, roleOf(target));

        UUID next = join(owner, "WAY");
        communities.request(owner, next, CommunityRole.OFFICER, "officer-request").toCompletableFuture().join();
        var approved = communities.approve(target, CommunityGovernanceAction.ROLE_GRANT, next + ":OFFICER")
                .toCompletableFuture().join();
        assertTrue(approved.effective());
        assertEquals(CommunityRole.OFFICER, roleOf(next));
    }

    @Test
    void legacyUuidOnlyRoleRequestsCanBeDeniedButCannotBeApprovedOrInferred() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID deputy = join(owner, "WAY", CommunityRole.OFFICER);
        UUID target = join(owner, "WAY");
        communities.request(owner, target, CommunityRole.BUILDER, "before-upgrade").toCompletableFuture().join();
        try (Connection connection = dataSource.getConnection(); var update = connection.prepareStatement(
                "UPDATE rpg_community_approvals SET subject = ? WHERE subject = ?")) {
            update.setString(1, target.toString());
            update.setString(2, target + ":BUILDER");
            update.executeUpdate();
            connection.commit();
        }
        assertEquals(CommunityApprovalOutcome.Status.INVALID,
                communities.approve(deputy, CommunityGovernanceAction.ROLE_GRANT, target.toString())
                        .toCompletableFuture().join().status());
        assertEquals(CommunityRole.MEMBER, roleOf(target));
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS,
                communities.deny(deputy, CommunityGovernanceAction.ROLE_GRANT, target.toString())
                        .toCompletableFuture().join().status());
        assertTrue(communities.subjects(owner, CommunityGovernanceAction.ROLE_GRANT).toCompletableFuture().join().isEmpty());
    }

    @Test
    void roleGrantCannotDemoteTheCurrentOwnerOrCountTheTargetAsTheirOwnSigner() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID deputy = join(owner, "WAY", CommunityRole.OFFICER);
        UUID treasurer = join(owner, "WAY", CommunityRole.TREASURER);
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED,
                communities.request(deputy, owner, CommunityRole.MEMBER, "demote-owner")
                        .toCompletableFuture().join().status());
        communities.request(owner, deputy, CommunityRole.TREASURER, "change-deputy").toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED,
                communities.approve(deputy, CommunityGovernanceAction.ROLE_GRANT, deputy + ":TREASURER")
                        .toCompletableFuture().join().status());
        assertTrue(communities.approve(treasurer, CommunityGovernanceAction.ROLE_GRANT, deputy + ":TREASURER")
                .toCompletableFuture().join().effective());
        assertEquals(CommunityRole.OWNER, roleOf(owner));
    }

    // ---------------------------------------------------------------
    // 「接线但不启用」：开关默认关时写操作一律 DISABLED
    // ---------------------------------------------------------------

    @Test
    void disabledGovernanceFailsClosedWithoutWritingAnything() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        JdbcCommunityService disabled = new JdbcCommunityService(dataSource, executor, null, false, published::add);
        assertFalse(disabled.enabled());

        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();
        assertEquals(CommunityApprovalOutcome.Status.DISABLED, disabled
                .submit(owner, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-off")
                .toCompletableFuture().join().status());
        assertEquals(CommunityApprovalOutcome.Status.DISABLED, disabled
                .approve(owner, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join().status());
        assertEquals(CommunityApprovalOutcome.Status.DISABLED, disabled
                .deny(owner, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join().status());
        assertEquals(CommunityApprovalOutcome.Status.DISABLED, disabled
                .approveAssignment(owner, owner, CommunityRole.RECRUITER.name(), "role-off")
                .toCompletableFuture().join().status());
        assertEquals(0, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    @Test
    void nonMembersCannotTakePartInGovernance() throws Exception {
        UUID owner = player(15);
        createCommunity(owner, "Wayfarers", "WAY");
        UUID outsider = player(7);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        assertEquals(CommunityApprovalOutcome.Status.NOT_MEMBER, communities
                .submit(outsider, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "outsider")
                .toCompletableFuture().join().status());
        assertEquals(CommunityApprovalOutcome.Status.NOT_MEMBER, communities
                .approve(outsider, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join().status());
        assertEquals(0, approvalRows(CommunityGovernanceAction.COMMUNITY_PROJECT.key()));
    }

    @Test
    void roleGrantToAMemberOfAnotherCommunityIsRejected() throws Exception {
        UUID first = player(15);
        createCommunity(first, "Wayfarers", "WAY");
        UUID second = player(14);
        createCommunity(second, "Pilgrims", "PIL");
        UUID foreigner = join(second, "PIL");

        CommunityApprovalOutcome outcome = communities.approveAssignment(
                        first, foreigner, CommunityRole.RECRUITER.name(), "role-foreign")
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.TARGET_NOT_MEMBER, outcome.status());
        assertEquals(CommunityRole.MEMBER, roleOf(foreigner));
    }

    // ---------------------------------------------------------------

    @Test
    void governanceNotificationsContainOnlyCurrentGovernanceMembersOfThatCommunity() throws Exception {
        UUID owner = player(31);
        createCommunity(owner, "Local Council", "LOC");
        UUID deputy = join(owner, "LOC", CommunityRole.OFFICER);
        UUID treasurer = join(owner, "LOC", CommunityRole.TREASURER);
        join(owner, "LOC", CommunityRole.BUILDER);
        join(owner, "LOC", CommunityRole.EVENT_HOST);
        join(owner, "LOC", CommunityRole.RECRUITER);
        join(owner, "LOC", CommunityRole.MEMBER);
        UUID outsider = player(31);
        createCommunity(outsider, "Other Council", "OUT");
        UUID community = communities.profile(owner, PERIOD, GOAL).toCompletableFuture().join()
                .membership().orElseThrow().community().communityId();

        assertEquals(Set.of(owner, deputy, treasurer), Set.copyOf(
                communities.notificationRecipients(community).toCompletableFuture().join()));
        setRole(owner, deputy, CommunityRole.MEMBER);
        assertEquals(Set.of(owner, treasurer), Set.copyOf(
                communities.notificationRecipients(community).toCompletableFuture().join()),
                "Audience reads must not retain removed governance privileges");
        assertTrue(communities.notificationRecipients(UUID.randomUUID()).toCompletableFuture().join().isEmpty());
        assertTrue(new JdbcCommunityService(dataSource, executor, null, false, published::add)
                .notificationRecipients(community).toCompletableFuture().join().isEmpty());
    }

    @Test
    void governanceEventsArePublishedOnlyAfterTheWriteIsVisibleToOtherConnections() throws Exception {
        UUID owner = player(31);
        createCommunity(owner, "Commit Council", "CMT");
        UUID host = join(owner, "CMT", CommunityRole.EVENT_HOST);
        AtomicInteger visibleRows = new AtomicInteger(-1);
        JdbcCommunityService observed = new JdbcCommunityService(dataSource, executor, null, true, event -> {
            try {
                visibleRows.set(approvalRows(CommunityGovernanceAction.COMMUNITY_EVENT.key()));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });

        assertEquals(CommunityApprovalOutcome.Status.SUCCESS,
                observed.submit(host, CommunityGovernanceAction.COMMUNITY_EVENT, "event", "commit-notification")
                        .toCompletableFuture().join().status());
        assertEquals(1, visibleRows.get(), "The event must not race ahead of its database commit");
    }

    @Test
    void completingAnEventDoesNotEmitASpuriousRoleRejection() throws Exception {
        UUID owner = player(31);
        createCommunity(owner, "Event Council", "EVT");
        UUID host = join(owner, "EVT", CommunityRole.EVENT_HOST);
        communities.submit(host, CommunityGovernanceAction.COMMUNITY_EVENT, "event", "event-action")
                .toCompletableFuture().join();
        published.clear();

        assertTrue(communities.approve(owner, CommunityGovernanceAction.COMMUNITY_EVENT, "event")
                .toCompletableFuture().join().effective());
        assertEquals(1, published.size());
        assertTrue(published.getFirst() instanceof CommunityGovernanceActionEffectiveEvent);
        assertFalse(published.stream().anyMatch(CommunityRoleRejectionEvent.class::isInstance));
    }

    private boolean isGovernanceMember(UUID playerId) {
        return communities.isGovernanceMember(playerId).toCompletableFuture().join();
    }

    private boolean canApprove(UUID playerId) {
        return can(playerId, CommunityRolePower.Capability.APPROVE);
    }

    private boolean can(UUID playerId, CommunityRolePower.Capability capability) {
        return communities.can(playerId, capability).toCompletableFuture().join();
    }

    private void createCommunity(UUID owner, String name, String tag) throws Exception {
        insertAccount(owner, new BigDecimal("50000.00"));
        setLevel(owner, 31);
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.create(owner, name, tag, 10, 1, new BigDecimal("1000.00"),
                                new BigDecimal("50000.00"), "create-" + tag.toLowerCase())
                        .toCompletableFuture().join().status());
    }

    /** 加入社群；角色在 JOIN 之后立即写入表，避免依赖邀请/接管的副作用。 */
    private UUID join(UUID owner, String tag) throws Exception {
        return join(owner, tag, CommunityRole.MEMBER);
    }

    private UUID join(UUID owner, String tag, CommunityRole role) throws Exception {
        UUID player = player(20);
        communities.invite(owner, player, 10, 24, PERIOD, GOAL).toCompletableFuture().join();
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.accept(player, tag, PERIOD, GOAL).toCompletableFuture().join().status());
        if (role != CommunityRole.MEMBER) setRole(owner, player, role);
        return player;
    }

    private UUID player(int level) {
        UUID playerId = UUID.randomUUID();
        try {
            setLevel(playerId, level);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return playerId;
    }

    private void setLevel(UUID playerId, int level) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     MERGE INTO rpg_profiles (player_uuid, archetype, ascension, level, experience, energy,
                         unspent_attribute_points, skill_points, defense, strength, agility,
                         intelligence, stamina)
                     KEY (player_uuid) VALUES (?, 'WARRIOR', 0, ?, 0, 100, 0, 0, 0, 0, 0, 0, 0)
                     """)) {
            insert.setString(1, playerId.toString());
            insert.setInt(2, level);
            insert.executeUpdate();
            connection.commit();
        }
    }

    private void insertAccount(UUID playerId, BigDecimal balance) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     MERGE INTO rpg_accounts (player_uuid, balance) KEY (player_uuid) VALUES (?, ?)
                     """)) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, balance);
            insert.executeUpdate();
            connection.commit();
        }
    }

    private void setRole(UUID owner, UUID target, CommunityRole role) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE rpg_community_members SET role = ?
                     WHERE player_uuid = ?
                       AND community_id = (SELECT community_id FROM rpg_community_members
                                           WHERE player_uuid = ?)
                     """)) {
            update.setString(1, role.name());
            update.setString(2, target.toString());
            update.setString(3, owner.toString());
            assertEquals(1, update.executeUpdate());
            connection.commit();
        }
    }

    private CommunityRole roleOf(UUID playerId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT role FROM rpg_community_members WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next(), "成员不存在: " + playerId);
                return CommunityRole.valueOf(result.getString("role"));
            }
        }
    }

    /** 按动作键统计仍在库里的审批记录（动作键，不含目标）。 */
    private int approvalRows(String actionKey) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM rpg_community_approvals WHERE action = ?")) {
            query.setString(1, actionKey);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
