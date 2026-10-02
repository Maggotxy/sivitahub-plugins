package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CommunityContributionResult;
import cn.originalrpg.api.model.CommunityLeaderboardEntry;
import cn.originalrpg.api.model.CommunityOperationResult;
import cn.originalrpg.api.model.CommunityProfile;
import cn.originalrpg.api.model.CommunityProjectRewardResult;
import cn.originalrpg.api.model.CommunitySummary;
import cn.originalrpg.api.model.MentorBondResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface CommunityService {
    CompletionStage<CommunityProfile> profile(UUID playerId, String periodKey, BigDecimal periodGoal);

    CompletionStage<List<CommunitySummary>> browse(String periodKey, BigDecimal periodGoal, int limit);

    CompletionStage<List<CommunityLeaderboardEntry>> leaderboard(
            String periodKey, BigDecimal periodGoal, int limit);

    CompletionStage<CommunityOperationResult> create(
            UUID owner,
            String name,
            String tag,
            int capacity,
            int minimumEffectiveLevel,
            BigDecimal creationCost,
            BigDecimal startingBalance,
            String idempotencyKey
    );

    CompletionStage<CommunityOperationResult> invite(
            UUID inviter, UUID target, int maximumPendingInvites, int expiryHours,
            String periodKey, BigDecimal periodGoal);

    CompletionStage<CommunityOperationResult> accept(
            UUID playerId, String communityTag, String periodKey, BigDecimal periodGoal);

    CompletionStage<CommunityOperationResult> leave(
            UUID playerId, String periodKey, BigDecimal periodGoal);

    CompletionStage<CommunityOperationResult> kick(
            UUID actor, UUID target, String periodKey, BigDecimal periodGoal);

    CompletionStage<CommunityOperationResult> transfer(
            UUID owner, UUID target, String periodKey, BigDecimal periodGoal);

    CompletionStage<MentorBondResult> bindMentor(
            UUID playerId,
            String inviteCode,
            int effectiveLevel,
            long playMinutes,
            int maximumEffectiveLevel,
            long maximumPlayMinutes,
            int minimumMentorEffectiveLevel
    );

    CompletionStage<MentorBondResult> verifyMentorBond(
            UUID playerId,
            boolean journeyComplete,
            int effectiveLevel,
            long playMinutes,
            int minimumEffectiveLevel,
            long minimumPlayMinutes,
            String periodKey
    );

    CompletionStage<CommunityContributionResult> contributeGold(
            UUID playerId,
            BigDecimal amount,
            BigDecimal playerPeriodCap,
            BigDecimal periodGoal,
            BigDecimal startingBalance,
            String periodKey,
            String idempotencyKey
    );

    CompletionStage<CommunityProjectRewardResult> claimWeeklyProjectReward(
            UUID playerId,
            String periodKey,
            BigDecimal periodGoal,
            BigDecimal minimumContribution,
            int honorAward
    );
}
