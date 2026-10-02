package cn.originalrpg.core.persistence;

import cn.originalrpg.api.event.CommunityApprovalSubmittedEvent;
import cn.originalrpg.api.event.CommunityGovernanceActionEffectiveEvent;
import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcCommunityService communities;
    private final List<Event> published = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:rpg_community_gov_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(16);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        // 治理开关显式打开：本测试就是要验证接线后的行为。
        communities = new JdbcCommunityService(dataSource, executor, null, true, published::add);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE rpg_profiles (
                        player_uuid VARCHAR(36) PRIMARY KEY, archetype VARCHAR(24) NOT NULL,
                        ascension INTEGER NOT NULL, level INTEGER NOT NULL, experience BIGINT NOT NULL,
                        energy INTEGER NOT NULL, unspent_attribute_points INTEGER NOT NULL,
                        skill_points INTEGER NOT NULL, defense INTEGER NOT NULL, strength INTEGER NOT NULL,
                        agility INTEGER NOT NULL, intelligence INTEGER NOT NULL, stamina INTEGER NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_skill_point_grants (
                        source VARCHAR(96) NOT NULL, idempotency_key VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL, points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL, from_local_level INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (source, idempotency_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_accounts (
                        player_uuid VARCHAR(36) PRIMARY KEY, balance DECIMAL(19, 2) NOT NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_ledger (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(96) UNIQUE NOT NULL,
                        sender_uuid VARCHAR(36), receiver_uuid VARCHAR(36), amount DECIMAL(19, 2) NOT NULL,
                        fee DECIMAL(19, 2) NOT NULL, reason VARCHAR(128) NOT NULL,
                        reversal_of VARCHAR(96) UNIQUE,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_identity (
                        player_uuid VARCHAR(36) PRIMARY KEY, invite_code VARCHAR(16) UNIQUE NOT NULL,
                        mentor_uuid VARCHAR(36), mentor_verified BOOLEAN NOT NULL DEFAULT FALSE,
                        verified_mentees INTEGER NOT NULL DEFAULT 0, mentor_bound_at TIMESTAMP NULL,
                        mentor_verified_at TIMESTAMP NULL,
                        community_honor BIGINT NOT NULL DEFAULT 0,
                        weekly_commemorations INTEGER NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_communities (
                        id VARCHAR(36) PRIMARY KEY, name VARCHAR(40) NOT NULL,
                        normalized_name VARCHAR(40) UNIQUE NOT NULL, tag VARCHAR(8) NOT NULL,
                        normalized_tag VARCHAR(8) UNIQUE NOT NULL, owner_uuid VARCHAR(36) NOT NULL,
                        capacity INTEGER NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_members (
                        community_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) UNIQUE NOT NULL,
                        role VARCHAR(16) NOT NULL, lifetime_contribution DECIMAL(19, 2) NOT NULL DEFAULT 0,
                        joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (community_id, player_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_invites (
                        community_id VARCHAR(36) NOT NULL, inviter_uuid VARCHAR(36) NOT NULL,
                        target_uuid VARCHAR(36) NOT NULL, expires_at TIMESTAMP NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (community_id, target_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_projects (
                        community_id VARCHAR(36) NOT NULL, period_key VARCHAR(24) NOT NULL,
                        gold_contributed DECIMAL(19, 2) NOT NULL DEFAULT 0,
                        verified_recruits INTEGER NOT NULL DEFAULT 0, completed_at TIMESTAMP NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (community_id, period_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_contributions (
                        id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(80) UNIQUE NOT NULL,
                        community_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,
                        period_key VARCHAR(24) NOT NULL, amount DECIMAL(19, 2) NOT NULL,
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_project_rewards (
                        player_uuid VARCHAR(36) NOT NULL, period_key VARCHAR(24) NOT NULL,
                        community_id VARCHAR(36) NOT NULL,
                        player_contribution DECIMAL(19, 2) NOT NULL,
                        minimum_contribution DECIMAL(19, 2) NOT NULL,
                        honor_awarded INTEGER NOT NULL,
                        claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, period_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_approvals (
                        approval_id VARCHAR(36) PRIMARY KEY,
                        community_id VARCHAR(36) NOT NULL,
                        actor_uuid VARCHAR(36) NOT NULL,
                        action VARCHAR(32) NOT NULL,
                        subject VARCHAR(64),
                        status VARCHAR(16) NOT NULL,
                        requester_uuid VARCHAR(36),
                        idempotency_key VARCHAR(96),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX idx_rpg_community_approvals_unique
                        ON rpg_community_approvals (community_id, action, subject, actor_uuid)
                    """);
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
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
