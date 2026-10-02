package cn.originalrpg.skills;

import java.util.Optional;

/** Deterministic combat plans for every persisted skill mutation. */
final class SkillMutationRules {
    static final String INVERTED = "INVERTED";
    static final String IRON_VOW = "IRON_VOW";
    static final String PREDATOR_MARK = "PREDATOR_MARK";
    static final String ARC_BURST = "ARC_BURST";
    static final String SPLIT_SHOT = "SPLIT_SHOT";
    static final String DANGEROUS_GAMBIT = "DANGEROUS_GAMBIT";

    private SkillMutationRules() {
    }

    static HealPlan heal(Optional<String> variant) {
        return active(variant, INVERTED)
                ? new HealPlan(true, 8.0, 10.0)
                : new HealPlan(false, 8.0, 8.0);
    }

    static WarCryPlan warCry(Optional<String> variant) {
        return active(variant, IRON_VOW)
                // Strong personal bulwark, but loses every party buff and all mobility.
                ? new WarCryPlan(false, 240, 1, -1, 1, 0, 240)
                : new WarCryPlan(true, 200, 0, 0, -1, 100, 0);
    }

    static ShadowStepPlan shadowStep(
            Optional<String> variant, boolean borrowed, boolean mastered,
            int borrowedDuration, int masteredDuration
    ) {
        if (active(variant, PREDATOR_MARK)) {
            // Trades invisibility for a short approach buff and a committed marked strike.
            return new ShadowStepPlan(true, 8.0, 9.0, 0, 60, 2, 100);
        }
        int duration = borrowed ? borrowedDuration : mastered ? masteredDuration : 100;
        int speedAmplifier = borrowed ? 0 : mastered ? 2 : 1;
        return new ShadowStepPlan(false, 0.0, 0.0, duration, duration, speedAmplifier, 0);
    }

    static FireballPlan fireball(Optional<String> variant) {
        return active(variant, ARC_BURST)
                // Reliable close-range arc, capped at three targets and no long-range projectile.
                ? new FireballPlan(false, 7.0, 3, 6.0, 80)
                : new FireballPlan(true, 0.0, 0, 0.0, 0);
    }

    static PowerShotPlan powerShot(Optional<String> variant) {
        return active(variant, SPLIT_SHOT)
                // More area coverage, but each arrow loses the base shot's burst and critical flag.
                ? new PowerShotPlan(3, 0.09, 1.15, false)
                : new PowerShotPlan(1, 0.0, 2.2, true);
    }

    static ImprovisationPlan improvisation(Optional<String> variant) {
        return active(variant, DANGEROUS_GAMBIT)
                ? new ImprovisationPlan(2, 1, 200, true, 120)
                : new ImprovisationPlan(1, 0, 160, false, 0);
    }

    static String visualId(String skillId, Optional<String> variant) {
        return variant.filter(value -> switch (skillId) {
            case "heal" -> value.equals(INVERTED);
            case "war-cry" -> value.equals(IRON_VOW);
            case "shadow-step" -> value.equals(PREDATOR_MARK);
            case "fireball" -> value.equals(ARC_BURST);
            case "power-shot" -> value.equals(SPLIT_SHOT);
            case "improvisation" -> value.equals(DANGEROUS_GAMBIT);
            default -> false;
        }).map(value -> skillId + "-" + value.toLowerCase(java.util.Locale.ROOT).replace('_', '-'))
                .orElse(skillId);
    }

    private static boolean active(Optional<String> variant, String expected) {
        return variant.filter(expected::equals).isPresent();
    }

    record HealPlan(boolean damaging, double radius, double power) {
    }

    record WarCryPlan(
            boolean partyWide, int durationTicks, int strengthAmplifier, int hasteAmplifier,
            int resistanceAmplifier, int weaknessTicks, int slownessTicks
    ) {
    }

    record ShadowStepPlan(
            boolean markedStrike, double radius, double damage, int invisibilityTicks,
            int speedTicks, int speedAmplifier, int markTicks
    ) {
    }

    record FireballPlan(
            boolean projectile, double radius, int maximumTargets, double damage, int slownessTicks
    ) {
    }

    record PowerShotPlan(
            int arrows, double spreadRadians, double damageMultiplier, boolean critical
    ) {
    }

    record ImprovisationPlan(
            int positiveEffects, int positiveAmplifier, int positiveTicks,
            boolean drawback, int drawbackTicks
    ) {
    }
}
