package cn.originalrpg.core.persistence;

import cn.originalrpg.api.event.CommunityApprovalSubmittedEvent;
import cn.originalrpg.api.event.CommunityGovernanceActionEffectiveEvent;
import cn.originalrpg.api.event.CommunityRoleRejectionEvent;
import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.bukkit.event.Event;

import javax.sql.DataSource;
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

    private CommunityDatabaseFixture fixture;
    private DataSource dataSource;
    private ExecutorService executor;
    private JdbcCommunityService communities;
    private final java.util.List<Event> published = new java.util.ArrayList<>();

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
    void roleGrantVotesPersistTheExactTargetAndIntendedRole() throws Exception {
        UUID owner = player();
        createCommunity(owner);
        UUID deputy = join(owner, CommunityRole.OFFICER);
        UUID target = join(owner, CommunityRole.MEMBER);

        communities.approveAssignment(owner, target, CommunityRole.RECRUITER.name(), "role-grant-1")
                .toCompletableFuture().join();

        List<Row> pending = rows();
        assertEquals(1, pending.size());
        assertEquals(target + ":RECRUITER", pending.get(0).subject(), "目标 UUID 与拟任角色必须一起持久化");

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
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE rpg_profiles SET archetype = 'WARRIOR', ascension = 0, level = ?,
                         experience = 0, energy = 100, unspent_attribute_points = 0, skill_points = 0,
                         defense = 0, strength = 0, agility = 0, intelligence = 0, stamina = 0
                     WHERE player_uuid = ?
                     """);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO rpg_profiles (player_uuid, archetype, ascension, level, experience, energy,
                         unspent_attribute_points, skill_points, defense, strength, agility,
                         intelligence, stamina)
                     VALUES (?, 'WARRIOR', 0, ?, 0, 100, 0, 0, 0, 0, 0, 0, 0)
                     """)) {
            // Serial fixture setup uses portable SQL on both production-schema backends.
            update.setInt(1, level);
            update.setString(2, playerId.toString());
            int updated = update.executeUpdate();
            if (updated == 0) {
                insert.setString(1, playerId.toString());
                insert.setInt(2, level);
                assertEquals(1, insert.executeUpdate());
            } else {
                assertEquals(1, updated);
            }
            connection.commit();
        }
    }

    private void insertAccount(UUID playerId, BigDecimal balance) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE rpg_accounts SET balance = ? WHERE player_uuid = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?)")) {
            update.setBigDecimal(1, balance);
            update.setString(2, playerId.toString());
            int updated = update.executeUpdate();
            if (updated == 0) {
                insert.setString(1, playerId.toString());
                insert.setBigDecimal(2, balance);
                assertEquals(1, insert.executeUpdate());
            } else {
                assertEquals(1, updated);
            }
            connection.commit();
        }
    }
}
