package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.UUID;

/** A verified, player-bound 26.2 sulfur-cube resonance used to bias equipment refinement. */
public record ForgeImprint(
        UUID imprintId,
        UUID ownerId,
        String bias,
        String sourceMaterial,
        int strength
) {
    public ForgeImprint {
        Objects.requireNonNull(imprintId, "imprintId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(bias, "bias");
        Objects.requireNonNull(sourceMaterial, "sourceMaterial");
        if (strength < 1 || strength > 3) throw new IllegalArgumentException("strength must be 1-3");
    }

    /** Chance that a successful eight-stat refinement selects the biased stat. */
    public double selectedStatChance() {
        int bonusWeight = strength * 75;
        return (100.0 + bonusWeight) / (800.0 + bonusWeight);
    }
}
