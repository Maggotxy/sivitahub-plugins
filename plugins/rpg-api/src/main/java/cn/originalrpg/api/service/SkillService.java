package cn.originalrpg.api.service;

import cn.originalrpg.api.model.SkillLearnResult;
import cn.originalrpg.api.model.SkillMutationRequest;
import cn.originalrpg.api.model.SkillMutationResult;
import cn.originalrpg.api.model.SkillProgress;
import cn.originalrpg.api.model.SkillUpgradeResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface SkillService {
    CompletionStage<Map<String, SkillProgress>> skills(UUID playerId);

    CompletionStage<Optional<SkillProgress>> skill(UUID playerId, String skillId);

    /**
     * Returns a non-blocking snapshot when this player's complete skill set is
     * already resident. A missing value means "not loaded", never "no skills".
     */
    default Optional<Map<String, SkillProgress>> cachedSkills(UUID playerId) {
        return Optional.empty();
    }

    /**
     * Learns a skill against the authoritative point balance stored with the
     * player profile. Implementations must check and insert in one transaction.
     */
    CompletionStage<SkillLearnResult> learn(UUID playerId, String skillId, int pointCost);

    /**
     * Raises one learned skill by exactly one level while holding the player's
     * persistent point budget and skill row in the same transaction.
     *
     * <p>The caller must provide the level that was shown to the player. A
     * replay or concurrent click with an outdated level returns
     * {@link SkillUpgradeResult.Status#STALE_LEVEL} without spending points.
     * {@code maximumLevel} is the current progression-stage cap, not merely a
     * client-side display value.</p>
     */
    CompletionStage<SkillUpgradeResult> upgrade(
            UUID playerId,
            String skillId,
            int expectedLevel,
            int maximumLevel,
            int pointCost
    );

    /**
     * Compatibility overload retained for integrations compiled against the
     * earlier API. The caller-provided balance is intentionally ignored.
     */
    @Deprecated(forRemoval = false)
    default CompletionStage<SkillLearnResult> learn(
            UUID playerId, String skillId, int pointCost, int ignoredAvailablePoints
    ) {
        return learn(playerId, skillId, pointCost);
    }

    CompletionStage<Boolean> setVariant(UUID playerId, String skillId, String variant);

    /**
     * Restores a learned skill to its authored base effect. Implementations
     * must update only an existing skill and keep the operation idempotent.
     */
    CompletionStage<Boolean> clearVariant(UUID playerId, String skillId);

    /**
     * Atomically charges Gold and settles one pre-rolled mutation or base-form
     * restoration. Implementations must persist and replay the exact outcome
     * identified by {@link SkillMutationRequest#idempotencyKey()}.
     */
    CompletionStage<SkillMutationResult> settleMutation(SkillMutationRequest request);

    /** Releases bounded online-session state. Persistent skill data is untouched. */
    default void evict(UUID playerId) {
    }
}
