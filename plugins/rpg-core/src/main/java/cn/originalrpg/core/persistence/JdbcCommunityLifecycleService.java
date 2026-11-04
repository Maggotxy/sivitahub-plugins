package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityLifecycleAction;
import cn.originalrpg.api.model.CommunityLifecycleAudit;
import cn.originalrpg.api.model.CommunityLifecyclePolicy;
import cn.originalrpg.api.model.CommunityLifecycleProposal;
import cn.originalrpg.api.model.CommunityLifecycleResult;
import cn.originalrpg.api.model.CommunityLifecycleSnapshot;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.service.CommunityLifecycleService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static cn.originalrpg.api.model.CommunityLifecycleProposal.State;
import static cn.originalrpg.api.model.CommunityLifecycleResult.Status;

/**
 * Durable community lifecycle transactions. Every mutation locks community, membership,
 * then proposal, in that order; the ordinary community service uses the same community lock.
 * Production deadlines use the database clock. A package-private clock supports fault tests.
 */
public final class JdbcCommunityLifecycleService implements CommunityLifecycleService {
    private final DataSource dataSource;
    private final Executor executor;
    private final boolean enabled;
    private final CommunityLifecyclePolicy policy;
    private final Clock testClock;

    public JdbcCommunityLifecycleService(DataSource dataSource, Executor executor, boolean enabled,
                                         CommunityLifecyclePolicy policy) {
        this(dataSource, executor, enabled, policy, null);
    }

