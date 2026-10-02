package cn.originalrpg.skills;

import java.util.List;

/**
 * Bounded, shared progression policy for every concrete skill executor.
 *
 * <p>Each ascension opens one additional skill level. Leveling deliberately
 * changes the common cooldown pipeline instead of adding twenty-four separate
 * unchecked multipliers to individual effects.</p>
 */
final class SkillLevelRules {
    static final int ASCENSION_STAGES = 4;

    private SkillLevelRules() {
    }

    static int maximumLevel(List<Integer> levelsByAscension, int ascension) {
        if (levelsByAscension == null || levelsByAscension.size() != ASCENSION_STAGES) {
            return Math.clamp(ascension, 0, 3) + 2;
        }
        return Math.clamp(levelsByAscension.get(Math.clamp(ascension, 0, 3)), 2, 10);
    }

    static int absoluteMaximumLevel(List<Integer> levelsByAscension) {
        return maximumLevel(levelsByAscension, 3);
    }

    /** Returns the cost of current level -> current level + 1. */
    static int upgradeCost(List<Integer> configuredCosts, int currentLevel) {
        if (currentLevel < 1) return 0;
        if (configuredCosts == null || currentLevel > configuredCosts.size()) {
            return Math.clamp(currentLevel, 1, 100);
        }
        return Math.clamp(configuredCosts.get(currentLevel - 1), 1, 1000);
    }

    static long cooldownMillis(
            long baseSeconds, int level, double reductionPerLevel, double minimumMultiplier
    ) {
        int boundedLevel = Math.clamp(level, 1, 10);
        double reduction = clampFinite(reductionPerLevel, 0.0, 0.10, 0.04);
        double floor = clampFinite(minimumMultiplier, 0.50, 1.0, 0.84);
        double multiplier = Math.max(floor, 1.0 - (boundedLevel - 1) * reduction);
        return Math.max(1000L, Math.round(Math.max(1L, baseSeconds) * 1000.0 * multiplier));
    }

    private static double clampFinite(double value, double minimum, double maximum, double fallback) {
        return Math.clamp(Double.isFinite(value) ? value : fallback, minimum, maximum);
    }
}
