package cn.originalrpg.api.service;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EnergyQuotaResult;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.SkillBookRedemption;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ProfileService {
    CompletionStage<PlayerProfile> get(UUID playerId);

    Optional<PlayerProfile> cached(UUID playerId);

    void evict(UUID playerId);

    CompletionStage<PlayerProfile> chooseArchetype(UUID playerId, Archetype archetype);

    CompletionStage<PlayerProfile> allocateAttribute(UUID playerId, CoreAttribute attribute);

    /**
     * Repairs a profile that cannot meet its selected class's first-skill
     * contract. Extra points earned through levels remain unspent; a profile
     * that already satisfies the starter contract is unchanged.
     */
    CompletionStage<PlayerProfile> prepareStarterBuild(UUID playerId);

    /**
     * Applies a one-shot XP mutation. Callers that can retry an uncertain outcome
     * must use {@link #grantExperienceOnce(UUID, UUID, long)} or own a durable
     * business receipt in the same profile transaction.
     */
    CompletionStage<PlayerProfile> grantExperience(UUID playerId, long amount);

    /**
     * Grants one immutable batch of earned XP. Replaying the same grant ID,
     * player and amount returns the current profile without awarding XP again,
     * including when the original commit succeeded but its response was lost.
     * Reusing an ID with different content must fail without changing a profile.
     *
     * <p>The receipt and normal profile/skill-point mutations share one authority
     * and one transaction. Callers must retain the ID and amount across retries;
     * this contract does not persist a caller's not-yet-submitted reward queue.</p>
     */
    default CompletionStage<PlayerProfile> grantExperienceOnce(UUID playerId, UUID grantId, long amount) {
        return java.util.concurrent.CompletableFuture.failedFuture(
                new UnsupportedOperationException("This profile provider does not support idempotent XP grants"));
    }

    CompletionStage<Boolean> spendEnergy(UUID playerId, int amount);

    CompletionStage<PlayerProfile> restoreEnergy(UUID playerId, int amount);

    /**
     * Restores Energy from a durable period quota. Concurrent requests,
     * reconnects and restarts share one counter and one diminishing schedule.
     */
    CompletionStage<EnergyQuotaResult> restoreEnergyWithQuota(
            UUID playerId,
            String sourceId,
            String periodKey,
            int baseAmount,
            int decayEveryUses,
            int decayAmount,
            int minimumAmount,
            int periodLimit
    );

    CompletionStage<SkillBookRedemption> redeemSkillBook(
            UUID playerId,
            UUID instanceId,
            String bookId,
            int points,
            String limitGroup,
            int maxUses
    );
}
