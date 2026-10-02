package cn.originalrpg.mobs;

/** Shared, overflow-safe experience calculation for combat and its guide UI. */
public final class MobExperienceRules {
    private MobExperienceRules() {
    }

    public static long reward(
            int monsterLevel,
            boolean elite,
            long minimumPerKill,
            long normalLevelMultiplier,
            long eliteLevelMultiplier
    ) {
        long level = Math.max(1L, monsterLevel);
        long minimum = Math.max(0L, minimumPerKill);
        long multiplier = Math.max(0L, elite ? eliteLevelMultiplier : normalLevelMultiplier);
        long scaled = multiplier > Long.MAX_VALUE / level ? Long.MAX_VALUE : level * multiplier;
        return Math.max(minimum, scaled);
    }
}
