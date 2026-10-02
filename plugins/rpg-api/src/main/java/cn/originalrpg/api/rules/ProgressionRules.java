package cn.originalrpg.api.rules;

public final class ProgressionRules {
    public static final int LEVELS_PER_ASCENSION = 30;
    /** Local cap per ascension stage (1..30). Effective cap is MAX_ASCENSION*30+30 = 120. */
    public static final int LOCAL_LEVEL_CAP = 30;
    public static final int MAX_ASCENSION = 3;
    public static final int POST_ASCENSION_VERTICAL_CAP = 120;

    /**
     * 技能点本地里程碑口径（唯一实现）：每一转的本地等级 1..30 内，每 3 级发 1 点。
     * 点位在本地等级 3/6/.../30，四转合计 10*4 = 40 点。
     */
    public static final int LOCAL_LEVELS_PER_SKILL_POINT = 3;

    /** 本地里程碑档位 id；转生换段后同一 id 在新段重新累计，跨段不吞点也不多发。 */
    private static final String SKILL_POINT_SOURCE = "local-milestone:%d:%d";

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

    // --- 技能点本地里程碑（G-107 下半，唯一实现）--------------------------------------
    // 旧口径是「有效等级整除 3 的差分」：finalEffective/3 - startingEffective/3。
    // 有效等级跨段会跳档（0转30 -> 1转31），整除差在跨段点会吞点或补发，
    // 因此改为按「本地等级里程碑」计数：本段内已过档位个数 - 本段起始已过档位个数。

    /** 本地等级 [1, localLevel] 内已过的里程碑个数（本地等级本身就是里程碑索引来源）。 */
    public static int skillPointMilestonesPassed(int localLevel) {
        if (localLevel < 1 || localLevel > LOCAL_LEVEL_CAP) {
            throw new IllegalArgumentException("localLevel must be between 1 and " + LOCAL_LEVEL_CAP);
        }
        return localLevel / LOCAL_LEVELS_PER_SKILL_POINT;
    }

    /** 某一转的本地等级全程走完后应得的技能点总数（每段 10 点）。 */
    public static int skillPointsForStage(int ascension) {
        requireAscension(ascension);
        return skillPointMilestonesPassed(LOCAL_LEVEL_CAP);
    }

    /** 从 (ascension, fromLocal) 升到同段 (ascension, toLocal) 应发的技能点增量。 */
    public static int skillPointsGained(int ascension, int fromLocal, int toLocal) {
        requireAscension(ascension);
        if (toLocal < fromLocal) {
            throw new IllegalArgumentException("toLocal must not be below fromLocal");
        }
        return skillPointMilestonesPassed(toLocal) - skillPointMilestonesPassed(fromLocal);
    }

    /** [1, 本地等级] 内跨全部转生段累计应得的技能点（跨段跳档不再吞点）。 */
    public static int cumulativeSkillPoints(int ascension, int localLevel) {
        requireAscension(ascension);
        if (localLevel < 1 || localLevel > LOCAL_LEVEL_CAP) {
            throw new IllegalArgumentException("localLevel must be between 1 and " + LOCAL_LEVEL_CAP);
        }
        return ascension * skillPointsForStage(ascension) + skillPointMilestonesPassed(localLevel);
    }

    /**
     * 里程碑档位 id（幂等依据）。
     * 同一 (转, 本地里程碑) 只会对应一个 id；跨段后重新从 0 开始，
     * 因此重放/跨段都不会拿到同一个 id 重复发点。
     */
    public static String skillPointMilestoneSource(int ascension, int localLevel) {
        requireAscension(ascension);
        if (localLevel < 1 || localLevel > LOCAL_LEVEL_CAP) {
            throw new IllegalArgumentException("localLevel must be between 1 and " + LOCAL_LEVEL_CAP);
        }
        return SKILL_POINT_SOURCE.formatted(
                ascension, localLevel / LOCAL_LEVELS_PER_SKILL_POINT);
    }

    private static void requireAscension(int ascension) {
        if (ascension < 0 || ascension > MAX_ASCENSION) {
            throw new IllegalArgumentException("ascension must be between 0 and " + MAX_ASCENSION);
        }
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

    /**
     * Total experience from start (0-1) to given effective level.
     *
     * <p>口径（单一来源）：全程只累加 {@link #experienceToNextLocalLevel(int, int)} 的实走值，
     * 已完成阶段也用实走和而不是 {@link #totalExperienceForAscension(int)} 的名义预算。
     * 原因是归一化取整会让实走和与预算相差 0-2 点；混用两套口径会在阶段边界产生回退
     * （修复前 effective 91 的累计值小于 effective 90），破坏单调性。
     * 预算方法只用于展示名义目标，不参与本方法计算。
     */
    public static long totalExperienceToEffective(int effectiveLevel) {
        if (effectiveLevel < 1 || effectiveLevel > POST_ASCENSION_VERTICAL_CAP) {
            throw new IllegalArgumentException("effectiveLevel out of range");
        }
        int asc = (effectiveLevel - 1) / LEVELS_PER_ASCENSION;
        int local = effectiveLevel - asc * LEVELS_PER_ASCENSION;
        long total = 0;
        for (int a = 0; a < asc; a++) {
            total += walkedStageExperience(a);
        }
        for (int l = 1; l < local; l++) total += experienceToNextLocalLevel(asc, l);
        return total;
    }

    /** 某一转 29 级逐级实走经验之和（与升级循环同源）。 */
    private static long walkedStageExperience(int ascension) {
        long sum = 0;
        for (int local = 1; local < LOCAL_LEVEL_CAP; local++) {
            sum += experienceToNextLocalLevel(ascension, local);
        }
        return sum;
    }

    /**
     * 本阶段（本转）已投入的经验。
     * 口径说明：阶段总预算 {@link #totalExperienceForAscension(int)} 是名义设计值；
     * 逐级实走经验由 {@link #experienceToNextLocalLevel(int, int)} 求和得到，
     * 两者因归一化取整存在 0-2 点级差（见 ProgressionRulesTest）。本方法用实走值做进度，
     * 预算只作分母，因此与 JdbcProfileService / JdbcDungeonRewardService 的升级循环同源，不会漂移。
     */
    public static long stageExperienceSpent(int ascension, int localLevel, long excessExperience) {
        if (excessExperience < 0L) {
            throw new IllegalArgumentException("excessExperience must not be negative");
        }
        int stageStartEffective = effectiveLevel(ascension, 1);
        long before = totalExperienceToEffective(stageStartEffective);
        long now = totalExperienceToEffective(effectiveLevel(ascension, localLevel));
        return Math.max(0L, now - before) + excessExperience;
    }

    /**
     * 本阶段剩余经验；已到本转上限或已溢出时返回 0。
     *
     * <p>到本地上限即视为阶段完成：名义预算与实走值存在 0-2 点取整差，
     * 若不做此判定，满级玩家会看到"还差 1 点经验"这种假目标。
     */
    public static long stageExperienceRemaining(int ascension, int localLevel, long excessExperience) {
        if (localLevel >= LOCAL_LEVEL_CAP) {
            return 0L;
        }
        long budget = totalExperienceForAscension(ascension);
        return Math.max(0L, budget - stageExperienceSpent(ascension, localLevel, excessExperience));
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

