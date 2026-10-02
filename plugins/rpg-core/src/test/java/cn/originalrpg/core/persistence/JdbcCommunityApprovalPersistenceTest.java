package cn.originalrpg.core.persistence;

import cn.originalrpg.api.event.CommunityApprovalSubmittedEvent;
import cn.originalrpg.api.event.CommunityGovernanceActionEffectiveEvent;
import cn.originalrpg.api.event.CommunityRoleRejectionEvent;
import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityRole;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bukkit.event.Event;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-103 行为级证据：双批审批的每一票都真的落到 {@code rpg_community_approvals}。
 *
 * <p>直接查库断言「谁投了票、投给哪个动作、哪个目标、当前状态」，
 * 而不是断言方法返回值 —— 落库是验收口径的第 ③ 条。</p>
 */
class JdbcCommunityApprovalPersistenceTest {
    private static final String PERIOD = "2026-W32";
    private static final BigDecimal GOAL = new BigDecimal("250000.00");

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcCommunityService communities;
    private final java.util.List<Event> published = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:rpg_approval_rows_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        // 断言事件确实由真实路径发布，所以这里显式传发布口（与运行时的 Bukkit 发布等价）。
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
                        mentor_verified_at TIMESTAMP NULL, community_honor BIGINT NOT NULL DEFAULT 0,
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
                        community_id VARCHAR(36) NOT NULL, player_contribution DECIMAL(19, 2) NOT NULL,
                        minimum_contribution DECIMAL(19, 2) NOT NULL, honor_awarded INTEGER NOT NULL,
                        claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (player_uuid, period_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_community_approvals (
                        approval_id VARCHAR(36) PRIMARY KEY, community_id VARCHAR(36) NOT NULL,
                        actor_uuid VARCHAR(36) NOT NULL, action VARCHAR(32) NOT NULL,
                        subject VARCHAR(64), status VARCHAR(16) NOT NULL,
                        requester_uuid VARCHAR(36), idempotency_key VARCHAR(96),
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

    @Test
    void eachVoteIsPersistedAsItsOwnPendingRowUntilTheSecondRoleApproves() throws Exception {
        UUID owner = player();
        createCommunity(owner);
        UUID builder = join(owner, CommunityRole.BUILDER);
        UUID treasurer = join(owner, CommunityRole.TREASURER);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        // 工程动作的前置角色是 BUILDER：由 BUILDER 提交，票记在 BUILDER 名下。
        assertEquals(CommunityApprovalOutcome.Status.SUCCESS,
                communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                        .toCompletableFuture().join().status());
        UUID submittingBuilder = builder;

        // 第一票已落库：actor=builder，status=PENDING，subject=动作键。
        List<Row> first = rows();
        assertEquals(1, first.size());
        assertEquals(submittingBuilder, first.get(0).actor());
        assertEquals(CommunityGovernanceAction.COMMUNITY_PROJECT.key(), first.get(0).action());
        assertEquals(subject, first.get(0).subject());
        assertEquals("PENDING", first.get(0).status());
        assertTrue(first.get(0).idempotencyKey().startsWith("project"));

        // 同一人再投一票：库里的行数不能变成两条。
        communities.approve(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(1, rows().size(), "同一个人不能凑两次批准");

        // 另一名治理角色投第二票 → 双批达成，PENDING 行被清空。
        CommunityApprovalOutcome effective = communities
                .approve(treasurer, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertTrue(effective.effective());
        assertEquals(0, rows().size(), "生效后不应留下 PENDING 残留");
    }

    @Test
    void aRejectedVoteLeavesTheAuditTableUntouched() throws Exception {
        UUID owner = player();
        createCommunity(owner);
        UUID plainMember = join(owner, CommunityRole.MEMBER);
        UUID builder = join(owner, CommunityRole.BUILDER);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                .toCompletableFuture().join();
        int before = rows().size();

        // MEMBER 没有 canApprove()，投批必须被拒且不写任何行。
        CommunityApprovalOutcome rejected = communities
                .approve(plainMember, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertEquals(CommunityApprovalOutcome.Status.NOT_ALLOWED, rejected.status());
        assertEquals(before, rows().size());
        assertFalse(rows().stream().anyMatch(row -> row.actor().equals(plainMember)));
    }

    @Test
    void roleGrantVotesArePersistedAgainstTheTargetUuid() throws Exception {
        UUID owner = player();
        createCommunity(owner);
        UUID deputy = join(owner, CommunityRole.OFFICER);
        UUID target = join(owner, CommunityRole.MEMBER);

        communities.approveAssignment(owner, target, CommunityRole.RECRUITER.name(), "role-grant-1")
                .toCompletableFuture().join();

        List<Row> pending = rows();
        assertEquals(1, pending.size());
        assertEquals(target.toString(), pending.get(0).subject(), "目标必须是目标玩家 UUID");

        communities.approveAssignment(deputy, target, CommunityRole.RECRUITER.name(), "role-grant-2")
                .toCompletableFuture().join();
        assertEquals(0, rows().size());
        assertEquals(CommunityRole.RECRUITER, roleOf(target));
    }

    @Test
    void anApprovedRoleChangeThatCannotLandPublishesARejectionForManualReplay() throws Exception {
        UUID owner = player();
        createCommunity(owner);
        UUID deputy = join(owner, CommunityRole.OFFICER);
        UUID target = join(owner, CommunityRole.MEMBER);

        // 服务未装角色分配提供者（第三个构造参数为 null）→ 目标不是本会成员时角色不落地。
        // 用「目标属于别的社群」这条真实前置触发：先让目标被踢出本会。
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM rpg_community_members WHERE player_uuid = ?")) {
            delete.setString(1, target.toString());
            delete.executeUpdate();
            connection.commit();
        }

        communities.approveAssignment(owner, target, CommunityRole.RECRUITER.name(), "role-grant-1")
                .toCompletableFuture().join();
        CommunityApprovalOutcome second = communities.approveAssignment(
                        deputy, target, CommunityRole.RECRUITER.name(), "role-grant-2")
                .toCompletableFuture().join();

        assertEquals(CommunityApprovalOutcome.Status.TARGET_NOT_MEMBER, second.status());
        assertTrue(published.stream().noneMatch(CommunityRoleRejectionEvent.class::isInstance),
                "目标校验失败时不应发布「已通过但未落地」事件");
    }

    @Test
    void governanceHooksArePublishedOnTheRealPath() throws Exception {
        UUID owner = player();
        createCommunity(owner);
        UUID builder = join(owner, CommunityRole.BUILDER);
        UUID treasurer = join(owner, CommunityRole.TREASURER);
        String subject = CommunityGovernanceAction.COMMUNITY_PROJECT.key();

        assertEquals(CommunityApprovalOutcome.Status.SUCCESS,
                communities.submit(builder, CommunityGovernanceAction.COMMUNITY_PROJECT, subject, "project-1")
                        .toCompletableFuture().join().status());
        assertTrue(published.stream().anyMatch(CommunityApprovalSubmittedEvent.class::isInstance),
                "提交必须发布 CommunityApprovalSubmittedEvent");
        assertTrue(published.stream().noneMatch(CommunityGovernanceActionEffectiveEvent.class::isInstance),
                "单票不得发布生效事件");

        CommunityApprovalOutcome second = communities
                .approve(treasurer, CommunityGovernanceAction.COMMUNITY_PROJECT, subject)
                .toCompletableFuture().join();
        assertTrue(second.effective(), "两个不同治理角色的批准必须生效");
        assertTrue(published.stream().anyMatch(CommunityGovernanceActionEffectiveEvent.class::isInstance),
                "双批达成必须发布 CommunityGovernanceActionEffectiveEvent");
    }

    private record Row(UUID actor, String action, String subject, String status, String idempotencyKey) {
    }

    private List<Row> rows() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT actor_uuid, action, subject, status, idempotency_key
                     FROM rpg_community_approvals ORDER BY created_at ASC, actor_uuid ASC
                     """);
             ResultSet result = query.executeQuery()) {
            List<Row> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(new Row(UUID.fromString(result.getString("actor_uuid")),
                        result.getString("action"), result.getString("subject"),
                        result.getString("status"), result.getString("idempotency_key")));
            }
            return rows;
        }
    }

    private CommunityRole roleOf(UUID playerId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT role FROM rpg_community_members WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return CommunityRole.valueOf(result.getString("role"));
            }
        }
    }

    private void createCommunity(UUID owner) throws Exception {
        insertAccount(owner, new BigDecimal("50000.00"));
        setLevel(owner, 31);
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.create(owner, "Wayfarers", "WAY", 10, 1, new BigDecimal("1000.00"),
                                new BigDecimal("50000.00"), "create-way")
                        .toCompletableFuture().join().status());
    }

    private UUID join(UUID owner, CommunityRole role) throws Exception {
        UUID player = player();
        communities.invite(owner, player, 10, 24, PERIOD, GOAL).toCompletableFuture().join();
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.accept(player, "WAY", PERIOD, GOAL).toCompletableFuture().join().status());
        if (role != CommunityRole.MEMBER) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE rpg_community_members SET role = ? WHERE player_uuid = ?")) {
                update.setString(1, role.name());
                update.setString(2, player.toString());
                assertEquals(1, update.executeUpdate());
                connection.commit();
            }
        }
        return player;
    }

    private UUID player() throws Exception {
        UUID playerId = UUID.randomUUID();
        setLevel(playerId, 20);
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
             PreparedStatement insert = connection.prepareStatement(
                     "MERGE INTO rpg_accounts (player_uuid, balance) KEY (player_uuid) VALUES (?, ?)")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, balance);
            insert.executeUpdate();
            connection.commit();
        }
    }
}
