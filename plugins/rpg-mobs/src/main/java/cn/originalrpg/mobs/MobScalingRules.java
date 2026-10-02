package cn.originalrpg.mobs;

/** Pure, bounded scaling shared by runtime code and balance tests. */
public final class MobScalingRules {
    private MobScalingRules() {
    }

    public static double healthMultiplier(
            int level, boolean elite, double minimum, double maximum, double eliteMultiplier
    ) {
        requireBounds(level, minimum, maximum);
        double scaled = (1.0 + level * 0.035) * (elite ? eliteMultiplier : 1.0);
        return Math.clamp(scaled, minimum, maximum);
    }

    public static double damageMultiplier(
            int level, boolean elite, double minimum, double maximum, double eliteMultiplier
    ) {
        requireBounds(level, minimum, maximum);
        double scaled = (1.0 + level * 0.018) * (elite ? eliteMultiplier : 1.0);
        return Math.clamp(scaled, minimum, maximum);
    }

    public static double armorBonus(int level, boolean elite, double maximum) {
        if (level < 1 || maximum < 0.0 || !Double.isFinite(maximum)) {
            throw new IllegalArgumentException("Invalid armor scaling bounds");
        }
        return Math.clamp(level * 0.08 + (elite ? 2.0 : 0.0), 0.0, maximum);
    }

    /** Newcomers get readable encounters instead of the full positive variance. */
    public static int positiveOffsetUpperBound(
            int effectiveLevel, int newcomerThreshold, int newcomerMaximumOffset
    ) {
        return positiveOffsetUpperBound(effectiveLevel, newcomerThreshold, newcomerMaximumOffset, 6);
    }

    public static int positiveOffsetUpperBound(
            int effectiveLevel, int newcomerThreshold, int newcomerMaximumOffset, int normalMaximum
    ) {
        if (normalMaximum < 0 || normalMaximum > 100) {
            throw new IllegalArgumentException("Invalid natural-mob positive offset");
        }
        if (effectiveLevel <= Math.max(1, newcomerThreshold)) {
            return Math.clamp(newcomerMaximumOffset, 0, normalMaximum);
        }
        return normalMaximum;
    }

    /**
     * A high-level player must not seed lethal monsters beside a newcomer.
     * This is a spawn cap only; it never raises a naturally calculated level.
     */
    public static int mixedGroupLevel(int calculatedLevel, int lowestNearbyLevel, int maximumAboveLowest) {
        if (calculatedLevel < 1 || lowestNearbyLevel < 1 || maximumAboveLowest < 0) {
            throw new IllegalArgumentException("Invalid mixed-group mob bounds");
        }
        long safeMaximum = (long) lowestNearbyLevel + maximumAboveLowest;
        return (int) Math.min(calculatedLevel, Math.min(Integer.MAX_VALUE, safeMaximum));
    }

    public static boolean specialAbilitiesAllowed(int lowestRelevantPlayerLevel, int minimumLevel) {
        return lowestRelevantPlayerLevel >= Math.max(1, minimumLevel);
    }

    private static void requireBounds(int level, double minimum, double maximum) {
        if (level < 1 || minimum <= 0.0 || maximum < minimum
                || !Double.isFinite(minimum) || !Double.isFinite(maximum)) {
            throw new IllegalArgumentException("Invalid mob scaling bounds");
        }
    }
}
