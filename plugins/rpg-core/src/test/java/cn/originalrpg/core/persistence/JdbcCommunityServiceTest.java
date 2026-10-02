package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommunityContributionResult;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityProjectRewardResult;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.MentorBondResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcCommunityServiceTest {
    private static final String PERIOD = "2026-W32";
    private static final BigDecimal GOAL = new BigDecimal("250000.00");

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private JdbcCommunityService communities;

    @BeforeEach
    void setUp() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:rpg_community_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        config.setAutoCommit(false);
        config.setMaximumPoolSize(32);
        dataSource = new HikariDataSource(config);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        communities = new JdbcCommunityService(dataSource, executor);

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
                        milestone_key VARCHAR(160) PRIMARY KEY,
                        receipt VARCHAR(96) NOT NULL,
                        player_uuid VARCHAR(36) NOT NULL,
                        points INTEGER NOT NULL,
                        ascension INTEGER NOT NULL,
                        milestone_index INTEGER NOT NULL,
                        to_local_level INTEGER NOT NULL,
                        granted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
                    CREATE TABLE rpg_progression_wallets (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        merit BIGINT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE rpg_commerce_accounts (
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        lifetime_paid DECIMAL(19, 2) NOT NULL DEFAULT 0,
                        credits BIGINT NOT NULL DEFAULT 0,
                        member_until TIMESTAMP NULL,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
                        role VARCHAR(12) NOT NULL, lifetime_contribution DECIMAL(19, 2) NOT NULL DEFAULT 0,
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
            connection.commit();
        }
    }

    @AfterEach
    void tearDown() {
        executor.close();
        dataSource.close();
    }

    @Test
    void creationDebitIsIdempotentAndChargedExactlyOnce() throws Exception {
        UUID owner = player(15);

        var created = create(owner, "Seed", "SEED", 8, "create-seed");
        var replay = create(owner, "Seed", "SEED", 8, "create-seed");
        var differentRequest = create(owner, "Other", "OTHR", 8, "create-other");

        assertEquals(CommunityOperationResult.Status.SUCCESS, created.status());
        assertEquals(CommunityOperationResult.Status.DUPLICATE, replay.status());
        assertEquals(CommunityOperationResult.Status.ALREADY_MEMBER, differentRequest.status());
        assertEquals(new BigDecimal("15000.00"), balance(owner));
        assertEquals(1, count("rpg_communities"));
        assertEquals(1, count("rpg_community_members"));
        assertEquals(1, count("rpg_ledger"));
    }

    @Test
    void negativeSubCentInputsCannotBecomeFreeCommunityOperations() {
        UUID owner = player(15);
        BigDecimal negativeSubCent = new BigDecimal("-0.001");

        var create = communities.create(owner, "Unsafe", "BAD", 8, 15,
                negativeSubCent, new BigDecimal("25000.00"), "negative-create")
                .toCompletableFuture().join();
        var contribution = communities.contributeGold(owner, new BigDecimal("100.00"),
                new BigDecimal("1000.00"), GOAL, negativeSubCent, PERIOD, "negative-seed")
                .toCompletableFuture().join();

        assertEquals(CommunityOperationResult.Status.INVALID, create.status());
        assertEquals(CommunityContributionResult.Status.INVALID, contribution.status());
    }

    @Test
    void concurrentCapacityAndContributionCapsCannotBeExceeded() throws Exception {
        UUID owner = player(15);
        insertAccount(owner, new BigDecimal("50000.00"));
        create(owner, "Harbor", "HBR", 2, "create-harbor");
        List<UUID> candidates = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            UUID candidate = player(1);
            candidates.add(candidate);
            assertEquals(CommunityOperationResult.Status.SUCCESS,
                    communities.invite(owner, candidate, 20, 24, PERIOD, GOAL).toCompletableFuture().join().status());
        }

        List<CompletableFuture<CommunityOperationResult>> accepts = candidates.stream()
                .map(candidate -> communities.accept(candidate, "HBR", PERIOD, GOAL).toCompletableFuture())
                .toList();
        CompletableFuture.allOf(accepts.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, accepts.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommunityOperationResult.Status.SUCCESS).count());
        assertEquals(2, count("rpg_community_members"));

        List<CompletableFuture<CommunityContributionResult>> contributions = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            contributions.add(communities.contributeGold(owner, new BigDecimal("10000.00"),
                    new BigDecimal("30000.00"), GOAL, BigDecimal.ZERO, PERIOD,
                    "cap-" + index).toCompletableFuture());
        }
        CompletableFuture.allOf(contributions.toArray(CompletableFuture[]::new)).join();
        assertEquals(3, contributions.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommunityContributionResult.Status.SUCCESS).count());
        assertEquals(new BigDecimal("30000.00"), decimal(
                "SELECT gold_contributed FROM rpg_community_projects WHERE period_key = ?", PERIOD));
        assertEquals(new BigDecimal("10000.00"), balance(owner));
    }

    @Test
    void contributionReplayAndMentorVerificationAreCountedOnce() throws Exception {
        UUID mentor = player(15);
        create(mentor, "Lantern", "LAMP", 8, "create-lantern");
        UUID mentee = player(1);
        String inviteCode = communities.profile(mentor, PERIOD, GOAL).toCompletableFuture().join().inviteCode();
        assertEquals(MentorBondResult.Status.SUCCESS,
                communities.bindMentor(mentee, inviteCode, 1, 20, 10, 360, 15)
                        .toCompletableFuture().join().status());

        List<CompletableFuture<MentorBondResult>> verifies = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            verifies.add(communities.verifyMentorBond(mentee, true, 15, 180, 15, 180, PERIOD)
                    .toCompletableFuture());
        }
        CompletableFuture.allOf(verifies.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, verifies.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == MentorBondResult.Status.SUCCESS).count());
        assertTrue(communities.profile(mentee, PERIOD, GOAL).toCompletableFuture().join().mentorVerified());
        assertEquals(1, communities.profile(mentor, PERIOD, GOAL)
                .toCompletableFuture().join().verifiedMentees());
        assertEquals(1, integer("SELECT verified_recruits FROM rpg_community_projects WHERE period_key = ?", PERIOD));

        var first = communities.contributeGold(mentor, new BigDecimal("5000.00"),
                new BigDecimal("50000.00"), GOAL, BigDecimal.ZERO, PERIOD, "same-contribution")
                .toCompletableFuture().join();
        var replay = communities.contributeGold(mentor, new BigDecimal("5000.00"),
                new BigDecimal("50000.00"), GOAL, BigDecimal.ZERO, PERIOD, "same-contribution")
                .toCompletableFuture().join();
        assertEquals(CommunityContributionResult.Status.SUCCESS, first.status());
        assertEquals(CommunityContributionResult.Status.DUPLICATE, replay.status());
        assertEquals(new BigDecimal("5000.00"), replay.communityPeriodContribution());
        assertEquals(new BigDecimal("10000.00"), balance(mentor));
    }

    @Test
    void expiredInvitesAndRoleTransitionsAreEnforced() throws Exception {
        UUID owner = player(15);
        create(owner, "Wayfarers", "WAY", 6, "create-way");
        UUID successor = join(owner, "WAY");
        UUID member = join(owner, "WAY");
        UUID expired = player(1);
        communities.invite(owner, expired, 20, 24, PERIOD, GOAL).toCompletableFuture().join();
        expireInvite(expired);

        assertEquals(CommunityOperationResult.Status.INVITE_MISSING,
                communities.accept(expired, "WAY", PERIOD, GOAL).toCompletableFuture().join().status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.transfer(owner, successor, PERIOD, GOAL).toCompletableFuture().join().status());
        assertEquals(CommunityRole.OFFICER, communities.profile(owner, PERIOD, GOAL)
                .toCompletableFuture().join().membership().orElseThrow().role());
        assertEquals(CommunityRole.OWNER, communities.profile(successor, PERIOD, GOAL)
                .toCompletableFuture().join().membership().orElseThrow().role());
        assertEquals(CommunityOperationResult.Status.NOT_ALLOWED,
                communities.kick(member, successor, PERIOD, GOAL).toCompletableFuture().join().status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.kick(owner, member, PERIOD, GOAL).toCompletableFuture().join().status());
    }

    @Test
    void failedCreationRollsBackIdentityCommunityMemberDebitAndLedger() throws Exception {
        UUID owner = player(15);
        insertAccount(owner, new BigDecimal("25000.00"));
        execute("ALTER TABLE rpg_ledger ADD CONSTRAINT reject_community_creation "
                + "CHECK (reason <> 'community-creation')");

        assertThrows(CompletionException.class,
                () -> create(owner, "Rollback", "ROLL", 8, "create-rollback"));

        assertEquals(new BigDecimal("25000.00"), balance(owner));
        assertEquals(0, count("rpg_community_identity"));
        assertEquals(0, count("rpg_communities"));
        assertEquals(0, count("rpg_community_members"));
        assertEquals(0, count("rpg_ledger"));
    }

    @Test
    void completedProjectRewardIsDurableConcurrentAndNeverMintsCurrencyOrPower() throws Exception {
        UUID owner = player(15);
        insertAccount(owner, new BigDecimal("25000.00"));
        create(owner, "Builders", "BLD", 8, "create-builders");
        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(owner, new BigDecimal("1000.00"),
                                new BigDecimal("50000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO,
                                PERIOD, "build-complete")
                        .toCompletableFuture().join().status());
        BigDecimal balanceBeforeClaims = balance(owner);
        int ledgerBeforeClaims = count("rpg_ledger");
        PowerSnapshot powerBeforeClaims = power(owner);
        execute("INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES ('"
                + owner + "', 17)");
        execute("INSERT INTO rpg_commerce_accounts (player_uuid, lifetime_paid, credits) VALUES ('"
                + owner + "', 0, 23)");

        List<CompletableFuture<CommunityProjectRewardResult>> claims = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            claims.add(communities.claimWeeklyProjectReward(owner, PERIOD,
                    new BigDecimal("1000.00"), new BigDecimal("1000.00"), 100).toCompletableFuture());
        }
        CompletableFuture.allOf(claims.toArray(CompletableFuture[]::new)).join();

        assertEquals(1, claims.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommunityProjectRewardResult.Status.SUCCESS).count());
        assertEquals(19, claims.stream().map(CompletableFuture::join)
                .filter(result -> result.status() == CommunityProjectRewardResult.Status.DUPLICATE).count());
        var profile = communities.profile(owner, PERIOD, new BigDecimal("1000.00"))
                .toCompletableFuture().join();
        assertEquals(100, profile.communityHonor());
        assertEquals(1, profile.weeklyCommemorations());
        assertTrue(profile.weeklyRewardClaimed());
        assertEquals(1, count("rpg_community_project_rewards"));
        assertEquals(balanceBeforeClaims, balance(owner));
        assertEquals(ledgerBeforeClaims, count("rpg_ledger"));
        assertEquals(powerBeforeClaims, power(owner));
        assertEquals(17, integer(
                "SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ?", owner.toString()));
        assertEquals(23, integer(
                "SELECT credits FROM rpg_commerce_accounts WHERE player_uuid = ?", owner.toString()));
    }

    @Test
    void rewardRequiresCompletedProjectAndMinimumPersonalWork() {
        UUID owner = player(15);
        try {
            insertAccount(owner, new BigDecimal("25000.00"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        create(owner, "Wardens", "WRD", 8, "create-wardens");

        var incomplete = communities.claimWeeklyProjectReward(owner, PERIOD,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), 100).toCompletableFuture().join();
        assertEquals(CommunityProjectRewardResult.Status.PROJECT_INCOMPLETE, incomplete.status());

        UUID member = join(owner, "WRD");
        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(owner, new BigDecimal("500.00"),
                                new BigDecimal("50000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO,
                                PERIOD, "owner-half")
                        .toCompletableFuture().join().status());
        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(member, new BigDecimal("500.00"),
                                new BigDecimal("50000.00"), new BigDecimal("1000.00"),
                                new BigDecimal("25000.00"), PERIOD, "member-half")
                        .toCompletableFuture().join().status());

        var underMinimum = communities.claimWeeklyProjectReward(owner, PERIOD,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), 100).toCompletableFuture().join();
        assertEquals(CommunityProjectRewardResult.Status.CONTRIBUTION_REQUIRED, underMinimum.status());
        assertEquals(new BigDecimal("500.00"), underMinimum.playerPeriodContribution());
        assertEquals(0, communities.profile(owner, PERIOD, new BigDecimal("1000.00"))
                .toCompletableFuture().join().communityHonor());
    }

    @Test
    void leavingBeforeClaimRemovesEligibilityWithoutErasingContributionHistory() throws Exception {
        UUID owner = player(15);
        insertAccount(owner, new BigDecimal("25000.00"));
        create(owner, "Harborlight", "HRB", 8, "create-harborlight");
        UUID member = join(owner, "HRB");
        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(member, new BigDecimal("1000.00"),
                                new BigDecimal("50000.00"), new BigDecimal("1000.00"),
                                new BigDecimal("25000.00"), PERIOD, "member-build")
                        .toCompletableFuture().join().status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.leave(member, PERIOD, new BigDecimal("1000.00")).toCompletableFuture().join().status());

        var result = communities.claimWeeklyProjectReward(member, PERIOD,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), 100).toCompletableFuture().join();
        assertEquals(CommunityProjectRewardResult.Status.NOT_MEMBER, result.status());
        assertEquals(0, count("rpg_community_project_rewards"));
        assertEquals(new BigDecimal("1000.00"), decimal(
                "SELECT amount FROM rpg_community_contributions WHERE player_uuid = ?", member.toString()));
    }

    @Test
    void contributionFromPreviousCommunityCannotQualifyAClaimAfterSwitching() throws Exception {
        UUID firstOwner = player(15);
        UUID secondOwner = player(15);
        insertAccount(firstOwner, new BigDecimal("25000.00"));
        insertAccount(secondOwner, new BigDecimal("25000.00"));
        create(firstOwner, "First Hearth", "ONE", 8, "create-first-hearth");
        create(secondOwner, "Second Hearth", "TWO", 8, "create-second-hearth");
        UUID traveler = join(firstOwner, "ONE");

        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(traveler, new BigDecimal("1000.00"),
                                new BigDecimal("50000.00"), new BigDecimal("1000.00"),
                                new BigDecimal("25000.00"), PERIOD, "traveler-first-build")
                        .toCompletableFuture().join().status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.leave(traveler, PERIOD, new BigDecimal("1000.00"))
                        .toCompletableFuture().join().status());

        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(secondOwner, new BigDecimal("1000.00"),
                                new BigDecimal("50000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO,
                                PERIOD, "second-owner-build")
                        .toCompletableFuture().join().status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.invite(secondOwner, traveler, 20, 24, PERIOD, new BigDecimal("1000.00"))
                        .toCompletableFuture().join().status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.accept(traveler, "TWO", PERIOD, new BigDecimal("1000.00"))
                        .toCompletableFuture().join().status());

        BigDecimal balanceBeforeCapRejection = balance(traveler);
        int contributionsBeforeCapRejection = count("rpg_community_contributions");
        var capRejected = communities.contributeGold(traveler, new BigDecimal("1.00"),
                        new BigDecimal("1000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO,
                        PERIOD, "traveler-switch-cap")
                .toCompletableFuture().join();
        assertEquals(CommunityContributionResult.Status.PLAYER_LIMIT_REACHED, capRejected.status());
        assertEquals(balanceBeforeCapRejection, balance(traveler));
        assertEquals(contributionsBeforeCapRejection, count("rpg_community_contributions"));

        var result = communities.claimWeeklyProjectReward(traveler, PERIOD,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), 100).toCompletableFuture().join();
        assertEquals(CommunityProjectRewardResult.Status.CONTRIBUTION_REQUIRED, result.status());
        assertEquals(new BigDecimal("0.00"), result.playerPeriodContribution());
        var switchedProfile = communities.profile(traveler, PERIOD, new BigDecimal("1000.00"))
                .toCompletableFuture().join().membership().orElseThrow();
        assertEquals(new BigDecimal("0.00"), switchedProfile.periodContribution());
        assertEquals(new BigDecimal("1000.00"), switchedProfile.periodCapContribution());
        assertEquals(new BigDecimal("1000.00"), switchedProfile.lifetimeContribution());
        assertEquals(0, count("rpg_community_project_rewards"));
    }

    @Test
    void communityRewardProgressLimitDoesNotClaimThePeriodAndCanBeRetried() throws Exception {
        UUID owner = player(15);
        insertAccount(owner, new BigDecimal("25000.00"));
        create(owner, "Long Memory", "MEM", 8, "create-long-memory");
        communities.contributeGold(owner, new BigDecimal("1000.00"),
                        new BigDecimal("50000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO,
                        PERIOD, "complete-long-memory")
                .toCompletableFuture().join();
        execute("UPDATE rpg_community_identity SET community_honor = 1000000000000 "
                + "WHERE player_uuid = '" + owner + "'");

        CommunityProjectRewardResult rejected = communities.claimWeeklyProjectReward(owner, PERIOD,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), 100).toCompletableFuture().join();
        assertEquals(CommunityProjectRewardResult.Status.PROGRESS_LIMIT, rejected.status());
        assertEquals(0, count("rpg_community_project_rewards"));
        assertTrue(!communities.profile(owner, PERIOD, new BigDecimal("1000.00"))
                .toCompletableFuture().join().weeklyRewardClaimed());

        execute("UPDATE rpg_community_identity SET community_honor = 999999999900 "
                + "WHERE player_uuid = '" + owner + "'");
        CommunityProjectRewardResult retried = communities.claimWeeklyProjectReward(owner, PERIOD,
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), 100).toCompletableFuture().join();
        assertEquals(CommunityProjectRewardResult.Status.SUCCESS, retried.status());
        assertEquals(1_000_000_000_000L, retried.totalHonor());
    }

    @Test
    void mentorProgressLimitLeavesTheBondUnverifiedAndDoesNotIncrementProject() throws Exception {
        UUID mentor = player(15);
        create(mentor, "Archive", "ARC", 8, "create-archive");
        UUID mentee = player(1);
        String inviteCode = communities.profile(mentor, PERIOD, GOAL).toCompletableFuture().join().inviteCode();
        communities.bindMentor(mentee, inviteCode, 1, 20, 10, 360, 15)
                .toCompletableFuture().join();
        execute("UPDATE rpg_community_identity SET verified_mentees = 1000000000 "
                + "WHERE player_uuid = '" + mentor + "'");

        MentorBondResult rejected = communities.verifyMentorBond(
                mentee, true, 15, 180, 15, 180, PERIOD).toCompletableFuture().join();

        assertEquals(MentorBondResult.Status.PROGRESS_LIMIT, rejected.status());
        assertTrue(!communities.profile(mentee, PERIOD, GOAL).toCompletableFuture().join().mentorVerified());
        assertEquals(0, integerOrZero(
                "SELECT verified_recruits FROM rpg_community_projects WHERE period_key = ?", PERIOD));
    }

    @Test
    void permanentContributionLimitsRejectBeforeDebitAndKeepTheRequestKeyRetryable() throws Exception {
        UUID owner = player(15);
        insertAccount(owner, new BigDecimal("25000.00"));
        create(owner, "Deep Archive", "DEEP", 8, "create-deep-archive");
        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(owner, new BigDecimal("100.00"),
                                new BigDecimal("1000000000000.00"),
                                new BigDecimal("1000000000000.00"), BigDecimal.ZERO,
                                PERIOD, "seed-permanent-limit")
                        .toCompletableFuture().join().status());

        String historyId = UUID.randomUUID().toString();
        execute("INSERT INTO rpg_community_contributions "
                + "(id, idempotency_key, community_id, player_uuid, period_key, amount) "
                + "SELECT '" + historyId + "', 'archive-history', community_id, '" + owner
                + "', 'archive-history', 999999999900.00 FROM rpg_community_members "
                + "WHERE player_uuid = '" + owner + "'");
        BigDecimal beforeLifetimeRejection = balance(owner);
        int contributionsBeforeLifetimeRejection = count("rpg_community_contributions");
        int ledgerBeforeLifetimeRejection = count("rpg_ledger");
        var lifetimeRejected = communities.contributeGold(owner, new BigDecimal("100.00"),
                        new BigDecimal("1000000000000.00"), new BigDecimal("1000000000000.00"),
                        BigDecimal.ZERO, PERIOD, "retry-lifetime-limit")
                .toCompletableFuture().join();
        assertEquals(CommunityContributionResult.Status.PROGRESS_LIMIT, lifetimeRejected.status());
        assertEquals(beforeLifetimeRejection, balance(owner));
        assertEquals(contributionsBeforeLifetimeRejection, count("rpg_community_contributions"));
        assertEquals(ledgerBeforeLifetimeRejection, count("rpg_ledger"));

        execute("UPDATE rpg_community_contributions SET amount = 999999999800.00 "
                + "WHERE idempotency_key = 'archive-history'");
        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(owner, new BigDecimal("100.00"),
                                new BigDecimal("1000000000000.00"),
                                new BigDecimal("1000000000000.00"), BigDecimal.ZERO,
                                PERIOD, "retry-lifetime-limit")
                        .toCompletableFuture().join().status());
        assertEquals(new BigDecimal("1000000000000.00"), decimal(
                "SELECT SUM(amount) FROM rpg_community_contributions WHERE player_uuid = ?",
                owner.toString()));
        assertEquals(new BigDecimal("0.00"), decimal(
                "SELECT lifetime_contribution FROM rpg_community_members WHERE player_uuid = ?",
                owner.toString()));

        execute("DELETE FROM rpg_community_contributions "
                + "WHERE idempotency_key = 'archive-history'");
        execute("UPDATE rpg_community_projects SET gold_contributed = 1000000000000.00 "
                + "WHERE period_key = '" + PERIOD + "'");
        BigDecimal beforeProjectRejection = balance(owner);
        int contributionsBeforeProjectRejection = count("rpg_community_contributions");
        int ledgerBeforeProjectRejection = count("rpg_ledger");
        var projectRejected = communities.contributeGold(owner, new BigDecimal("100.00"),
                        new BigDecimal("1000000000000.00"), new BigDecimal("1000000000000.00"),
                        BigDecimal.ZERO, PERIOD, "retry-project-limit")
                .toCompletableFuture().join();
        assertEquals(CommunityContributionResult.Status.PROGRESS_LIMIT, projectRejected.status());
        assertEquals(beforeProjectRejection, balance(owner));
        assertEquals(contributionsBeforeProjectRejection, count("rpg_community_contributions"));
        assertEquals(ledgerBeforeProjectRejection, count("rpg_ledger"));

        execute("UPDATE rpg_community_projects SET gold_contributed = 999999999900.00 "
                + "WHERE period_key = '" + PERIOD + "'");
        assertEquals(CommunityContributionResult.Status.SUCCESS,
                communities.contributeGold(owner, new BigDecimal("100.00"),
                                new BigDecimal("1000000000000.00"),
                                new BigDecimal("1000000000000.00"), BigDecimal.ZERO,
                                PERIOD, "retry-project-limit")
                        .toCompletableFuture().join().status());
        assertEquals(new BigDecimal("1000000000000.00"), decimal(
                "SELECT gold_contributed FROM rpg_community_projects WHERE period_key = ?", PERIOD));
    }

    @Test
    void legacyContributionOverflowIsSaturatedForDisplayAndRejectsFurtherWrites() throws Exception {
        UUID owner = player(15);
        insertAccount(owner, new BigDecimal("25000.00"));
        create(owner, "Bounded Archive", "BND", 8, "create-bounded-archive");
        execute("INSERT INTO rpg_community_contributions "
                + "(id, idempotency_key, community_id, player_uuid, period_key, amount) "
                + "SELECT '" + UUID.randomUUID() + "', 'legacy-overflow', community_id, '" + owner
                + "', 'legacy-overflow', 1000000000001.00 FROM rpg_community_members "
                + "WHERE player_uuid = '" + owner + "'");

        var membership = communities.profile(owner, PERIOD, GOAL)
                .toCompletableFuture().join().membership().orElseThrow();
        assertEquals(new BigDecimal("1000000000000.00"), membership.lifetimeContribution());
        assertEquals(new BigDecimal("0.00"), membership.periodContribution());
        assertEquals(new BigDecimal("0.00"), membership.periodCapContribution());

        BigDecimal balanceBefore = balance(owner);
        int contributionsBefore = count("rpg_community_contributions");
        int ledgerBefore = count("rpg_ledger");
        var rejected = communities.contributeGold(owner, new BigDecimal("1.00"),
                        new BigDecimal("50000.00"), GOAL, BigDecimal.ZERO,
                        PERIOD, "legacy-overflow-retry")
                .toCompletableFuture().join();
        assertEquals(CommunityContributionResult.Status.PROGRESS_LIMIT, rejected.status());
        assertEquals(balanceBefore, balance(owner));
        assertEquals(contributionsBefore, count("rpg_community_contributions"));
        assertEquals(ledgerBefore, count("rpg_ledger"));
    }

    private CommunityOperationResult create(UUID owner, String name, String tag, int capacity, String key) {
        return communities.create(owner, name, tag, capacity, 15,
                new BigDecimal("10000.00"), new BigDecimal("25000.00"), key)
                .toCompletableFuture().join();
    }

    private UUID join(UUID owner, String tag) {
        UUID player = player(1);
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.invite(owner, player, 20, 24, PERIOD, GOAL).toCompletableFuture().join().status());
        assertEquals(CommunityOperationResult.Status.SUCCESS,
                communities.accept(player, tag, PERIOD, GOAL).toCompletableFuture().join().status());
        return player;
    }

    private UUID player(int level) {
        UUID playerId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_profiles
                    (player_uuid, archetype, ascension, level, experience, energy,
                     unspent_attribute_points, skill_points, defense, strength, agility, intelligence, stamina)
                VALUES (?, 'UNCHOSEN', 0, ?, 0, 100, 0, 0, 0, 0, 0, 0, 0)
                """)) {
            insert.setString(1, playerId.toString());
            insert.setInt(2, level);
            insert.executeUpdate();
            connection.commit();
            return playerId;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void insertAccount(UUID playerId, BigDecimal balance) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?)")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, balance);
            insert.executeUpdate();
            connection.commit();
        }
    }

    private void expireInvite(UUID playerId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_community_invites SET expires_at = ? WHERE target_uuid = ?")) {
            update.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(60)));
            update.setString(2, playerId.toString());
            update.executeUpdate();
            connection.commit();
        }
    }

    private int count(String table) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private BigDecimal balance(UUID playerId) throws Exception {
        return decimal("SELECT balance FROM rpg_accounts WHERE player_uuid = ?", playerId.toString());
    }

    private BigDecimal decimal(String sql, String value) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, value);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getBigDecimal(1).setScale(2);
            }
        }
    }

    private int integer(String sql, String value) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, value);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private int integerOrZero(String sql, String value) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, value);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private PowerSnapshot power(UUID playerId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement query = connection.prepareStatement("""
                SELECT archetype, ascension, level, experience, energy, unspent_attribute_points,
                       skill_points, defense, strength, agility, intelligence, stamina
                FROM rpg_profiles WHERE player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return new PowerSnapshot(result.getString("archetype"), result.getInt("ascension"),
                        result.getInt("level"), result.getLong("experience"), result.getInt("energy"),
                        result.getInt("unspent_attribute_points"), result.getInt("skill_points"),
                        result.getInt("defense"), result.getInt("strength"), result.getInt("agility"),
                        result.getInt("intelligence"), result.getInt("stamina"));
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
        }
    }

    private record PowerSnapshot(
            String archetype, int ascension, int level, long experience, int energy,
            int unspentAttributePoints, int skillPoints, int defense, int strength,
            int agility, int intelligence, int stamina
    ) {
    }
}
