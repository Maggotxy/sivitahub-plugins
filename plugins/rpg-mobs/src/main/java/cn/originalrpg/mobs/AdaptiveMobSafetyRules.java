package cn.originalrpg.mobs;

import cn.originalrpg.api.rules.ProgressionRules;

/** Pure boundaries that keep administrator tooling out of natural encounters. */
public final class AdaptiveMobSafetyRules {
    private AdaptiveMobSafetyRules() {
    }

    public static boolean eligibleScalingSource(
            boolean hasAdministratorExclusion,
            int effectiveLevel,
            int maximumChapterPlayerLevel
    ) {
        if (effectiveLevel < 1 || maximumChapterPlayerLevel < 1) {
            throw new IllegalArgumentException("Invalid player-level scaling boundary");
        }
        // An OP playing a normal live character should still receive ordinary
        // encounters. Only a chapter-impossible test profile is isolated.
        return !hasAdministratorExclusion || effectiveLevel <= maximumChapterPlayerLevel;
    }

    public static int maximumPlayerLevel(int maximumOpenAscension) {
        if (maximumOpenAscension < 0 || maximumOpenAscension > ProgressionRules.MAX_ASCENSION) {
            throw new IllegalArgumentException("Invalid content chapter");
        }
        if (maximumOpenAscension == ProgressionRules.MAX_ASCENSION) {
            // 终态章节没有下一转，天花板就是绝对有效等级上限（120）。
            // 收敛到 ProgressionRules.effectiveLevelCap()，避免此处再手写一份乘加公式。
            return ProgressionRules.effectiveLevelCap();
        }
        return maximumOpenAscension * ProgressionRules.LEVELS_PER_ASCENSION
                + ProgressionRules.levelCap(maximumOpenAscension);
    }

    public static int maximumNaturalLevel(int maximumOpenAscension, int maximumPositiveOffset) {
        if (maximumOpenAscension < 0 || maximumOpenAscension > ProgressionRules.MAX_ASCENSION
                || maximumPositiveOffset < 0 || maximumPositiveOffset > 100) {
            throw new IllegalArgumentException("Invalid natural-mob chapter boundary");
        }
        return maximumPlayerLevel(maximumOpenAscension) + maximumPositiveOffset;
    }
}
