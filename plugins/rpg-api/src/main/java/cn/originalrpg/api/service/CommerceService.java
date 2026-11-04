package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CommerceResult;
import cn.originalrpg.api.model.CommercePurchaseResult;
import cn.originalrpg.api.model.CommerceStatus;
import cn.originalrpg.api.model.CreditResult;
import cn.originalrpg.api.model.RewardClaimResult;
import cn.originalrpg.api.model.SupplyPurchaseResult;
import cn.originalrpg.api.model.LegacyPayment;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface CommerceService {
    CompletionStage<CommerceStatus> status(UUID playerId);

    /**
     * Read-only historical audit for one exact UUID, newest first with a stable
     * ID tie-break. Limit must be 1..50. Does not create accounts or change any
     * ledger; operators must not treat these rows as new gameplay eligibility.
     */
    default CompletionStage<List<LegacyPayment>> legacyPayments(UUID playerId, int limit) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Legacy payment audit is unavailable"));
    }

    /** Closed legacy path: real-money receipts must enter the isolated SupportCreditService. */
    @Deprecated(forRemoval = false)
    CompletionStage<CommerceResult> recordPayment(
            UUID playerId,
            BigDecimal amount,
            String externalReference,
            int membershipDays,
            String operator
    );

    /**
     * Applies a uniquely referenced operational Starlight adjustment. Positive
     * adjustments are constrained by persistence-layer per-operation, daily,
     * and weekly global budgets; revocations remain audited but do not modify
     * issuance quota.
     */
    CompletionStage<CreditResult> adjustCredits(
            UUID playerId,
            long delta,
            String externalReference,
            String operator
    );

    default CompletionStage<CommercePurchaseResult> purchaseWithCredits(
            UUID playerId,
            String productId,
            long creditCost,
            String itemPayload,
            String idempotencyKey
    ) {
        return purchaseWithCredits(playerId, productId, creditCost, itemPayload, idempotencyKey,
                "unlimited", 10_000);
    }

    CompletionStage<CommercePurchaseResult> purchaseWithCredits(
            UUID playerId,
            String productId,
            long creditCost,
            String itemPayload,
            String idempotencyKey,
            String periodKey,
            int maximumPurchases
    );

    /**
     * Buys time-limited membership with the same earnable Starlight balance used by the store.
     * Credits, period limits, source-proof receipt and the earned expiry are committed
     * in one transaction. The historical mixed paid/earned expiry is audit-only.
     */
    CompletionStage<CommercePurchaseResult> purchaseMembershipWithCredits(
            UUID playerId,
            String productId,
            long creditCost,
            int membershipDays,
            String idempotencyKey,
            String periodKey,
            int maximumPurchases
    );

    /**
     * Purchases an ordinary, unbound system supply with Gold. Membership is
     * revalidated by the persistence layer, so clients cannot forge the lower
     * price. Gold, the period quota, the audit ledger, and mailbox delivery are
     * committed atomically across Paper shards.
     */
    CompletionStage<SupplyPurchaseResult> purchaseSupplyWithGold(
            UUID playerId,
            String productId,
            BigDecimal publicGoldCost,
            BigDecimal memberGoldCost,
            String itemPayload,
            String idempotencyKey,
            String periodKey,
            int maximumPurchases
    );

    @Deprecated(forRemoval = false)
    CompletionStage<RewardClaimResult> claimReward(
            UUID playerId,
            String rewardId,
            BigDecimal requiredLifetimePaid,
            String itemPayload
    );
}
