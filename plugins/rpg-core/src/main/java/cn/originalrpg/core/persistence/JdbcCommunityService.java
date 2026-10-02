package cn.originalrpg.core.persistence;

import cn.originalrpg.api.event.CommunityApprovalSubmittedEvent;
import cn.originalrpg.api.event.CommunityGovernanceActionEffectiveEvent;
import cn.originalrpg.api.event.CommunityRoleRejectionEvent;
import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityContributionResult;
import cn.originalrpg.api.model.CommunityGovernanceAction;
import cn.originalrpg.api.model.CommunityLeaderboardEntry;
import cn.originalrpg.api.model.CommunityMembership;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityProfile;
import cn.originalrpg.api.model.CommunityProjectRewardResult;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.model.CommunitySummary;
import cn.originalrpg.api.model.MentorBondResult;
import cn.originalrpg.api.service.CommunityGovernanceService;
import cn.originalrpg.api.service.CommunityRoleAssignmentService;
import cn.originalrpg.api.service.CommunityService;
import org.bukkit.event.Event;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcCommunityService
        implements CommunityService, CommunityGovernanceService, CommunityRoleAssignmentService {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} _-]{1,19}");
    private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{N}]{2,5}");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{6,16}");
    private static final Pattern PERIOD = Pattern.compile("[A-Za-z0-9._:-]{1,24}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{3,60}");
    private static final BigDecimal MAX_GOLD = new BigDecimal("1000000000000.00");
    private static final int MAX_VERIFIED_MENTEES = 1_000_000_000;
    private static final int MAX_VERIFIED_RECRUITS = 1_000_000_000;
    private static final long MAX_COMMUNITY_HONOR = 1_000_000_000_000L;
    private static final int MAX_WEEKLY_COMMEMORATIONS = 1_000_000_000;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final DataSource dataSource;
    private final Executor executor;
    /** C-005 Kill Switch；为 null 表示未接入注册表。 */
    private final MechanismRegistry mechanisms;
    /** Phase 6 治理接线开关（默认关）；同时决定事件是否发布。 */
    private final boolean governanceEnabled;
    /** 事件发布口；单测传 null，运行时传 Bukkit 调度器。 */
    private final java.util.function.Consumer<Event> eventPublisher;

    public JdbcCommunityService(DataSource dataSource, Executor executor) {
        this(dataSource, executor, null);
    }

    public JdbcCommunityService(DataSource dataSource, Executor executor, MechanismRegistry mechanisms) {
        this(dataSource, executor, mechanisms, false);
    }

    /** 治理开关关闭时的既有签名：同样必须把事件交给 Bukkit，否则治理事件会被静默吞掉。 */
    public JdbcCommunityService(
            DataSource dataSource, Executor executor, MechanismRegistry mechanisms, boolean governanceEnabled
    ) {
        this(dataSource, executor, mechanisms, governanceEnabled,
                event -> org.bukkit.Bukkit.getPluginManager().callEvent(event));
    }

    public JdbcCommunityService(
            DataSource dataSource,
            Executor executor,
            MechanismRegistry mechanisms,
            boolean governanceEnabled,
            java.util.function.Consumer<Event> eventPublisher
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.mechanisms = mechanisms;
        this.governanceEnabled = governanceEnabled;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public CompletionStage<CommunityProfile> profile(UUID playerId, String periodKey, BigDecimal periodGoal) {
        if (playerId == null || !validPeriod(periodKey) || !positiveMoney(periodGoal)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid community profile query"));
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Identity identity = ensureIdentity(connection, playerId);
            Optional<CommunityMembership> membership = membership(connection, playerId, periodKey, goal);
            return new CommunityProfile(playerId, identity.inviteCode(), Optional.ofNullable(identity.mentorId()),
                    identity.mentorVerified(), identity.verifiedMentees(), identity.communityHonor(),
                    identity.weeklyCommemorations(), rewardClaimed(connection, playerId, periodKey), membership);
        }));
    }

    @Override
    public CompletionStage<List<CommunitySummary>> browse(String periodKey, BigDecimal periodGoal, int limit) {
        if (!validPeriod(periodKey) || !positiveMoney(periodGoal) || limit < 1 || limit > 100) {
            return CompletableFuture.completedFuture(List.of());
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(
                connection -> browse(connection, periodKey, goal, limit)));
    }

    @Override
    public CompletionStage<List<CommunityLeaderboardEntry>> leaderboard(
            String periodKey, BigDecimal periodGoal, int limit
    ) {
        if (!validPeriod(periodKey) || !positiveMoney(periodGoal) || limit < 1 || limit > 100) {
            return CompletableFuture.completedFuture(List.of());
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            List<CommunitySummary> communities = browse(connection, periodKey, goal, limit);
            List<CommunityLeaderboardEntry> entries = new ArrayList<>(communities.size());
            for (int index = 0; index < communities.size(); index++) {
                entries.add(new CommunityLeaderboardEntry(index + 1, communities.get(index)));
            }
            return List.copyOf(entries);
        }));
    }

    @Override
    public CompletionStage<CommunityOperationResult> create(
            UUID owner,
            String name,
            String tag,
            int capacity,
            int minimumEffectiveLevel,
            BigDecimal creationCost,
            BigDecimal startingBalance,
            String idempotencyKey
    ) {
        String cleanName = cleanName(name);
        String cleanTag = cleanTag(tag);
        if (owner == null || cleanName == null || cleanTag == null || capacity < 2 || capacity > 200
                || minimumEffectiveLevel < 1 || !nonNegativeMoney(creationCost)
                || !nonNegativeMoney(startingBalance) || !validKey(idempotencyKey)) {
            return CompletableFuture.completedFuture(invalidOperation());
        }
        BigDecimal cost = money(creationCost);
        BigDecimal initial = money(startingBalance);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            lockIdentity(connection, owner);
            BigDecimal balance = loadOrCreateAccount(connection, owner, initial);
            balance = lockBalance(connection, owner);
            String ledgerKey = "community:create:" + idempotencyKey;
            if (ledgerExists(connection, ledgerKey)) {
                return operation(CommunityOperationResult.Status.DUPLICATE,
                        membership(connection, owner, "replay", BigDecimal.ONE)
                                .map(CommunityMembership::community), balance);
            }
            if (membershipCommunity(connection, owner) != null) {
                return operation(CommunityOperationResult.Status.ALREADY_MEMBER, Optional.empty(), balance);
            }
            if (effectiveLevel(connection, owner) < minimumEffectiveLevel) {
                return operation(CommunityOperationResult.Status.LEVEL_REQUIRED, Optional.empty(), balance);
            }
            if (communityNameExists(connection, normalize(cleanName))) {
                return operation(CommunityOperationResult.Status.NAME_TAKEN, Optional.empty(), balance);
            }
            if (communityTagExists(connection, normalize(cleanTag))) {
                return operation(CommunityOperationResult.Status.TAG_TAKEN, Optional.empty(), balance);
            }
            if (balance.compareTo(cost) < 0) {
                return operation(CommunityOperationResult.Status.INSUFFICIENT_GOLD, Optional.empty(), balance);
            }

            UUID communityId = UUID.randomUUID();
            int inserted = insertCommunity(connection, communityId, cleanName, cleanTag, owner, capacity);
            if (inserted != 1) {
                CommunityOperationResult.Status conflict = communityNameExists(connection, normalize(cleanName))
                        ? CommunityOperationResult.Status.NAME_TAKEN : CommunityOperationResult.Status.TAG_TAKEN;
                return operation(conflict, Optional.empty(), balance);
            }
            insertMember(connection, communityId, owner, CommunityRole.OWNER);
            if (cost.signum() > 0) {
                insertGoldLedger(connection, ledgerKey, owner, cost, "community-creation");
                balance = money(balance.subtract(cost));
                updateBalance(connection, owner, balance);
            }
            CommunitySummary summary = summary(connection, communityId, "create", BigDecimal.ONE)
                    .orElseThrow(() -> new IllegalStateException("Created community disappeared"));
            return operation(CommunityOperationResult.Status.SUCCESS, Optional.of(summary), balance);
        }));
    }

    @Override
    public CompletionStage<CommunityOperationResult> invite(
            UUID inviter,
            UUID target,
            int maximumPendingInvites,
            int expiryHours,
            String periodKey,
            BigDecimal periodGoal
    ) {
        if (inviter == null || target == null || inviter.equals(target)
                || maximumPendingInvites < 1 || maximumPendingInvites > 100
                || expiryHours < 1 || expiryHours > 168
                || !validPeriod(periodKey) || !positiveMoney(periodGoal)) {
            return CompletableFuture.completedFuture(invalidOperation());
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow inviterMembership = memberRow(connection, inviter);
            if (inviterMembership == null) return status(CommunityOperationResult.Status.NOT_MEMBER);
            if (membershipCommunity(connection, target) != null) {
                return operation(CommunityOperationResult.Status.ALREADY_MEMBER,
                        summary(connection, inviterMembership.communityId(), periodKey, goal), ZERO);
            }
            expireInvites(connection);
            if (pendingInvites(connection, inviter) >= maximumPendingInvites) {
                return operation(CommunityOperationResult.Status.RATE_LIMITED,
                        summary(connection, inviterMembership.communityId(), periodKey, goal), ZERO);
            }
            ensureIdentity(connection, target);
            deleteInvite(connection, inviterMembership.communityId(), target);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_community_invites
                        (community_id, inviter_uuid, target_uuid, expires_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                insert.setString(1, inviterMembership.communityId().toString());
                insert.setString(2, inviter.toString());
                insert.setString(3, target.toString());
                insert.setTimestamp(4, Timestamp.from(Instant.now().plus(expiryHours, ChronoUnit.HOURS)));
                insert.executeUpdate();
            }
            return operation(CommunityOperationResult.Status.SUCCESS,
                    summary(connection, inviterMembership.communityId(), periodKey, goal), ZERO);
        }));
    }

    @Override
    public CompletionStage<CommunityOperationResult> accept(
            UUID playerId, String communityTag, String periodKey, BigDecimal periodGoal
    ) {
        String cleanTag = cleanTag(communityTag);
        if (playerId == null || cleanTag == null || !validPeriod(periodKey) || !positiveMoney(periodGoal)) {
            return CompletableFuture.completedFuture(invalidOperation());
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            if (membershipCommunity(connection, playerId) != null) {
                return operation(CommunityOperationResult.Status.ALREADY_MEMBER, Optional.empty(), ZERO);
            }
            CommunityLock community = lockCommunityByTag(connection, normalize(cleanTag));
            if (community == null || !validInvite(connection, community.id(), playerId)) {
                return status(CommunityOperationResult.Status.INVITE_MISSING);
            }
            if (memberCount(connection, community.id()) >= community.capacity()) {
                return operation(CommunityOperationResult.Status.COMMUNITY_FULL,
                        summary(connection, community.id(), periodKey, goal), ZERO);
            }
            if (!insertMember(connection, community.id(), playerId, CommunityRole.MEMBER)) {
                return operation(CommunityOperationResult.Status.ALREADY_MEMBER,
                        summary(connection, community.id(), periodKey, goal), ZERO);
            }
            deleteAllInvites(connection, playerId);
            return operation(CommunityOperationResult.Status.SUCCESS,
                    summary(connection, community.id(), periodKey, goal), ZERO);
        }));
    }

    @Override
    public CompletionStage<CommunityOperationResult> leave(
            UUID playerId, String periodKey, BigDecimal periodGoal
    ) {
        if (playerId == null || !validPeriod(periodKey) || !positiveMoney(periodGoal)) {
            return CompletableFuture.completedFuture(invalidOperation());
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow member = lockMemberRow(connection, playerId);
            if (member == null) return status(CommunityOperationResult.Status.NOT_MEMBER);
            if (member.role() == CommunityRole.OWNER) {
                return operation(CommunityOperationResult.Status.OWNER_MUST_TRANSFER,
                        summary(connection, member.communityId(), periodKey, goal), ZERO);
            }
            deleteMember(connection, member.communityId(), playerId);
            return operation(CommunityOperationResult.Status.SUCCESS,
                    summary(connection, member.communityId(), periodKey, goal), ZERO);
        }));
    }

    @Override
    public CompletionStage<CommunityOperationResult> kick(
            UUID actor, UUID target, String periodKey, BigDecimal periodGoal
    ) {
        if (actor == null || target == null || actor.equals(target)
                || !validPeriod(periodKey) || !positiveMoney(periodGoal)) {
            return CompletableFuture.completedFuture(invalidOperation());
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow actorRow = memberRow(connection, actor);
            MemberRow targetRow = memberRow(connection, target);
            if (actorRow == null) return status(CommunityOperationResult.Status.NOT_MEMBER);
            if (targetRow == null || !targetRow.communityId().equals(actorRow.communityId())) {
                return operation(CommunityOperationResult.Status.TARGET_NOT_MEMBER,
                        summary(connection, actorRow.communityId(), periodKey, goal), ZERO);
            }
            boolean allowed = actorRow.role() == CommunityRole.OWNER && targetRow.role() != CommunityRole.OWNER
                    || actorRow.role() == CommunityRole.OFFICER && targetRow.role() == CommunityRole.MEMBER;
            if (!allowed) {
                return operation(CommunityOperationResult.Status.NOT_ALLOWED,
                        summary(connection, actorRow.communityId(), periodKey, goal), ZERO);
            }
            deleteMember(connection, actorRow.communityId(), target);
            return operation(CommunityOperationResult.Status.SUCCESS,
                    summary(connection, actorRow.communityId(), periodKey, goal), ZERO);
        }));
    }

    @Override
    public CompletionStage<CommunityOperationResult> transfer(
            UUID owner, UUID target, String periodKey, BigDecimal periodGoal
    ) {
        if (owner == null || target == null || owner.equals(target)
                || !validPeriod(periodKey) || !positiveMoney(periodGoal)) {
            return CompletableFuture.completedFuture(invalidOperation());
        }
        BigDecimal goal = money(periodGoal);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow ownerRow = memberRow(connection, owner);
            MemberRow targetRow = memberRow(connection, target);
            if (ownerRow == null) return status(CommunityOperationResult.Status.NOT_MEMBER);
            if (ownerRow.role() != CommunityRole.OWNER) {
                return operation(CommunityOperationResult.Status.NOT_ALLOWED,
                        summary(connection, ownerRow.communityId(), periodKey, goal), ZERO);
            }
            if (targetRow == null || !targetRow.communityId().equals(ownerRow.communityId())) {
                return operation(CommunityOperationResult.Status.TARGET_NOT_MEMBER,
                        summary(connection, ownerRow.communityId(), periodKey, goal), ZERO);
            }
            updateRole(connection, ownerRow.communityId(), owner, CommunityRole.OFFICER);
            updateRole(connection, ownerRow.communityId(), target, CommunityRole.OWNER);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_communities SET owner_uuid = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """)) {
                update.setString(1, target.toString());
                update.setString(2, ownerRow.communityId().toString());
                update.executeUpdate();
            }
            return operation(CommunityOperationResult.Status.SUCCESS,
                    summary(connection, ownerRow.communityId(), periodKey, goal), ZERO);
        }));
    }

    @Override
    public CompletionStage<MentorBondResult> bindMentor(
            UUID playerId,
            String inviteCode,
            int effectiveLevel,
            long playMinutes,
            int maximumEffectiveLevel,
            long maximumPlayMinutes,
            int minimumMentorEffectiveLevel
    ) {
        String code = inviteCode == null ? "" : inviteCode.strip().toUpperCase(Locale.ROOT);
        if (playerId == null || !CODE.matcher(code).matches() || effectiveLevel < 1 || playMinutes < 0
                || maximumEffectiveLevel < 1 || maximumPlayMinutes < 1 || minimumMentorEffectiveLevel < 1) {
            return CompletableFuture.completedFuture(mentor(MentorBondResult.Status.INVALID, null, null, false, 0));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Identity player = ensureIdentity(connection, playerId);
            if (player.mentorId() != null) {
                return mentor(MentorBondResult.Status.ALREADY_BOUND, player.mentorId(), null,
                        player.mentorVerified(), mentorScore(connection, player.mentorId()));
            }
            if (effectiveLevel > maximumEffectiveLevel || playMinutes > maximumPlayMinutes) {
                return mentor(MentorBondResult.Status.BIND_WINDOW_CLOSED, null, null, false, 0);
            }
            Identity mentor = identityByCode(connection, code);
            if (mentor == null) return mentor(MentorBondResult.Status.CODE_NOT_FOUND, null, null, false, 0);
            if (mentor.playerId().equals(playerId)) {
                return mentor(MentorBondResult.Status.SELF_REFERENCE, null, null, false, 0);
            }
            if (effectiveLevel(connection, mentor.playerId()) < minimumMentorEffectiveLevel) {
                return mentor(MentorBondResult.Status.MENTOR_NOT_READY, mentor.playerId(), null, false,
                        mentor.verifiedMentees());
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_community_identity
                    SET mentor_uuid = ?, mentor_bound_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ? AND mentor_uuid IS NULL
                    """)) {
                update.setString(1, mentor.playerId().toString());
                update.setString(2, playerId.toString());
                if (update.executeUpdate() != 1) {
                    Identity replay = identity(connection, playerId);
                    return mentor(MentorBondResult.Status.ALREADY_BOUND, replay.mentorId(), null,
                            replay.mentorVerified(), mentorScore(connection, replay.mentorId()));
                }
            }
            return mentor(MentorBondResult.Status.SUCCESS, mentor.playerId(), null, false,
                    mentor.verifiedMentees());
        }));
    }

    @Override
    public CompletionStage<MentorBondResult> verifyMentorBond(
            UUID playerId,
            boolean journeyComplete,
            int effectiveLevel,
            long playMinutes,
            int minimumEffectiveLevel,
            long minimumPlayMinutes,
            String periodKey
    ) {
        if (playerId == null || effectiveLevel < 1 || playMinutes < 0 || minimumEffectiveLevel < 1
                || minimumPlayMinutes < 1 || !validPeriod(periodKey)) {
            return CompletableFuture.completedFuture(mentor(MentorBondResult.Status.INVALID, null, null, false, 0));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Identity player = ensureIdentity(connection, playerId);
            if (player.mentorId() == null) {
                return mentor(MentorBondResult.Status.NOT_BOUND, null, null, false, 0);
            }
            if (player.mentorVerified()) {
                return mentor(MentorBondResult.Status.ALREADY_VERIFIED, player.mentorId(), null, true,
                        mentorScore(connection, player.mentorId()));
            }
            if (!journeyComplete || effectiveLevel < minimumEffectiveLevel || playMinutes < minimumPlayMinutes) {
                return mentor(MentorBondResult.Status.NOT_READY, player.mentorId(), null, false,
                        mentorScore(connection, player.mentorId()));
            }
            Identity lockedPlayer = lockIdentity(connection, playerId);
            if (lockedPlayer.mentorVerified()) {
                return mentor(MentorBondResult.Status.ALREADY_VERIFIED, lockedPlayer.mentorId(), null, true,
                        mentorScore(connection, lockedPlayer.mentorId()));
            }
            UUID mentorId = lockedPlayer.mentorId();
            if (mentorId == null) return mentor(MentorBondResult.Status.NOT_BOUND, null, null, false, 0);
            Identity lockedMentor = lockIdentity(connection, mentorId);
            if (lockedMentor.verifiedMentees() >= MAX_VERIFIED_MENTEES) {
                return mentor(MentorBondResult.Status.PROGRESS_LIMIT, mentorId, null, false,
                        lockedMentor.verifiedMentees());
            }
            UUID communityId = membershipCommunity(connection, mentorId);
            if (communityId != null) {
                ensureProject(connection, communityId, periodKey);
                ProjectRow project = lockProject(connection, communityId, periodKey);
                if (project.verifiedRecruits() >= MAX_VERIFIED_RECRUITS) {
                    return mentor(MentorBondResult.Status.PROGRESS_LIMIT, mentorId, communityId, false,
                            lockedMentor.verifiedMentees());
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_community_identity
                    SET mentor_verified = TRUE, mentor_verified_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ? AND mentor_verified = FALSE
                    """)) {
                update.setString(1, playerId.toString());
                if (update.executeUpdate() != 1) {
                    return mentor(MentorBondResult.Status.ALREADY_VERIFIED, mentorId, null, true,
                            mentorScore(connection, mentorId));
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_community_identity
                    SET verified_mentees = verified_mentees + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ?
                    """)) {
                update.setString(1, mentorId.toString());
                update.executeUpdate();
            }
            if (communityId != null) {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE rpg_community_projects
                        SET verified_recruits = verified_recruits + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE community_id = ? AND period_key = ?
                        """)) {
                    update.setString(1, communityId.toString());
                    update.setString(2, periodKey);
                    update.executeUpdate();
                }
            }
            return mentor(MentorBondResult.Status.SUCCESS, mentorId, communityId, true,
                    mentorScore(connection, mentorId));
        }));
    }

    @Override
    public CompletionStage<CommunityContributionResult> contributeGold(
            UUID playerId,
            BigDecimal amount,
            BigDecimal playerPeriodCap,
            BigDecimal periodGoal,
            BigDecimal startingBalance,
            String periodKey,
            String idempotencyKey
    ) {
        if (playerId == null || !positiveMoney(amount) || !positiveMoney(playerPeriodCap)
                || !positiveMoney(periodGoal) || !nonNegativeMoney(startingBalance)
                || !validPeriod(periodKey) || !validKey(idempotencyKey)) {
            return CompletableFuture.completedFuture(contribution(
                    CommunityContributionResult.Status.INVALID, ZERO, ZERO, ZERO, ZERO, BigDecimal.ONE, false));
        }
        BigDecimal normalized = money(amount);
        BigDecimal cap = money(playerPeriodCap);
        BigDecimal goal = money(periodGoal);
        BigDecimal initial = money(startingBalance);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            ExistingContribution replay = existingContribution(connection, idempotencyKey);
            if (replay != null && replay.playerId().equals(playerId)) {
                BigDecimal balance = loadOrCreateAccount(connection, playerId, initial);
                BigDecimal playerTotal = playerContribution(connection, playerId, replay.periodKey());
                BigDecimal communityTotal = projectTotal(connection, replay.communityId(), replay.periodKey());
                return contribution(CommunityContributionResult.Status.DUPLICATE,
                        replay.amount(), balance, playerTotal, communityTotal, goal, false);
            }
            MemberRow member = lockMemberRow(connection, playerId);
            if (member == null) {
                return contribution(CommunityContributionResult.Status.NOT_MEMBER,
                        normalized, ZERO, ZERO, ZERO, goal, false);
            }
            BigDecimal balance = loadOrCreateAccount(connection, playerId, initial);
            replay = existingContribution(connection, idempotencyKey);
            if (replay != null) {
                if (!replay.playerId().equals(playerId)) {
                    return contribution(CommunityContributionResult.Status.INVALID,
                            normalized, balance, ZERO, ZERO, goal, false);
                }
                BigDecimal playerTotal = playerContribution(connection, playerId, periodKey);
                BigDecimal communityTotal = projectTotal(connection, replay.communityId(), replay.periodKey());
                return contribution(CommunityContributionResult.Status.DUPLICATE,
                        replay.amount(), balance, playerTotal, communityTotal, goal, false);
            }
            ensureProject(connection, member.communityId(), periodKey);
            ProjectRow project = lockProject(connection, member.communityId(), periodKey);
            replay = existingContribution(connection, idempotencyKey);
            if (replay != null) {
                BigDecimal replayPlayerTotal = playerContribution(connection, playerId, periodKey);
                return contribution(CommunityContributionResult.Status.DUPLICATE,
                        replay.amount(), balance, replayPlayerTotal, project.gold(), goal, false);
            }
            BigDecimal playerTotal = playerContribution(connection, playerId, periodKey);
            if (playerTotal.add(normalized).compareTo(cap) > 0) {
                return contribution(CommunityContributionResult.Status.PLAYER_LIMIT_REACHED,
                        normalized, balance, playerTotal, project.gold(), goal, false);
            }
            BigDecimal lifetimeTotal = lifetimeContribution(connection, playerId).add(normalized);
            BigDecimal communityTotal = project.gold().add(normalized);
            if (lifetimeTotal.compareTo(MAX_GOLD) > 0 || communityTotal.compareTo(MAX_GOLD) > 0) {
                return contribution(CommunityContributionResult.Status.PROGRESS_LIMIT,
                        normalized, balance, playerTotal, project.gold(), goal, false);
            }
            balance = lockBalance(connection, playerId);
            if (balance.compareTo(normalized) < 0) {
                return contribution(CommunityContributionResult.Status.INSUFFICIENT_GOLD,
                        normalized, balance, playerTotal, project.gold(), goal, false);
            }
            insertContribution(connection, idempotencyKey, member.communityId(), playerId, periodKey, normalized);
            insertGoldLedger(connection, "community:project:" + idempotencyKey,
                    playerId, normalized, "community-project:" + periodKey);
            BigDecimal updatedBalance = money(balance.subtract(normalized));
            updateBalance(connection, playerId, updatedBalance);
            communityTotal = money(communityTotal);
            boolean newlyCompleted = project.completedAt() == null && communityTotal.compareTo(goal) >= 0;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_community_projects
                    SET gold_contributed = ?,
                        completed_at = CASE WHEN completed_at IS NULL AND ? >= ? THEN CURRENT_TIMESTAMP
                                            ELSE completed_at END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE community_id = ? AND period_key = ?
                    """)) {
                update.setBigDecimal(1, communityTotal);
                update.setBigDecimal(2, communityTotal);
                update.setBigDecimal(3, goal);
                update.setString(4, member.communityId().toString());
                update.setString(5, periodKey);
                update.executeUpdate();
            }
            return contribution(CommunityContributionResult.Status.SUCCESS, normalized, updatedBalance,
                    money(playerTotal.add(normalized)), communityTotal, goal, newlyCompleted);
        }));
    }

    @Override
    public CompletionStage<CommunityProjectRewardResult> claimWeeklyProjectReward(
            UUID playerId,
            String periodKey,
            BigDecimal periodGoal,
            BigDecimal minimumContribution,
            int honorAward
    ) {
        if (playerId == null || !validPeriod(periodKey) || !positiveMoney(periodGoal)
                || !positiveMoney(minimumContribution) || honorAward < 1 || honorAward > 1_000_000) {
            return CompletableFuture.completedFuture(projectReward(
                    CommunityProjectRewardResult.Status.INVALID, validPeriod(periodKey) ? periodKey : "invalid",
                    0, 0, 0, ZERO, safeMoney(minimumContribution)));
        }
        // C-005：社区工程周期奖励发放荣誉/纪念，属 faucet，受 Kill Switch 熔断。
        if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.COMMUNITY_PROJECT)) {
            return CompletableFuture.completedFuture(projectReward(
                    CommunityProjectRewardResult.Status.INVALID, periodKey,
                    0, 0, 0, ZERO, safeMoney(minimumContribution)));
        }
        BigDecimal goal = money(periodGoal);
        BigDecimal minimum = money(minimumContribution);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Identity identity = lockIdentity(connection, playerId);
            MemberRow member = lockMemberRow(connection, playerId);
            if (member == null) {
                return projectReward(CommunityProjectRewardResult.Status.NOT_MEMBER, periodKey,
                        0, identity.communityHonor(), identity.weeklyCommemorations(), ZERO, minimum);
            }

            ProjectRewardReceipt receipt = projectRewardReceipt(connection, playerId, periodKey);
            if (receipt != null) {
                return projectReward(CommunityProjectRewardResult.Status.DUPLICATE, periodKey,
                        receipt.honorAwarded(), identity.communityHonor(), identity.weeklyCommemorations(),
                        receipt.playerContribution(), receipt.minimumContribution());
            }

            ensureProject(connection, member.communityId(), periodKey);
            ProjectRow project = lockProject(connection, member.communityId(), periodKey);
            BigDecimal contribution = playerContribution(
                    connection, playerId, member.communityId(), periodKey);
            if (project.completedAt() == null && project.gold().compareTo(goal) < 0) {
                return projectReward(CommunityProjectRewardResult.Status.PROJECT_INCOMPLETE, periodKey,
                        0, identity.communityHonor(), identity.weeklyCommemorations(), contribution, minimum);
            }
            if (contribution.compareTo(minimum) < 0) {
                return projectReward(CommunityProjectRewardResult.Status.CONTRIBUTION_REQUIRED, periodKey,
                        0, identity.communityHonor(), identity.weeklyCommemorations(), contribution, minimum);
            }
            if (identity.communityHonor() > MAX_COMMUNITY_HONOR - honorAward
                    || identity.weeklyCommemorations() >= MAX_WEEKLY_COMMEMORATIONS) {
                return projectReward(CommunityProjectRewardResult.Status.PROGRESS_LIMIT, periodKey,
                        0, identity.communityHonor(), identity.weeklyCommemorations(), contribution, minimum);
            }

            insertProjectRewardReceipt(connection, playerId, member.communityId(), periodKey,
                    contribution, minimum, honorAward);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_community_identity
                    SET community_honor = community_honor + ?,
                        weekly_commemorations = weekly_commemorations + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE player_uuid = ?
                    """)) {
                update.setInt(1, honorAward);
                update.setString(2, playerId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Community reward identity disappeared");
                }
            }
            return projectReward(CommunityProjectRewardResult.Status.SUCCESS, periodKey, honorAward,
                    Math.addExact(identity.communityHonor(), honorAward),
                    Math.addExact(identity.weeklyCommemorations(), 1), contribution, minimum);
        }));
    }

    // ------------------------------------------------------------------
    // Phase 6 社群治理：角色能力 + 双批审批 + 审计
    // ------------------------------------------------------------------

    @Override
    public boolean enabled() {
        return governanceEnabled;
    }

    /**
     * 成员是否治理成员（能否进入治理通道）。
     *
     * <p>对外可查询：命令层的治理通道准入判定必须问这里，不能在别处重新写角色表。
     * 判定直接走 {@link CommunityRole#isGovernance()} —— 成员行只知道角色，因此这里能拿到真角色名。
     * 与「能否投票」（APPROVE）是两条口径：准入回答「能不能进治理通道」，投票回答「能不能批」。</p>
     */
    @Override
    public CompletionStage<Boolean> isGovernanceMember(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.completedFuture(false);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow member = memberRow(connection, playerId);
            return member != null && member.role().isGovernance();
        }));
    }

    @Override
    public CompletionStage<Boolean> can(UUID playerId, CommunityRolePower.Capability capability) {
        if (playerId == null || capability == null) {
            return CompletableFuture.completedFuture(false);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow member = memberRow(connection, playerId);
            return member != null && CommunityRolePower.can(member.role(), capability);
        }));
    }

    @Override
    public CompletionStage<Optional<CommunityGovernanceAction>> pending(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow member = memberRow(connection, playerId);
            if (member == null) return Optional.<CommunityGovernanceAction>empty();
            for (CommunityGovernanceAction action : CommunityGovernanceAction.values()) {
                if (!approvals(connection, member.communityId(), action, action.key()).isEmpty()) {
                    return Optional.of(action);
                }
            }
            return Optional.<CommunityGovernanceAction>empty();
        }));
    }

    @Override
    public CompletionStage<CommunityApprovalOutcome> submit(
            UUID actor,
            CommunityGovernanceAction action,
            String subject,
            String idempotencyKey
    ) {
        // 前置校验与事务体都走同一条路径，避免两处实现漂移。
        // subject 必须先归一化再传入：decide() 拿到的应当是真正的目标（工程=动作键），
        // 否则 roleQualified() 会因为拿不到动作而直接拒绝提交。
        if (action == null) {
            return CompletableFuture.completedFuture(
                    approval(CommunityApprovalOutcome.Status.INVALID, CommunityGovernanceAction.ROLE_GRANT));
        }
        return decide(actor, action, cleanSubject(action, subject), null, null, idempotencyKey);
    }

    @Override
    public CompletionStage<CommunityApprovalOutcome> approve(
            UUID actor, CommunityGovernanceAction action, String subject
    ) {
        return decide(actor, action, subject, null, null, null);
    }

    @Override
    public CompletionStage<CommunityApprovalOutcome> approveAssignment(
            UUID actor, UUID target, String targetRole, String idempotencyKey
    ) {
        return decide(actor, CommunityGovernanceAction.ROLE_GRANT, subjectFor(target), target, targetRole,
                idempotencyKey);
    }

    @Override
    public CompletionStage<CommunityApprovalOutcome> deny(
            UUID actor, CommunityGovernanceAction action, String subject
    ) {
        if (actor == null || action == null) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.INVALID,
                    action == null ? CommunityGovernanceAction.ROLE_GRANT : action));
        }
        if (!governanceEnabled) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.DISABLED, action));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow member = lockMemberRow(connection, actor);
            if (member == null) return approval(CommunityApprovalOutcome.Status.NOT_MEMBER, action);
            if (!CommunityRolePower.can(member.role(), CommunityRolePower.Capability.DENY)) {
                return approval(CommunityApprovalOutcome.Status.NOT_ALLOWED, action);
            }
            String cleanSubject = cleanSubject(action, subject);
            if (cleanSubject == null) return approval(CommunityApprovalOutcome.Status.INVALID, action);
            int removed = deleteApprovals(connection, member.communityId(), action, cleanSubject);
            if (removed == 0) return approval(CommunityApprovalOutcome.Status.NO_PENDING_ACTION, action);
            return approval(CommunityApprovalOutcome.Status.SUCCESS, action);
        }));
    }

    @Override
    public CompletionStage<Optional<String>> subjects(UUID playerId, CommunityGovernanceAction action) {
        if (playerId == null || action == null) return CompletableFuture.completedFuture(Optional.empty());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow member = memberRow(connection, playerId);
            if (member == null) return Optional.<String>empty();
            return pendingSubject(connection, member.communityId(), action);
        }));
    }

    @Override
    public CompletionStage<List<CommunityApprovalOutcome.GrantedApproval>> approvals(
            UUID playerId, CommunityGovernanceAction action, String subject
    ) {
        if (playerId == null || action == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow member = memberRow(connection, playerId);
            if (member == null) return List.of();
            String cleanSubject = cleanSubject(action, subject);
            if (cleanSubject == null) return List.of();
            return approvals(connection, member.communityId(), action, cleanSubject);
        }));
    }

    @Override
    public CompletionStage<CommunityApprovalOutcome> request(
            UUID actor, UUID target, CommunityRole role, String idempotencyKey
    ) {
        if (actor == null || target == null || role == null || actor.equals(target)) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.INVALID, CommunityGovernanceAction.ROLE_GRANT));
        }
        if (role == CommunityRole.OWNER || role.canApprove()) {
            // 会长更换只能走既有 /community transfer 流程。
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.NOT_ALLOWED, CommunityGovernanceAction.ROLE_GRANT));
        }
        if (!governanceEnabled) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.DISABLED, CommunityGovernanceAction.ROLE_GRANT));
        }
        if (!validKey(idempotencyKey)) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.INVALID, CommunityGovernanceAction.ROLE_GRANT));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow actorRow = lockMemberRow(connection, actor);
            if (actorRow == null) return approval(CommunityApprovalOutcome.Status.NOT_MEMBER, CommunityGovernanceAction.ROLE_GRANT);
            if (!CommunityRolePower.can(actorRow.role(), CommunityRolePower.Capability.ASSIGN_ROLE)) {
                return approval(CommunityApprovalOutcome.Status.NOT_ALLOWED, CommunityGovernanceAction.ROLE_GRANT);
            }
            MemberRow targetRow = memberRow(connection, target);
            if (targetRow == null || !targetRow.communityId().equals(actorRow.communityId())) {
                return approval(CommunityApprovalOutcome.Status.TARGET_NOT_MEMBER,
                        CommunityGovernanceAction.ROLE_GRANT);
            }
            String cleanSubject = subjectFor(target);
            // 提交人第一票；第二票必须由另一名治理角色投出。
            CommunityApprovalOutcome submitted = submitOnConnection(
                    connection, actorRow, CommunityGovernanceAction.ROLE_GRANT, cleanSubject,
                    idempotencyKey);
            return submitted;
        }));
    }

    private CommunityApprovalOutcome submitOnConnection(
            Connection connection,
            MemberRow member,
            CommunityGovernanceAction action,
            String subject,
            String idempotencyKey
    ) throws SQLException {
        if (!CommunityRolePower.can(member.role(), CommunityRolePower.Capability.SUBMIT_APPROVAL)
                || !roleQualified(member.role(), action)) {
            return approval(CommunityApprovalOutcome.Status.NOT_ALLOWED, action);
        }
        if (!approvals(connection, member.communityId(), action, subject).isEmpty()) {
            return approval(CommunityApprovalOutcome.Status.DUPLICATE, action);
        }
        UUID approvalId = UUID.randomUUID();
        if (!insertApproval(connection, approvalId, member.communityId(), member.playerId(), action,
                subject, member.playerId(), idempotencyKey)) {
            return approval(CommunityApprovalOutcome.Status.DUPLICATE, action);
        }
        List<CommunityApprovalOutcome.GrantedApproval> recorded = approvals(
                connection, member.communityId(), action, subject);
        publish(new CommunityApprovalSubmittedEvent(member.communityId(), member.playerId(), action, subject));
        return new CommunityApprovalOutcome(CommunityApprovalOutcome.Status.SUCCESS, action,
                Optional.of(subject), recorded, member.playerId(), requiredApprovals());
    }

    /**
     * 批准 / 批准并任命角色的公共事务体。
     * 双批成立后动作立即生效：写入 ROLE_GRANT 的生效记录并发布生效事件。
     */
    private CompletionStage<CommunityApprovalOutcome> decide(
            UUID actor,
            CommunityGovernanceAction action,
            String subject,
            UUID roleTarget,
            String roleName,
            String idempotencyKey
    ) {
        if (actor == null || action == null) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.INVALID, CommunityGovernanceAction.ROLE_GRANT));
        }
        String cleanSubject = cleanSubject(action, subject);
        if (cleanSubject == null) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.INVALID, action));
        }
        if (!governanceEnabled) {
            return CompletableFuture.completedFuture(approval(CommunityApprovalOutcome.Status.DISABLED, action));
        }
        // ROLE_GRANT 必须带幂等键（提交/生效都要写审计）；其余动作没有幂等键也可以提交。
        if (action == CommunityGovernanceAction.ROLE_GRANT
                && (idempotencyKey == null || !validKey(idempotencyKey))) {
            return CompletableFuture.completedFuture(
                    approval(CommunityApprovalOutcome.Status.INVALID, action));
        }
        CommunityRole assignedRole = null;
        if (action == CommunityGovernanceAction.ROLE_GRANT) {
            assignedRole = CommunityRolePower.assignable(roleName).orElse(null);
            if (assignedRole == null || idempotencyKey == null || !validKey(idempotencyKey)) {
                return CompletableFuture.completedFuture(
                        approval(CommunityApprovalOutcome.Status.INVALID, action));
            }
        }
        CommunityRole finalRole = assignedRole;
        String finalSubject = cleanSubject;
        UUID finalTarget = roleTarget;
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            MemberRow actorRow = lockMemberRow(connection, actor);
            if (actorRow == null) return approval(CommunityApprovalOutcome.Status.NOT_MEMBER, action);
            if (action == CommunityGovernanceAction.ROLE_GRANT) {
                // 目标校验先于投票校验：跨会目标必须报 TARGET_NOT_MEMBER，而不是「没有待处理」。
                MemberRow targetRow = memberRow(connection, finalTarget);
                if (targetRow == null || !targetRow.communityId().equals(actorRow.communityId())) {
                    return approval(CommunityApprovalOutcome.Status.TARGET_NOT_MEMBER, action);
                }
            }
            List<CommunityApprovalOutcome.GrantedApproval> existing = approvals(
                    connection, actorRow.communityId(), action, finalSubject);
            if (existing.stream().anyMatch(approval -> approval.actor().equals(actor))) {
                // 同一人不能凑两次批准；该判定先于能力判定，避免把重复票误报成权限不足。
                return new CommunityApprovalOutcome(CommunityApprovalOutcome.Status.ALREADY_APPROVED, action,
                        Optional.of(finalSubject), existing, actor, requiredApprovals());
            }
            if (existing.isEmpty()) {
                // 还没有人投票：这次调用是提交（提交即第一票）。
                // 提交资格 = SUBMIT_APPROVAL ∪ 动作前置角色（例如工程需要 BUILDER），
                // 不能用 APPROVE 判定，否则领域角色永远发不起本领域动作。
                return submitOnConnection(connection, actorRow, action, finalSubject, idempotencyKey);
            }
            if (!CommunityRolePower.can(actorRow.role(), CommunityRolePower.Capability.APPROVE)) {
                return approval(CommunityApprovalOutcome.Status.NOT_ALLOWED, action);
            }
            UUID requester = requesterOf(connection, actorRow.communityId(), action, finalSubject);
            if (requester != null && requester.equals(actor)) {
                return new CommunityApprovalOutcome(CommunityApprovalOutcome.Status.SELF_APPROVAL, action,
                        Optional.of(finalSubject), existing, actor, requiredApprovals());
            }
            UUID approvalId = UUID.randomUUID();
            if (!insertApproval(connection, approvalId, actorRow.communityId(), actor, action,
                    finalSubject, requester, idempotencyKey)) {
                List<CommunityApprovalOutcome.GrantedApproval> recorded = approvals(
                        connection, actorRow.communityId(), action, finalSubject);
                return new CommunityApprovalOutcome(CommunityApprovalOutcome.Status.DUPLICATE, action,
                        Optional.of(finalSubject), recorded, actor, requiredApprovals());
            }
            List<CommunityApprovalOutcome.GrantedApproval> recorded = approvals(
                    connection, actorRow.communityId(), action, finalSubject);
            if (recorded.size() < requiredApprovals()) {
                return new CommunityApprovalOutcome(CommunityApprovalOutcome.Status.SUCCESS, action,
                        Optional.of(finalSubject), recorded, actor, requiredApprovals());
            }
            boolean roleApplied = false;
            if (action == CommunityGovernanceAction.ROLE_GRANT) {
                roleApplied = applyRoleGrant(connection, actorRow.communityId(), roleTarget, finalRole);
            }
            deleteApprovals(connection, actorRow.communityId(), action, finalSubject);
            publish(new CommunityGovernanceActionEffectiveEvent(actorRow.communityId(), actor, action,
                    finalSubject, roleTarget, finalRole));
            if (!roleApplied) {
                // 双批通过但角色没落地（目标被移出社群 / 目标角色不可任命）。
                // 发一条显式事件让运营人工重放，而不是静默丢弃这次审批结果。
                publish(new CommunityRoleRejectionEvent(actorRow.communityId(), actor, action,
                        finalSubject, roleTarget, finalRole));
            }
            return new CommunityApprovalOutcome(CommunityApprovalOutcome.Status.SUCCESS, action,
                    Optional.of(finalSubject), recorded, actor, requiredApprovals());
        }));
    }

    /**
     * 应用双批通过的角色变更。
     *
     * <p>返回该角色是否真的被写入。不写入的情况：目标不存在、目标属于别的社群、
     * 被任命的角色本身是治理角色或 OWNER。</p>
     */
    private boolean applyRoleGrant(
            Connection connection, UUID communityId, UUID target, CommunityRole role
    ) throws SQLException {
        if (target == null || role == null || role == CommunityRole.OWNER || role.canApprove()) {
            return false;
        }
        MemberRow targetRow = memberRow(connection, target);
        if (targetRow == null || !targetRow.communityId().equals(communityId)) return false;
        return updateRole(connection, communityId, target, role);
    }

    /**
     * 动作的真实前置角色：工程要 BUILDER、活动要 EVENT_HOST；无前置即治理成员即可。
     *
     * <p>无前置时的「治理成员」必须走 {@link CommunityRole#isGovernance()}（经能力表），
     * 不能代入 canApprove()：前者是治理通道准入，后者是投票权，语义不同。</p>
     */
    private static boolean roleQualified(CommunityRole role, CommunityGovernanceAction action) {
        return action.requiredRole().map(required -> role == required)
                .orElse(CommunityRolePower.can(role, CommunityRolePower.Capability.GOVERNANCE_ACTION));
    }

    private static long requiredApprovals() {
        return CommunityRolePower.REQUIRED_APPROVALS;
    }

    private static CommunityApprovalOutcome approval(
            CommunityApprovalOutcome.Status status, CommunityGovernanceAction action
    ) {
        return CommunityApprovalOutcome.of(status, action);
    }

    private static String subjectFor(UUID target) {
        return target == null ? null : target.toString();
    }

    private static String cleanSubject(CommunityGovernanceAction action, String subject) {
        if (action == CommunityGovernanceAction.ROLE_GRANT) {
            return subject == null || subject.isBlank() ? null : subject.strip();
        }
        return action.key();
    }

    private void publish(Event event) {
        if (eventPublisher != null) eventPublisher.accept(event);
    }

    private boolean insertApproval(
            Connection connection,
            UUID approvalId,
            UUID communityId,
            UUID actor,
            CommunityGovernanceAction action,
            String subject,
            UUID requester,
            String idempotencyKey
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_community_approvals
                    (approval_id, community_id, actor_uuid, action, subject, status,
                     requester_uuid, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, approvalId.toString());
            insert.setString(2, communityId.toString());
            insert.setString(3, actor.toString());
            insert.setString(4, action.key());
            insert.setString(5, subject);
            insert.setString(6, "PENDING");
            insert.setString(7, requester == null ? null : requester.toString());
            insert.setString(8, idempotencyKey);
            return insert.executeUpdate() == 1;
        }
    }

    private List<CommunityApprovalOutcome.GrantedApproval> approvals(
            Connection connection, UUID communityId, CommunityGovernanceAction action, String subject
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT a.actor_uuid, m.role
                FROM rpg_community_approvals a
                LEFT JOIN rpg_community_members m ON m.player_uuid = a.actor_uuid
                WHERE a.community_id = ? AND a.action = ? AND a.subject = ? AND a.status = 'PENDING'
                ORDER BY a.created_at ASC, a.actor_uuid ASC
                """)) {
            query.setString(1, communityId.toString());
            query.setString(2, action.key());
            query.setString(3, subject);
            try (ResultSet result = query.executeQuery()) {
                List<CommunityApprovalOutcome.GrantedApproval> approvals = new ArrayList<>();
                while (result.next()) {
                    CommunityRole role = CommunityRole.parse(result.getString("role")).orElse(CommunityRole.MEMBER);
                    approvals.add(new CommunityApprovalOutcome.GrantedApproval(
                            UUID.fromString(result.getString("actor_uuid")), role));
                }
                return List.copyOf(approvals);
            }
        }
    }

    /** 待批准记录里真正记录的目标：{@code action|subject} 中的 subject 部分。 */
    private Optional<String> pendingSubject(
            Connection connection, UUID communityId, CommunityGovernanceAction action
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT subject FROM rpg_community_approvals
                WHERE community_id = ? AND action = ? AND status = 'PENDING'
                ORDER BY created_at ASC LIMIT 1
                """)) {
            query.setString(1, communityId.toString());
            query.setString(2, action.key());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.ofNullable(result.getString("subject"));
            }
        }
    }

    private UUID requesterOf(
            Connection connection, UUID communityId, CommunityGovernanceAction action, String subject
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT requester_uuid FROM rpg_community_approvals
                WHERE community_id = ? AND action = ? AND subject = ? AND status = 'PENDING'
                ORDER BY created_at ASC LIMIT 1
                """)) {
            query.setString(1, communityId.toString());
            query.setString(2, action.key());
            query.setString(3, subject);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                String requester = result.getString("requester_uuid");
                if (requester == null) return null;
                try {
                    return UUID.fromString(requester);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
    }

    private int deleteApprovals(
            Connection connection, UUID communityId, CommunityGovernanceAction action, String subject
    ) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM rpg_community_approvals
                WHERE community_id = ? AND action = ? AND subject = ? AND status = 'PENDING'
                """)) {
            delete.setString(1, communityId.toString());
            delete.setString(2, action.key());
            delete.setString(3, subject);
            return delete.executeUpdate();
        }
    }

    private List<CommunitySummary> browse(
            Connection connection, String periodKey, BigDecimal goal, int limit
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT c.id, c.name, c.tag, c.capacity,
                       COUNT(m.player_uuid) AS member_count,
                       COALESCE(p.gold_contributed, 0) AS period_gold,
                       COALESCE(p.verified_recruits, 0) AS verified_recruits,
                       p.completed_at
                FROM rpg_communities c
                JOIN rpg_community_members m ON m.community_id = c.id
                LEFT JOIN rpg_community_projects p
                    ON p.community_id = c.id AND p.period_key = ?
                GROUP BY c.id, c.name, c.tag, c.capacity,
                         p.gold_contributed, p.verified_recruits, p.completed_at
                ORDER BY CASE WHEN p.completed_at IS NULL THEN 0 ELSE 1 END DESC,
                         COALESCE(p.verified_recruits, 0) DESC,
                         COALESCE(p.gold_contributed, 0) DESC,
                         COUNT(m.player_uuid) DESC,
                         c.created_at ASC
                LIMIT ?
                """)) {
            query.setString(1, periodKey);
            query.setInt(2, limit);
            try (ResultSet result = query.executeQuery()) {
                List<CommunitySummary> summaries = new ArrayList<>();
                while (result.next()) summaries.add(readSummary(result, goal));
                return List.copyOf(summaries);
            }
        }
    }

    private Optional<CommunityMembership> membership(
            Connection connection, UUID playerId, String periodKey, BigDecimal goal
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT c.id, c.name, c.tag, c.capacity, m.role,
                       (SELECT COUNT(*) FROM rpg_community_members members
                        WHERE members.community_id = c.id) AS member_count,
                       COALESCE(p.gold_contributed, 0) AS period_gold,
                       COALESCE(p.verified_recruits, 0) AS verified_recruits,
                       p.completed_at,
                       COALESCE((SELECT SUM(contribution.amount) FROM rpg_community_contributions contribution
                                 WHERE contribution.player_uuid = ?
                                   AND contribution.community_id = c.id
                                   AND contribution.period_key = ?), 0) AS player_period_gold,
                       COALESCE((SELECT SUM(contribution.amount) FROM rpg_community_contributions contribution
                                 WHERE contribution.player_uuid = ?
                                   AND contribution.period_key = ?), 0) AS player_period_cap_gold,
                       COALESCE((SELECT SUM(contribution.amount) FROM rpg_community_contributions contribution
                                 WHERE contribution.player_uuid = ?), 0) AS player_lifetime_gold
                FROM rpg_community_members m
                JOIN rpg_communities c ON c.id = m.community_id
                LEFT JOIN rpg_community_projects p
                    ON p.community_id = c.id AND p.period_key = ?
                WHERE m.player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, periodKey);
            query.setString(3, playerId.toString());
            query.setString(4, periodKey);
            query.setString(5, playerId.toString());
            query.setString(6, periodKey);
            query.setString(7, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return Optional.empty();
                CommunitySummary summary = readSummary(result, goal);
                return Optional.of(new CommunityMembership(summary,
                        CommunityRole.valueOf(result.getString("role")),
                        saturatedContribution(result.getBigDecimal("player_lifetime_gold")),
                        saturatedContribution(result.getBigDecimal("player_period_gold")),
                        saturatedContribution(result.getBigDecimal("player_period_cap_gold"))));
            }
        }
    }

    private Optional<CommunitySummary> summary(
            Connection connection, UUID communityId, String periodKey, BigDecimal goal
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT c.id, c.name, c.tag, c.capacity,
                       COUNT(m.player_uuid) AS member_count,
                       COALESCE(p.gold_contributed, 0) AS period_gold,
                       COALESCE(p.verified_recruits, 0) AS verified_recruits,
                       p.completed_at
                FROM rpg_communities c
                JOIN rpg_community_members m ON m.community_id = c.id
                LEFT JOIN rpg_community_projects p
                    ON p.community_id = c.id AND p.period_key = ?
                WHERE c.id = ?
                GROUP BY c.id, c.name, c.tag, c.capacity,
                         p.gold_contributed, p.verified_recruits, p.completed_at
                """)) {
            query.setString(1, periodKey);
            query.setString(2, communityId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Optional.of(readSummary(result, goal)) : Optional.empty();
            }
        }
    }

    private static CommunitySummary readSummary(ResultSet result, BigDecimal goal) throws SQLException {
        BigDecimal contribution = money(result.getBigDecimal("period_gold"));
        return new CommunitySummary(UUID.fromString(result.getString("id")), result.getString("name"),
                result.getString("tag"), result.getInt("member_count"), result.getInt("capacity"),
                contribution, goal, result.getInt("verified_recruits"),
                result.getTimestamp("completed_at") != null || contribution.compareTo(goal) >= 0);
    }

    private Identity ensureIdentity(Connection connection, UUID playerId) throws SQLException {
        Identity identity = identity(connection, playerId);
        if (identity != null) return identity;
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 10).toUpperCase(Locale.ROOT);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_community_identity
                        (player_uuid, invite_code, mentor_verified, verified_mentees)
                    VALUES (?, ?, FALSE, 0) ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setString(2, code);
                insert.executeUpdate();
            }
            identity = identity(connection, playerId);
            if (identity != null) return identity;
        }
        throw new IllegalStateException("Could not allocate a unique community invite code");
    }

    private Identity identity(Connection connection, UUID playerId) throws SQLException {
        if (playerId == null) return null;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, invite_code, mentor_uuid, mentor_verified, verified_mentees,
                       community_honor, weekly_commemorations
                FROM rpg_community_identity WHERE player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? readIdentity(result) : null;
            }
        }
    }

    private Identity lockIdentity(Connection connection, UUID playerId) throws SQLException {
        ensureIdentity(connection, playerId);
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, invite_code, mentor_uuid, mentor_verified, verified_mentees,
                       community_honor, weekly_commemorations
                FROM rpg_community_identity WHERE player_uuid = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Community identity disappeared");
                return readIdentity(result);
            }
        }
    }

    private Identity identityByCode(Connection connection, String code) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, invite_code, mentor_uuid, mentor_verified, verified_mentees,
                       community_honor, weekly_commemorations
                FROM rpg_community_identity WHERE invite_code = ?
                """)) {
            query.setString(1, code);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? readIdentity(result) : null;
            }
        }
    }

    private static Identity readIdentity(ResultSet result) throws SQLException {
        String mentor = result.getString("mentor_uuid");
        return new Identity(UUID.fromString(result.getString("player_uuid")), result.getString("invite_code"),
                mentor == null ? null : UUID.fromString(mentor), result.getBoolean("mentor_verified"),
                result.getInt("verified_mentees"), result.getLong("community_honor"),
                result.getInt("weekly_commemorations"));
    }

    private int mentorScore(Connection connection, UUID mentorId) throws SQLException {
        Identity identity = identity(connection, mentorId);
        return identity == null ? 0 : identity.verifiedMentees();
    }

    private int effectiveLevel(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT ascension, level FROM rpg_profiles WHERE player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt("ascension") * 30 + result.getInt("level") : 0;
            }
        }
    }

    private UUID membershipCommunity(Connection connection, UUID playerId) throws SQLException {
        MemberRow row = memberRow(connection, playerId);
        return row == null ? null : row.communityId();
    }

    private MemberRow memberRow(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT community_id, role
                FROM rpg_community_members WHERE player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new MemberRow(UUID.fromString(result.getString("community_id")),
                        playerId, CommunityRole.valueOf(result.getString("role"))) : null;
            }
        }
    }

    private MemberRow lockMemberRow(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT community_id, role FROM rpg_community_members
                WHERE player_uuid = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new MemberRow(UUID.fromString(result.getString("community_id")),
                        playerId, CommunityRole.valueOf(result.getString("role"))) : null;
            }
        }
    }

    private boolean insertMember(
            Connection connection, UUID communityId, UUID playerId, CommunityRole role
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_community_members
                    (community_id, player_uuid, role)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, communityId.toString());
            insert.setString(2, playerId.toString());
            insert.setString(3, role.name());
            return insert.executeUpdate() == 1;
        }
    }

    private void deleteMember(Connection connection, UUID communityId, UUID playerId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM rpg_community_members WHERE community_id = ? AND player_uuid = ?
                """)) {
            delete.setString(1, communityId.toString());
            delete.setString(2, playerId.toString());
            delete.executeUpdate();
        }
    }

    /** 返回角色是否真的被改写，便于审批路径区分「已生效」与「前置不满足」。 */
    private boolean updateRole(
            Connection connection, UUID communityId, UUID playerId, CommunityRole role
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_community_members SET role = ? WHERE community_id = ? AND player_uuid = ?
                """)) {
            update.setString(1, role.name());
            update.setString(2, communityId.toString());
            update.setString(3, playerId.toString());
            return update.executeUpdate() > 0;
        }
    }

    private int insertCommunity(
            Connection connection, UUID id, String name, String tag, UUID owner, int capacity
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_communities
                    (id, name, normalized_name, tag, normalized_tag, owner_uuid, capacity)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, id.toString());
            insert.setString(2, name);
            insert.setString(3, normalize(name));
            insert.setString(4, tag);
            insert.setString(5, normalize(tag));
            insert.setString(6, owner.toString());
            insert.setInt(7, capacity);
            return insert.executeUpdate();
        }
    }

    private boolean communityNameExists(Connection connection, String normalized) throws SQLException {
        return exists(connection, "SELECT 1 FROM rpg_communities WHERE normalized_name = ?", normalized);
    }

    private boolean communityTagExists(Connection connection, String normalized) throws SQLException {
        return exists(connection, "SELECT 1 FROM rpg_communities WHERE normalized_tag = ?", normalized);
    }

    private boolean exists(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, value);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private CommunityLock lockCommunityByTag(Connection connection, String normalizedTag) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id, capacity FROM rpg_communities WHERE normalized_tag = ? FOR UPDATE
                """)) {
            query.setString(1, normalizedTag);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new CommunityLock(
                        UUID.fromString(result.getString("id")), result.getInt("capacity")) : null;
            }
        }
    }

    private int memberCount(Connection connection, UUID communityId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_community_members WHERE community_id = ?
                """)) {
            query.setString(1, communityId.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private int pendingInvites(Connection connection, UUID inviter) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_community_invites
                WHERE inviter_uuid = ? AND expires_at > CURRENT_TIMESTAMP
                """)) {
            query.setString(1, inviter.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private boolean validInvite(Connection connection, UUID communityId, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM rpg_community_invites
                WHERE community_id = ? AND target_uuid = ? AND expires_at > CURRENT_TIMESTAMP
                """)) {
            query.setString(1, communityId.toString());
            query.setString(2, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private void expireInvites(Connection connection) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM rpg_community_invites WHERE expires_at <= CURRENT_TIMESTAMP
                """)) {
            delete.executeUpdate();
        }
    }

    private void deleteInvite(Connection connection, UUID communityId, UUID target) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM rpg_community_invites WHERE community_id = ? AND target_uuid = ?
                """)) {
            delete.setString(1, communityId.toString());
            delete.setString(2, target.toString());
            delete.executeUpdate();
        }
    }

    private void deleteAllInvites(Connection connection, UUID target) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM rpg_community_invites WHERE target_uuid = ?
                """)) {
            delete.setString(1, target.toString());
            delete.executeUpdate();
        }
    }

    private void ensureProject(Connection connection, UUID communityId, String periodKey) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_community_projects
                    (community_id, period_key, gold_contributed, verified_recruits)
                VALUES (?, ?, 0, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, communityId.toString());
            insert.setString(2, periodKey);
            insert.executeUpdate();
        }
    }

    private ProjectRow lockProject(Connection connection, UUID communityId, String periodKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT gold_contributed, verified_recruits, completed_at FROM rpg_community_projects
                WHERE community_id = ? AND period_key = ? FOR UPDATE
                """)) {
            query.setString(1, communityId.toString());
            query.setString(2, periodKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Community project disappeared");
                return new ProjectRow(money(result.getBigDecimal("gold_contributed")),
                        result.getInt("verified_recruits"),
                        result.getTimestamp("completed_at"));
            }
        }
    }

    private BigDecimal projectTotal(Connection connection, UUID communityId, String periodKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT gold_contributed FROM rpg_community_projects
                WHERE community_id = ? AND period_key = ?
                """)) {
            query.setString(1, communityId.toString());
            query.setString(2, periodKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? money(result.getBigDecimal(1)) : ZERO;
            }
        }
    }

    private BigDecimal playerContribution(Connection connection, UUID playerId, String periodKey)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount), 0) FROM rpg_community_contributions
                WHERE player_uuid = ? AND period_key = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, periodKey);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return money(result.getBigDecimal(1));
            }
        }
    }

    private BigDecimal lifetimeContribution(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount), 0) FROM rpg_community_contributions
                WHERE player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return money(result.getBigDecimal(1));
            }
        }
    }

    private BigDecimal playerContribution(
            Connection connection, UUID playerId, UUID communityId, String periodKey
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount), 0) FROM rpg_community_contributions
                WHERE player_uuid = ? AND community_id = ? AND period_key = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, communityId.toString());
            query.setString(3, periodKey);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return money(result.getBigDecimal(1));
            }
        }
    }

    private boolean rewardClaimed(Connection connection, UUID playerId, String periodKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM rpg_community_project_rewards
                WHERE player_uuid = ? AND period_key = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, periodKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private ProjectRewardReceipt projectRewardReceipt(
            Connection connection, UUID playerId, String periodKey
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT honor_awarded, player_contribution, minimum_contribution
                FROM rpg_community_project_rewards
                WHERE player_uuid = ? AND period_key = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, periodKey);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new ProjectRewardReceipt(result.getInt("honor_awarded"),
                        money(result.getBigDecimal("player_contribution")),
                        money(result.getBigDecimal("minimum_contribution"))) : null;
            }
        }
    }

    private void insertProjectRewardReceipt(
            Connection connection,
            UUID playerId,
            UUID communityId,
            String periodKey,
            BigDecimal playerContribution,
            BigDecimal minimumContribution,
            int honorAward
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_community_project_rewards
                    (player_uuid, period_key, community_id, player_contribution,
                     minimum_contribution, honor_awarded)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, periodKey);
            insert.setString(3, communityId.toString());
            insert.setBigDecimal(4, playerContribution);
            insert.setBigDecimal(5, minimumContribution);
            insert.setInt(6, honorAward);
            insert.executeUpdate();
        }
    }

    private ExistingContribution existingContribution(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT community_id, player_uuid, period_key, amount
                FROM rpg_community_contributions WHERE idempotency_key = ?
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new ExistingContribution(
                        UUID.fromString(result.getString("community_id")),
                        UUID.fromString(result.getString("player_uuid")),
                        result.getString("period_key"), money(result.getBigDecimal("amount"))) : null;
            }
        }
    }

    private void insertContribution(
            Connection connection,
            String key,
            UUID communityId,
            UUID playerId,
            String periodKey,
            BigDecimal amount
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_community_contributions
                    (id, idempotency_key, community_id, player_uuid, period_key, amount)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, communityId.toString());
            insert.setString(4, playerId.toString());
            insert.setString(5, periodKey);
            insert.setBigDecimal(6, amount);
            insert.executeUpdate();
        }
    }

    private BigDecimal loadOrCreateAccount(Connection connection, UUID playerId, BigDecimal startingBalance)
            throws SQLException {
        BigDecimal balance = selectBalance(connection, playerId, false);
        if (balance != null) return balance;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
        return selectBalance(connection, playerId, false);
    }

    private BigDecimal lockBalance(Connection connection, UUID playerId) throws SQLException {
        return selectBalance(connection, playerId, true);
    }

    private BigDecimal selectBalance(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT balance FROM rpg_accounts WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? money(result.getBigDecimal(1)) : null;
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?
                """)) {
            update.setBigDecimal(1, balance);
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
    }

    private boolean ledgerExists(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_ledger WHERE idempotency_key = ?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private void insertGoldLedger(
            Connection connection, String key, UUID sender, BigDecimal amount, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger
                    (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, NULL, ?, 0, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, sender.toString());
            insert.setBigDecimal(4, amount);
            insert.setString(5, reason);
            insert.executeUpdate();
        }
    }

    private static CommunityOperationResult invalidOperation() {
        return status(CommunityOperationResult.Status.INVALID);
    }

    private static CommunityOperationResult status(CommunityOperationResult.Status status) {
        return operation(status, Optional.empty(), ZERO);
    }

    private static CommunityOperationResult operation(
            CommunityOperationResult.Status status,
            Optional<CommunitySummary> summary,
            BigDecimal balance
    ) {
        return new CommunityOperationResult(status, summary, money(balance));
    }

    private static MentorBondResult mentor(
            MentorBondResult.Status status,
            UUID mentorId,
            UUID communityId,
            boolean verified,
            int score
    ) {
        return new MentorBondResult(status, Optional.ofNullable(mentorId),
                Optional.ofNullable(communityId), verified, score);
    }

    private static CommunityContributionResult contribution(
            CommunityContributionResult.Status status,
            BigDecimal amount,
            BigDecimal balance,
            BigDecimal playerTotal,
            BigDecimal communityTotal,
            BigDecimal goal,
            boolean newlyCompleted
    ) {
        return new CommunityContributionResult(status, money(amount), money(balance), money(playerTotal),
                money(communityTotal), money(goal), newlyCompleted);
    }

    private static CommunityProjectRewardResult projectReward(
            CommunityProjectRewardResult.Status status,
            String periodKey,
            int honorAwarded,
            long totalHonor,
            int weeklyCommemorations,
            BigDecimal playerContribution,
            BigDecimal minimumContribution
    ) {
        return new CommunityProjectRewardResult(status, periodKey, honorAwarded, totalHonor,
                weeklyCommemorations, money(playerContribution), money(minimumContribution));
    }

    private static BigDecimal safeMoney(BigDecimal value) {
        return value == null || value.signum() < 0 ? ZERO : money(value);
    }

    private static String cleanName(String input) {
        if (input == null) return null;
        String cleaned = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ");
        return NAME.matcher(cleaned).matches() ? cleaned : null;
    }

    private static String cleanTag(String input) {
        if (input == null) return null;
        String cleaned = Normalizer.normalize(input, Normalizer.Form.NFKC).strip();
        return TAG.matcher(cleaned).matches() ? cleaned : null;
    }

    private static String normalize(String input) {
        return Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean validPeriod(String value) {
        return value != null && PERIOD.matcher(value).matches();
    }

    private static boolean validKey(String value) {
        return value != null && KEY.matcher(value).matches();
    }

    private static boolean positiveMoney(BigDecimal value) {
        if (value == null) return false;
        BigDecimal normalized = money(value);
        return value.signum() > 0 && normalized.signum() > 0 && normalized.compareTo(MAX_GOLD) <= 0;
    }

    private static boolean nonNegativeMoney(BigDecimal value) {
        if (value == null) return false;
        BigDecimal normalized = money(value);
        return value.signum() >= 0 && normalized.signum() >= 0 && normalized.compareTo(MAX_GOLD) <= 0;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Contribution history is append-only and can contain legacy data above today's public
     * progression ceiling. The ledger remains exact; API and menu projections saturate at the
     * supported maximum. Write-side checks deliberately use the unsaturated aggregate.
     */
    private static BigDecimal saturatedContribution(BigDecimal value) {
        BigDecimal normalized = money(value);
        if (normalized.signum() < 0) return ZERO;
        return normalized.min(MAX_GOLD);
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Community transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open community transaction", exception);
        }
    }

    private record Identity(
            UUID playerId,
            String inviteCode,
            UUID mentorId,
            boolean mentorVerified,
            int verifiedMentees,
            long communityHonor,
            int weeklyCommemorations
    ) {
    }

    private record MemberRow(UUID communityId, UUID playerId, CommunityRole role) {
    }

    private record CommunityLock(UUID id, int capacity) {
    }

    private record ProjectRow(BigDecimal gold, int verifiedRecruits, Timestamp completedAt) {
    }

    private record ExistingContribution(
            UUID communityId, UUID playerId, String periodKey, BigDecimal amount) {
    }

    private record ProjectRewardReceipt(
            int honorAwarded, BigDecimal playerContribution, BigDecimal minimumContribution) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
