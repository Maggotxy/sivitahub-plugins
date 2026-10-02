package cn.originalrpg.skills;

import cn.originalrpg.api.model.EquipmentSnapshot;
import cn.originalrpg.api.model.SkillProgress;

import java.util.Optional;

/** Pure boundaries for costs which must not be altered by combat mitigation. */
final class SkillUseRules {
    private SkillUseRules() {
    }

    static boolean canPayHealthCost(double currentHealth, double cost) {
        return Double.isFinite(currentHealth) && Double.isFinite(cost)
                && currentHealth > Math.max(0.0, cost);
    }

    static double healthAfterCost(double currentHealth, double cost) {
        if (!canPayHealthCost(currentHealth, cost)) {
            throw new IllegalArgumentException("Health cost must leave the caster alive");
        }
        return Math.max(1.0, currentHealth - Math.max(0.0, cost));
    }

    /**
     * Equipment-granted skills have no persistent learning cost or mutation,
     * but still need a legal runtime level. A level-zero placeholder violates
     * the shared model before the skill effect can execute.
     */
    static SkillProgress borrowedProgress(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("Borrowed skill id is required");
        }
        return new SkillProgress(skillId, 1, 0, Optional.empty());
    }

    /**
     * Public command fallbacks must not bypass the forged-weapon combat loop.
     * Operators retain an explicit test permission, while malformed signed
     * items are rejected before this pure rule is evaluated.
     */
    static boolean canCastWithHeldEquipment(
            Optional<EquipmentSnapshot> heldEquipment,
            boolean operatorTestBypass
    ) {
        if (operatorTestBypass) return true;
        return heldEquipment
                .map(EquipmentSnapshot::equipmentType)
                .filter(type -> type.equalsIgnoreCase("WEAPON"))
                .isPresent();
    }

    /**
     * Calculates deterministic outgoing healing before the target health cap is
     * applied. Every caller receives the same defensive bounds even when a
     * server operator supplies an unsafe value in config.
     */
    static double healingMultiplier(
            int intelligence,
            double configuredPerIntelligence,
            double configuredAttributeCap,
            int equipmentHealingPercent,
            boolean hasHealingBoost,
            double configuredBoostMultiplier
    ) {
        double perIntelligence = Math.clamp(finiteOr(configuredPerIntelligence, 0.01), 0.0, 0.05);
        double attributeCap = Math.clamp(finiteOr(configuredAttributeCap, 2.0), 1.0, 5.0);
        double attributeMultiplier = Math.min(attributeCap,
                1.0 + Math.max(0, intelligence) * perIntelligence);
        double equipmentMultiplier = Math.max(0.5, 1.0 + equipmentHealingPercent / 100.0);
        if (hasHealingBoost) {
            equipmentMultiplier *= Math.clamp(finiteOr(configuredBoostMultiplier, 1.35), 1.0, 3.0);
        }
        return attributeMultiplier * equipmentMultiplier;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
