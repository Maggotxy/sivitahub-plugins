package cn.originalrpg.api.service;

import cn.originalrpg.api.model.ActivityRewardResult;
import cn.originalrpg.api.model.ActivityRewardRequest;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.EconomyProgress;
import cn.originalrpg.api.model.EconomyPurchaseResult;
import cn.originalrpg.api.model.DailyItemRewardResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ActivityEconomyService {
    CompletionStage<EconomyProgress> progress(UUID playerId);

    CompletionStage<ActivityRewardResult> reward(
            UUID playerId,
            EconomyActivity activity,
            BigDecimal requestedGold,
            long requestedMerit,
            BigDecimal dailyGoldCap,
            long dailyMeritCap,
            String idempotencyKey,
            String reason
    );

    /**
     * Applies the complete reward or nothing. Intended for item-for-currency commissions where
     * partial issuance would make compensation ambiguous.
     */
    CompletionStage<ActivityRewardResult> rewardExact(
            UUID playerId,
            EconomyActivity activity,
            BigDecimal requestedGold,
            long requestedMerit,
            BigDecimal dailyGoldCap,
            long dailyMeritCap,
            String idempotencyKey,
            String reason
    );

    CompletionStage<ActivityRewardResult> rewardBatch(
            UUID playerId,
            EconomyActivity activity,
            List<ActivityRewardRequest> rewards,
            BigDecimal dailyGoldCap,
            long dailyMeritCap
    );

    CompletionStage<EconomyPurchaseResult> purchase(
            UUID playerId,
            BigDecimal goldCost,
            long meritCost,
            String itemPayload,
            String idempotencyKey,
            String reason
    );

    /**
     * Atomically consumes a daily item quota, records an idempotent receipt,
     * and enqueues one durable mailbox delivery for every granted unit.
     *
     * <p>{@code unitItemPayload} must encode exactly one item. Splitting the
     * grant into unit deliveries lets the persistence layer truncate a reward
     * at the remaining daily allowance without editing a Paper payload. The
     * {@code minimumUsed} value is a one-way migration floor for legacy quota
     * state; it can raise, but never lower, the database-authoritative count.</p>
     */
    CompletionStage<DailyItemRewardResult> rewardDailyItem(
            UUID playerId,
            String rewardId,
            int requestedAmount,
            int dailyCap,
            int minimumUsed,
            String unitItemPayload,
            String idempotencyKey,
            String reason
    );
}