    JdbcCommunityLifecycleService(DataSource dataSource, Executor executor, boolean enabled,
                                   CommunityLifecyclePolicy policy, Clock testClock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.executor = Objects.requireNonNull(executor);
        this.enabled = enabled;
        this.policy = Objects.requireNonNull(policy);
        this.testClock = testClock;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public CommunityLifecyclePolicy policy() {
        return policy;
    }

    @Override
    public CompletionStage<Optional<CommunityLifecycleSnapshot>> status(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        return AsyncJdbc.supply(executor, () -> transaction(connection -> {
            UUID communityId = memberCommunity(connection, playerId);
            if (communityId == null) return Optional.empty();
            Community community = community(connection, communityId, false);
            if (community == null || community.dissolved()) return Optional.empty();
            Member owner = member(connection, communityId, community.ownerId(), false);
            if (owner == null) throw new SQLException("Community has no owner membership: " + communityId);
            List<CommunityLifecycleProposal> proposals = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM rpg_community_lifecycle_proposals
                    WHERE community_id = ? ORDER BY created_at DESC, proposal_id DESC LIMIT 8
                    """)) {
                query.setString(1, communityId.toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) proposals.add(view(connection, readProposal(rows)));
                }
            }
            return Optional.of(new CommunityLifecycleSnapshot(communityId, community.name(), community.tag(),
                    community.ownerId(), owner.lastAuthenticated(), proposals));
        }));
    }

    @Override
    public CompletionStage<Void> recordAuthenticatedActivity(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(null);
        // Presence remains fresh while governance is disabled; enabling it must not manufacture absence.
        return AsyncJdbc.supply(executor, () -> transaction(connection -> {
            UUID communityId = memberCommunity(connection, playerId);
            if (communityId == null) return null;
            Community community = community(connection, communityId, true);
            if (community == null || community.dissolved()) return null;
            Member current = member(connection, communityId, playerId, true);
            if (current == null) return null;
            Instant now = now(connection);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_community_members
                    SET last_authenticated_at = CASE WHEN last_authenticated_at < ? THEN ?
                                                     ELSE last_authenticated_at END
                    WHERE community_id = ? AND player_uuid = ?
                    """)) {
                update.setTimestamp(1, Timestamp.from(now));
                update.setTimestamp(2, Timestamp.from(now));
                update.setString(3, communityId.toString());
                update.setString(4, playerId.toString());
                update.executeUpdate();
            }
            if (community.ownerId().equals(playerId)) {
                Proposal active = activeProposal(connection, communityId, true);
                if (active != null && active.action() == CommunityLifecycleAction.SUCCESSION) {
                    finish(connection, active, State.CANCELLED, playerId, now,
                            "OWNER_RETURNED", "Authenticated incumbent activity");
                }
            }
            return null;
        }));
    }

    @Override
    public CompletionStage<CommunityLifecycleResult> propose(UUID actorId, CommunityLifecycleAction action,
                                                            String idempotencyKey) {
        if (!enabled) return completed(Status.DISABLED);
        if (actorId == null || action == null || !validKey(idempotencyKey)) return completed(Status.INVALID);
        return AsyncJdbc.supply(executor, () -> transaction(connection -> {
            UUID communityId = memberCommunity(connection, actorId);
            if (communityId == null) return CommunityLifecycleResult.of(Status.NOT_MEMBER);
            Community community = community(connection, communityId, true);
            if (community == null || community.dissolved()) return CommunityLifecycleResult.of(Status.NOT_MEMBER);
            Member actor = member(connection, communityId, actorId, true);
            if (actor == null) return CommunityLifecycleResult.of(Status.NOT_MEMBER);
            if (!governor(actor)) return CommunityLifecycleResult.of(Status.NOT_ALLOWED);
            if (action == CommunityLifecycleAction.DISSOLUTION && !community.ownerId().equals(actorId)
                    || action == CommunityLifecycleAction.SUCCESSION && community.ownerId().equals(actorId)) {
                return CommunityLifecycleResult.of(Status.NOT_ALLOWED);
            }
            Proposal replay = proposalByKey(connection, idempotencyKey);
            if (replay != null) {
                if (!replay.communityId().equals(communityId) || !replay.proposerId().equals(actorId)
                        || replay.action() != action) return CommunityLifecycleResult.of(Status.INVALID);
                return result(connection, terminalStatus(replay, Status.ACTIVE_PROPOSAL), replay);
            }
            Proposal active = activeProposal(connection, communityId, true);
            if (active != null) return result(connection, Status.ACTIVE_PROPOSAL, active);
            Instant now = now(connection);
            Member owner = member(connection, communityId, community.ownerId(), false);
            if (owner == null || owner.role() != CommunityRole.OWNER) {
                return CommunityLifecycleResult.of(Status.CONDITIONS_CHANGED);
            }
            if (action == CommunityLifecycleAction.SUCCESSION) {
                if (owner.lastAuthenticated().isAfter(now.minus(policy.ownerInactivity()))) {
                    return CommunityLifecycleResult.of(Status.OWNER_ACTIVE);
                }
                if (actor.joinedAt().isAfter(now.minus(policy.candidateMembership()))) {
                    return CommunityLifecycleResult.of(Status.MEMBERSHIP_TOO_RECENT);
                }
            }
            UUID proposalId = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_community_lifecycle_proposals
                        (proposal_id, community_id, active_community_id, action, proposer_uuid,
                         incumbent_owner_uuid, candidate_uuid, candidate_joined_at,
                         owner_last_authenticated_at, state, idempotency_key, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """)) {
                insert.setString(1, proposalId.toString());
                insert.setString(2, communityId.toString());
                insert.setString(3, communityId.toString());
                insert.setString(4, action.name());
                insert.setString(5, actorId.toString());
                insert.setString(6, community.ownerId().toString());
                insert.setString(7, action == CommunityLifecycleAction.SUCCESSION ? actorId.toString() : null);
                insert.setTimestamp(8, action == CommunityLifecycleAction.SUCCESSION
                        ? Timestamp.from(actor.joinedAt()) : null);
                insert.setTimestamp(9, Timestamp.from(owner.lastAuthenticated()));
                insert.setString(10, idempotencyKey);
                insert.setTimestamp(11, Timestamp.from(now));
                insert.executeUpdate();
            }
            Proposal proposal = proposal(connection, proposalId, true);
            insertApproval(connection, proposal, actor, now);
            audit(connection, proposal, actorId, "PROPOSED", "First signature: " + actor.role(), now);
            return result(connection, Status.SUCCESS, proposal);
        }));
    }

    @Override
    public CompletionStage<CommunityLifecycleResult> approve(UUID actorId, UUID proposalId) {
        return decide(actorId, proposalId, Decision.APPROVE);
    }

    @Override
    public CompletionStage<CommunityLifecycleResult> execute(UUID actorId, UUID proposalId) {
        return decide(actorId, proposalId, Decision.EXECUTE);
    }

    @Override
    public CompletionStage<CommunityLifecycleResult> cancel(UUID actorId, UUID proposalId) {
        return decide(actorId, proposalId, Decision.CANCEL);
    }

    private CompletionStage<CommunityLifecycleResult> decide(UUID actorId, UUID proposalId, Decision decision) {
        if (!enabled) return completed(Status.DISABLED);
        if (actorId == null || proposalId == null) return completed(Status.INVALID);
        return AsyncJdbc.supply(executor, () -> transaction(connection -> {
            // This unlocked lookup locates the immutable lock root; all decisions are reread after locking.
            Proposal located = proposal(connection, proposalId, false);
            if (located == null) return CommunityLifecycleResult.of(Status.NOT_FOUND);
            Community community = community(connection, located.communityId(), true);
            if (community == null) return CommunityLifecycleResult.of(Status.NOT_FOUND);
            Member actor = member(connection, community.id(), actorId, true);
            Proposal proposal = proposal(connection, proposalId, true);
            if (proposal.state().terminal()) return result(connection, terminalStatus(proposal, Status.SUCCESS), proposal);
            if (actor == null || community.dissolved()) return CommunityLifecycleResult.of(Status.NOT_MEMBER);
            if (!governor(actor)) return CommunityLifecycleResult.of(Status.NOT_ALLOWED);
            Instant now = now(connection);
            if (decision == Decision.CANCEL) {
                finish(connection, proposal, State.CANCELLED, actorId, now, "CANCELLED", "Governor veto");
                return result(connection, Status.CANCELLED, proposal(connection, proposalId, false));
            }
            if (!conditionsHold(connection, community, proposal, now)) {
                finish(connection, proposal, State.CANCELLED, actorId, now, "INVALIDATED",
                        "Incumbent, membership, authenticated activity or signature changed");
                return result(connection, Status.CONDITIONS_CHANGED, proposal(connection, proposalId, false));
            }
            List<CommunityApprovalOutcome.GrantedApproval> signatures = approvals(connection, proposal);
            if (decision == Decision.APPROVE) {
                if (signatures.stream().anyMatch(signature -> signature.actor().equals(actorId))) {
                    return result(connection, Status.ALREADY_APPROVED, proposal);
                }
                if (proposal.state() == State.COOLING) return result(connection, Status.COOLING, proposal);
                insertApproval(connection, proposal, actor, now);
                audit(connection, proposal, actorId, "APPROVED", "Independent signature: " + actor.role(), now);
                if (approvals(connection, proposal).size() >= CommunityRolePower.REQUIRED_APPROVALS) {
                    Instant deadline = now.plus(policy.coolingFor(proposal.action()));
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE rpg_community_lifecycle_proposals SET state = 'COOLING', execute_after = ?
                            WHERE proposal_id = ? AND state = 'PENDING'
                            """)) {
                        update.setTimestamp(1, Timestamp.from(deadline));
                        update.setString(2, proposalId.toString());
                        requireOne(update.executeUpdate(), "Starting lifecycle cooling");
                    }
                    audit(connection, proposal, actorId, "COOLING_STARTED", deadline.toString(), now);
                }
                return result(connection, Status.SUCCESS, proposal(connection, proposalId, false));
            }
            if (signatures.size() < CommunityRolePower.REQUIRED_APPROVALS || proposal.state() != State.COOLING) {
                return result(connection, Status.APPROVALS_REQUIRED, proposal);
            }
            if (proposal.executeAfter() == null || now.isBefore(proposal.executeAfter())) {
                return result(connection, Status.COOLING, proposal);
            }
            if (proposal.action() == CommunityLifecycleAction.SUCCESSION) {
                changeRole(connection, community.id(), proposal.incumbentOwnerId(), CommunityRole.OFFICER);
                changeRole(connection, community.id(), proposal.candidateId(), CommunityRole.OWNER);
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE rpg_communities SET owner_uuid = ?, updated_at = ?
                        WHERE id = ? AND owner_uuid = ? AND dissolved_at IS NULL
                        """)) {
                    update.setString(1, proposal.candidateId().toString());
                    update.setTimestamp(2, Timestamp.from(now));
                    update.setString(3, community.id().toString());
                    update.setString(4, proposal.incumbentOwnerId().toString());
                    requireOne(update.executeUpdate(), "Transferring community ownership");
                }
            } else {
                if (!CommunityStorageTransactions.releaseForDissolution(connection, community.id(), actorId)) {
                    return result(connection, Status.STORAGE_PENDING, proposal);
                }
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE rpg_communities SET dissolved_at = ?, updated_at = ?
                        WHERE id = ? AND dissolved_at IS NULL
                        """)) {
                    update.setTimestamp(1, Timestamp.from(now));
                    update.setTimestamp(2, Timestamp.from(now));
                    update.setString(3, community.id().toString());
                    requireOne(update.executeUpdate(), "Dissolving community");
                }
                deleteByCommunity(connection, "rpg_community_members", community.id());
                deleteByCommunity(connection, "rpg_community_invites", community.id());
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE rpg_community_approvals SET status = 'CLOSED'
                        WHERE community_id = ? AND action NOT IN ('community.succession', 'community.dissolution')
                        """)) {
                    update.setString(1, community.id().toString());
                    update.executeUpdate();
                }
            }
            finish(connection, proposal, State.EXECUTED, actorId, now, "EXECUTED", proposal.action().name());
            return result(connection, Status.EXECUTED, proposal(connection, proposalId, false));
        }));
    }

    private boolean conditionsHold(Connection connection, Community community, Proposal proposal, Instant now)
            throws SQLException {
        if (!community.ownerId().equals(proposal.incumbentOwnerId())) return false;
        Member owner = member(connection, community.id(), community.ownerId(), false);
        Member proposer = member(connection, community.id(), proposal.proposerId(), false);
        if (owner == null || owner.role() != CommunityRole.OWNER || !governor(proposer)) return false;
        if (proposal.action() == CommunityLifecycleAction.DISSOLUTION) {
            if (!community.ownerId().equals(proposal.proposerId())) return false;
        } else {
            Member candidate = member(connection, community.id(), proposal.candidateId(), false);
            if (!governor(candidate) || !candidate.joinedAt().equals(proposal.candidateJoinedAt())
                    || candidate.joinedAt().isAfter(now.minus(policy.candidateMembership()))
                    || owner.lastAuthenticated().isAfter(proposal.ownerLastAuthenticated())
                    || owner.lastAuthenticated().isAfter(now.minus(policy.ownerInactivity()))) return false;
        }
        // A departing, demoted or rejoining signer invalidates the old consent, even if another
        // current governor would otherwise bring the count back to two.
        return approvals(connection, proposal).size() == approvalCount(connection, proposal);
    }

    @Override
    public CompletionStage<List<CommunityLifecycleAudit>> history(UUID communityId, int limit) {
        if (communityId == null) return CompletableFuture.completedFuture(List.of());
        int boundedLimit = Math.clamp(limit, 1, 100);
        return AsyncJdbc.supply(executor, () -> transaction(connection -> {
            List<CommunityLifecycleAudit> history = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT * FROM rpg_community_lifecycle_audit
                    WHERE community_id = ? ORDER BY recorded_at DESC, audit_id DESC LIMIT ?
                    """)) {
                query.setString(1, communityId.toString());
                query.setInt(2, boundedLimit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) history.add(new CommunityLifecycleAudit(
                            UUID.fromString(rows.getString("audit_id")), communityId,
                            UUID.fromString(rows.getString("proposal_id")), UUID.fromString(rows.getString("actor_uuid")),
                            rows.getString("transition"), rows.getString("detail"),
                            rows.getTimestamp("recorded_at").toInstant()));
                }
            }
            return List.copyOf(history);
        }));
    }

    private UUID memberCommunity(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT m.community_id FROM rpg_community_members m
                JOIN rpg_communities c ON c.id = m.community_id
                WHERE m.player_uuid = ? AND c.dissolved_at IS NULL
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? UUID.fromString(rows.getString(1)) : null;
            }
        }
    }

    private Community community(Connection connection, UUID id, boolean lock) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, owner_uuid, name, tag, dissolved_at FROM rpg_communities WHERE id = ?"
                        + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new Community(id, UUID.fromString(rows.getString("owner_uuid")),
                        rows.getString("name"), rows.getString("tag"), rows.getTimestamp("dissolved_at") != null) : null;
            }
        }
    }

    private Member member(Connection connection, UUID communityId, UUID playerId, boolean lock) throws SQLException {
        if (playerId == null) return null;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT role, joined_at, last_authenticated_at FROM rpg_community_members
                WHERE community_id = ? AND player_uuid = ?
                """ + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, communityId.toString());
            query.setString(2, playerId.toString());
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new Member(playerId, CommunityRole.parse(rows.getString("role")).orElseThrow(),
                        rows.getTimestamp("joined_at").toInstant(), rows.getTimestamp("last_authenticated_at").toInstant()) : null;
            }
        }
    }

    private Proposal activeProposal(Connection connection, UUID communityId, boolean lock) throws SQLException {
        return queryProposal(connection, "active_community_id", communityId.toString(), lock);
    }

    private Proposal proposalByKey(Connection connection, String key) throws SQLException {
        return queryProposal(connection, "idempotency_key", key, false);
    }

    private Proposal proposal(Connection connection, UUID id, boolean lock) throws SQLException {
        return queryProposal(connection, "proposal_id", id.toString(), lock);
    }

    private Proposal queryProposal(Connection connection, String column, String value, boolean lock) throws SQLException {
        // Column names come exclusively from the three private callers above.
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM rpg_community_lifecycle_proposals WHERE " + column + " = ?"
                        + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, value);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? readProposal(rows) : null;
            }
        }
    }

    private static Proposal readProposal(ResultSet row) throws SQLException {
        String candidate = row.getString("candidate_uuid");
        return new Proposal(UUID.fromString(row.getString("proposal_id")), UUID.fromString(row.getString("community_id")),
                CommunityLifecycleAction.valueOf(row.getString("action")), UUID.fromString(row.getString("proposer_uuid")),
                UUID.fromString(row.getString("incumbent_owner_uuid")), candidate == null ? null : UUID.fromString(candidate),
                instant(row, "candidate_joined_at"), instant(row, "owner_last_authenticated_at"),
                State.valueOf(row.getString("state")), instant(row, "created_at"), instant(row, "execute_after"),
                instant(row, "finished_at"));
    }

    private void insertApproval(Connection connection, Proposal proposal, Member actor, Instant now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_community_approvals
                    (approval_id, community_id, actor_uuid, action, status, created_at,
                     requester_uuid, subject, idempotency_key)
                VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, proposal.communityId().toString());
            insert.setString(3, actor.id().toString());
            insert.setString(4, proposal.action().approvalKey());
            insert.setTimestamp(5, Timestamp.from(now));
            insert.setString(6, proposal.proposerId().toString());
            insert.setString(7, proposal.id().toString());
            insert.setString(8, "lifecycle:" + proposal.id() + ":" + actor.id());
            insert.executeUpdate();
        }
    }

    private List<CommunityApprovalOutcome.GrantedApproval> approvals(Connection connection, Proposal proposal)
            throws SQLException {
        List<CommunityApprovalOutcome.GrantedApproval> signatures = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT a.actor_uuid, m.role FROM rpg_community_approvals a
                JOIN rpg_community_members m ON m.community_id = a.community_id AND m.player_uuid = a.actor_uuid
                WHERE a.community_id = ? AND a.action = ? AND a.subject = ? AND a.status = 'APPROVED'
                  AND m.joined_at <= a.created_at
                ORDER BY a.created_at, a.actor_uuid
                """)) {
            query.setString(1, proposal.communityId().toString());
            query.setString(2, proposal.action().approvalKey());
            query.setString(3, proposal.id().toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    CommunityRole role = CommunityRole.parse(rows.getString("role")).orElse(null);
                    if (CommunityRolePower.countsAsApproval(role)) {
                        signatures.add(new CommunityApprovalOutcome.GrantedApproval(
                                UUID.fromString(rows.getString("actor_uuid")), role));
                    }
                }
            }
        }
        return List.copyOf(signatures);
    }

    private int approvalCount(Connection connection, Proposal proposal) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_community_approvals
                WHERE community_id = ? AND action = ? AND subject = ? AND status = 'APPROVED'
                """)) {
            query.setString(1, proposal.communityId().toString());
            query.setString(2, proposal.action().approvalKey());
            query.setString(3, proposal.id().toString());
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void finish(Connection connection, Proposal proposal, State state, UUID actorId, Instant now,
                        String transition, String detail) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_community_lifecycle_proposals
                SET state = ?, active_community_id = NULL, finished_at = ?
                WHERE proposal_id = ? AND state IN ('PENDING', 'COOLING')
                """)) {
            update.setString(1, state.name());
            update.setTimestamp(2, Timestamp.from(now));
            update.setString(3, proposal.id().toString());
            requireOne(update.executeUpdate(), "Finalizing lifecycle proposal");
        }
        audit(connection, proposal, actorId, transition, detail, now);
    }

    private void audit(Connection connection, Proposal proposal, UUID actorId, String transition,
                       String detail, Instant now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_community_lifecycle_audit
                    (audit_id, community_id, proposal_id, actor_uuid, transition, detail, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, proposal.communityId().toString());
            insert.setString(3, proposal.id().toString());
            insert.setString(4, actorId.toString());
            insert.setString(5, transition);
            insert.setString(6, detail);
            insert.setTimestamp(7, Timestamp.from(now));
            insert.executeUpdate();
        }
    }

    private void changeRole(Connection connection, UUID communityId, UUID playerId, CommunityRole role)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_community_members SET role = ? WHERE community_id = ? AND player_uuid = ?
                """)) {
            update.setString(1, role.name());
            update.setString(2, communityId.toString());
            update.setString(3, playerId.toString());
            requireOne(update.executeUpdate(), "Changing community role");
        }
    }

    private void deleteByCommunity(Connection connection, String table, UUID communityId) throws SQLException {
        // Fixed internal table names only; histories and economic ledgers are never deleted.
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE community_id = ?")) {
            delete.setString(1, communityId.toString());
            delete.executeUpdate();
        }
    }

    private CommunityLifecycleResult result(Connection connection, Status status, Proposal proposal) throws SQLException {
        return new CommunityLifecycleResult(status, Optional.of(view(connection, proposal)));
    }

    private CommunityLifecycleProposal view(Connection connection, Proposal proposal) throws SQLException {
        return new CommunityLifecycleProposal(proposal.id(), proposal.communityId(), proposal.action(),
                proposal.proposerId(), proposal.incumbentOwnerId(), Optional.ofNullable(proposal.candidateId()),
                proposal.state(), proposal.createdAt(), Optional.ofNullable(proposal.executeAfter()),
                Optional.ofNullable(proposal.finishedAt()), approvals(connection, proposal));
    }

    private Instant now(Connection connection) throws SQLException {
        if (testClock != null) return testClock.instant();
        try (PreparedStatement query = connection.prepareStatement("SELECT CURRENT_TIMESTAMP");
             ResultSet row = query.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean governor(Member member) {
        return member != null && CommunityRolePower.countsAsApproval(member.role());
    }

    private static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9:_-]{1,96}");
    }

    private static Status terminalStatus(Proposal proposal, Status otherwise) {
        return proposal.state() == State.EXECUTED ? Status.EXECUTED
                : proposal.state() == State.CANCELLED ? Status.CANCELLED : otherwise;
    }

    private static CompletionStage<CommunityLifecycleResult> completed(Status status) {
        return CompletableFuture.completedFuture(CommunityLifecycleResult.of(status));
    }

    private static void requireOne(int changed, String operation) throws SQLException {
        if (changed != 1) throw new SQLException(operation + " affected " + changed + " rows");
    }

    private <T> T transaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Community lifecycle transaction failed", failure);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private enum Decision { APPROVE, EXECUTE, CANCEL }

    private record Community(UUID id, UUID ownerId, String name, String tag, boolean dissolved) {}

    private record Member(UUID id, CommunityRole role, Instant joinedAt, Instant lastAuthenticated) {}

    private record Proposal(UUID id, UUID communityId, CommunityLifecycleAction action, UUID proposerId,
                            UUID incumbentOwnerId, UUID candidateId, Instant candidateJoinedAt,
                            Instant ownerLastAuthenticated, State state, Instant createdAt,
                            Instant executeAfter, Instant finishedAt) {}
}
