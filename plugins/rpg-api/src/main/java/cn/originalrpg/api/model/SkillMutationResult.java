package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Durable outcome of an atomic Gold and skill-variant settlement. */
public record SkillMutationResult(
        Status status,
        Outcome outcome,
        BigDecimal chargedGold,
        BigDecimal goldBalance,
        Optional<String> resultingVariant
) {
    public SkillMutationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(chargedGold, "chargedGold");
        Objects.requireNonNull(goldBalance, "goldBalance");
        resultingVariant = Objects.requireNonNull(resultingVariant, "resultingVariant");
        if (chargedGold.signum() < 0 || goldBalance.signum() < 0) {
            throw new IllegalArgumentException("Skill mutation money values cannot be negative");
        }
    }

    public enum Status {
        SETTLED,
        REPLAYED,
        INSUFFICIENT_FUNDS,
        NOT_LEARNED,
        STALE_VARIANT,
        INVALID
    }

    public enum Outcome {
        MUTATED,
        FAILED_ROLL,
        RESTORED,
        NONE
    }
}
