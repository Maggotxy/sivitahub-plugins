package cn.originalrpg.api.rules;

public final class ProgressionRules {
    public static final int LEVELS_PER_ASCENSION = 30;
    /** Local cap per ascension stage (1..30). Effective cap is MAX_ASCENSION*30+30 = 120. */
    public static final int LOCAL_LEVEL_CAP = 30;
    public static final int MAX_ASCENSION = 3;
    public static final int POST_ASCENSION_VERTICAL_CAP = 120;

    /**
     * Stage total experience budgets (sum of 29 transitions per stage).
     * Chosen to keep early game brisk (~120 XP for 1->2) while keeping
     * total to 120 at ~2.48M (avoids eff^2 inflation but preserves stage growth).
     */
    private static final long[] STAGE_BUDGETS = {280_000L, 450_000L, 700_000L, 1_050_000L};
    private static final long[] STAGE_WEIGHT_SUM = new long[MAX_ASCENSION + 1];

    static {
        for (int asc = 0; asc <= MAX_ASCENSION; asc++) {
            long sum = 0;
            for (int local = 1; local < LOCAL_LEVEL_CAP; local++) {
                sum += baseForLocal(local);
            }
            STAGE_WEIGHT_SUM[asc] = sum;
        }
    }

    private ProgressionRules() {
    }

    private static long baseForLocal(int localLevel) {
        // Quadratic weight preserves increasing cost without eff^2 inflation
        return 90L * localLevel * localLevel + 310L * localLevel;
    }

    /** Effective level 1..120 from ascension + local level. */
    public static int effectiveLevel(int ascension, int localLevel) {
        if (ascension < 0 || ascension > MAX_ASCENSION) {
            throw new IllegalArgumentException("ascension must be between 0 and " + MAX_ASCENSION);
        }
        if (localLevel < 1 || localLevel > LOCAL_LEVEL_CAP) {
            throw new IllegalArgumentException("localLevel must be between 1 and " + LOCAL_LEVEL_CAP);
        }
        return ascension * LEVELS_PER_ASCENSION + localLevel;
    }

    /** Legacy alias – now validates local cap. */
    public static int levelCap(int ascension) {
        if (ascension < 0 || ascension > MAX_ASCENSION) {
            throw new IllegalArgumentException("ascension must be between 0 and " + MAX_ASCENSION);
        }
        return LOCAL_LEVEL_CAP;
    }

    /** Effective cap for reference (always 120). */
    public static int effectiveLevelCap() {
        return POST_ASCENSION_VERTICAL_CAP;
    }

    public static long experienceToNextLocalLevel(int ascension, int localLevel) {
        if (ascension < 0 || ascension > MAX_ASCENSION) {
            throw new IllegalArgumentException("ascension must be between 0 and " + MAX_ASCENSION);
        }
        if (localLevel < 1 || localLevel >= LOCAL_LEVEL_CAP) {
            throw new IllegalArgumentException("localLevel must be between 1 and " + (LOCAL_LEVEL_CAP - 1));
        }
        long stageBudget = STAGE_BUDGETS[ascension];
        long weightSum = STAGE_WEIGHT_SUM[ascension];
        long base = baseForLocal(localLevel);
        // normalized weight * budget
        long needed = Math.round((double) stageBudget * base / weightSum);
        return Math.max(100L, needed);
    }

    /** Total experience required to complete one ascension stage (29 levels). */
    public static long totalExperienceForAscension(int ascension) {
        if (ascension < 0 || ascension > MAX_ASCENSION) {
            throw new IllegalArgumentException("ascension must be between 0 and " + MAX_ASCENSION);
        }
        return STAGE_BUDGETS[ascension];
    }

    /** Total experience from start (0-1) to given effective level. */
    public static long totalExperienceToEffective(int effectiveLevel) {
        if (effectiveLevel < 1 || effectiveLevel > POST_ASCENSION_VERTICAL_CAP) {
            throw new IllegalArgumentException("effectiveLevel out of range");
        }
        int asc = (effectiveLevel - 1) / LEVELS_PER_ASCENSION;
        int local = effectiveLevel - asc * LEVELS_PER_ASCENSION;
        long total = 0;
        for (int a = 0; a < asc; a++) total += totalExperienceForAscension(a);
        for (int l = 1; l < local; l++) total += experienceToNextLocalLevel(asc, l);
        return total;
    }

    /** Legacy method – now delegates to local-level model (level is local). */
    public static long experienceToNextLevel(int ascension, int level) {
        if (level < 1 || level > LOCAL_LEVEL_CAP) {
            throw new IllegalArgumentException("level must be between 1 and " + LOCAL_LEVEL_CAP);
        }
        if (level >= LOCAL_LEVEL_CAP) {
            // At cap, return next stage's first step or sentinel
            if (ascension >= MAX_ASCENSION) return Long.MAX_VALUE;
            return experienceToNextLocalLevel(ascension, LOCAL_LEVEL_CAP - 1);
        }
        return experienceToNextLocalLevel(ascension, level);
    }
}

