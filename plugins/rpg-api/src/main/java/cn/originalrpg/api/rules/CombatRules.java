package cn.originalrpg.api.rules;

public final class CombatRules {
    private CombatRules() {
    }

    public static boolean allowsCustomDrops(int monsterLevel, int killerEffectiveLevel) {
        return Math.abs(monsterLevel - killerEffectiveLevel) < 20;
    }

    public static double executeThreshold(double baseRatio, boolean bossOrPlayer, double attackerPower, double targetMaxHealth) {
        if (baseRatio < 0.0 || baseRatio > 1.0 || attackerPower < 0.0 || targetMaxHealth <= 0.0) {
            throw new IllegalArgumentException("Invalid execute calculation input");
        }
        double categoryRatio = bossOrPlayer ? baseRatio * 0.5 : baseRatio;
        double powerBound = Math.min(1.0, attackerPower / targetMaxHealth);
        return Math.min(categoryRatio, powerBound);
    }

    public static int adaptiveMonsterLevel(int playerEffectiveLevel, int randomOffset) {
        return Math.max(1, playerEffectiveLevel + Math.clamp(randomOffset, -3, 6));
    }
}

