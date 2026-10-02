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

    CompletionStage<PlayerProfile> grantExperience(UUID playerId, long amount);

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

    /**
     * @deprecated Phase 1 removed simplified bypass — use AscensionService.ascend() (C-015).
     */
    @Deprecated(forRemoval = true)
    CompletionStage<PlayerProfile> ascend(UUID playerId);

    CompletionStage<SkillBookRedemption> redeemSkillBook(
            UUID playerId,
            UUID instanceId,
            String bookId,
            int points,
            String limitGroup,
            int maxUses
    );
}
