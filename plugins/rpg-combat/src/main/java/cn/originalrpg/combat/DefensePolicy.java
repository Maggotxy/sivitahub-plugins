package cn.originalrpg.combat;

import java.util.Set;

/** Pure rules for applying custom defense exactly once to defendable damage. */
final class DefensePolicy {
    private static final Set<String> UNDEFENDABLE_CAUSES = Set.of(
            "VOID", "KILL", "SUICIDE", "STARVATION", "DROWNING", "SUFFOCATION");

    private DefensePolicy() {
    }

    static boolean isDefendable(String damageCause) {
        return damageCause != null && !UNDEFENDABLE_CAUSES.contains(damageCause);
    }

    static double multiplier(
            int defense, double reductionCoefficient, double maximumReduction, double maximumVulnerability
    ) {
        double coefficient = Math.clamp(reductionCoefficient, 0.0, 1.0);
        if (defense >= 0) {
            double reductionCap = Math.clamp(maximumReduction, 0.0, 0.95);
            return Math.max(1.0 - reductionCap, 1.0 / (1.0 + defense * coefficient));
        }
        return Math.min(Math.clamp(maximumVulnerability, 1.0, 10.0),
                1.0 + -(double) defense * coefficient);
    }
}
