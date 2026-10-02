package cn.originalrpg.pets;

/** Pure combat bounds shared by event paths and covered without a live server. */
final class PetCombatRules {
    private static final double FINISHING_HEALTH_RESERVE = 1.0;

    private PetCombatRules() { }

    static double boundedDamage(double requested, double configuredCap,
                                double targetHealth, boolean nonLethal) {
        if (!Double.isFinite(requested) || !Double.isFinite(configuredCap)
                || !Double.isFinite(targetHealth) || requested <= 0.0
                || configuredCap <= 0.0 || targetHealth <= 0.0) {
            return 0.0;
        }
        double bounded = Math.min(requested, configuredCap);
        if (nonLethal) bounded = Math.min(bounded, Math.max(0.0, targetHealth - FINISHING_HEALTH_RESERVE));
        return Math.max(0.0, bounded);
    }
}
