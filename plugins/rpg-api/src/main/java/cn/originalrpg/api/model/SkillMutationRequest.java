package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable intent for one Gold-backed skill mutation settlement. */
public record SkillMutationRequest(
        UUID playerId,
        String skillId,
        Operation operation,
        Optional<String> expectedVariant,
        Optional<String> candidateVariant,
        BigDecimal goldCost,
        String idempotencyKey
) {
    public SkillMutationRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(operation, "operation");
        expectedVariant = Objects.requireNonNull(expectedVariant, "expectedVariant");
        candidateVariant = Objects.requireNonNull(candidateVariant, "candidateVariant");
        Objects.requireNonNull(goldCost, "goldCost");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    public enum Operation {
        MUTATE,
        RESTORE
    }
}
