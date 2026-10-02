package cn.originalrpg.combat;

import cn.originalrpg.api.model.Archetype;

/** Pure category and scaling boundaries shared by the hot combat listener. */
final class CombatBalance {
    private CombatBalance() {
    }

    static double skillTargetMultiplier(
            boolean skillDamage, boolean playerTarget, boolean bossTarget,
            double pvpMultiplier, double bossMultiplier
    ) {
        if (!skillDamage) return 1.0;
        if (playerTarget) return Math.clamp(pvpMultiplier, 0.0, 1.0);
        if (bossTarget) return Math.clamp(bossMultiplier, 0.0, 1.0);
        return 1.0;
    }

    static double skillDamageMultiplier(
            boolean skillDamage, boolean playerTarget, boolean bossTarget, double authoredPower,
            double pvpMultiplier, double bossMultiplier
    ) {
        if (!skillDamage) return 1.0;
        double boundedPower = Math.clamp(Double.isFinite(authoredPower) ? authoredPower : 1.0, 0.25, 2.0);
        return boundedPower * skillTargetMultiplier(
                true, playerTarget, bossTarget, pvpMultiplier, bossMultiplier);
    }

    static boolean blocksFriendlyFire(boolean playerTarget, boolean verifiedAlly) {
        return playerTarget && verifiedAlly;
    }

    static int primaryOffense(Archetype archetype, int strength, int agility, int intelligence) {
        int safeStrength = Math.max(0, strength);
        int safeAgility = Math.max(0, agility);
        int safeIntelligence = Math.max(0, intelligence);
        if (archetype == null) return Math.max(safeStrength, Math.max(safeAgility, safeIntelligence));
        return switch (archetype) {
            case HEALER, MAGE -> safeIntelligence;
            case WARRIOR -> safeStrength;
            case ASSASSIN, RANGER -> safeAgility;
            case FREEFORM, UNCHOSEN -> Math.max(safeStrength, Math.max(safeAgility, safeIntelligence));
        };
    }
}
