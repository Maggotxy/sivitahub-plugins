package cn.originalrpg.api.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressionRulesTest {
    @Test
    void stageCapsFollowTheReleasePlan() {
        assertEquals(30, ProgressionRules.levelCap(0));
        assertEquals(30, ProgressionRules.levelCap(1));
        assertEquals(30, ProgressionRules.levelCap(2));
        assertEquals(30, ProgressionRules.LOCAL_LEVEL_CAP);
        assertEquals(120, ProgressionRules.effectiveLevelCap());
        assertEquals(1, ProgressionRules.effectiveLevel(0, 1));
        assertEquals(30, ProgressionRules.effectiveLevel(0, 30));
        assertEquals(31, ProgressionRules.effectiveLevel(1, 1));
        assertEquals(60, ProgressionRules.effectiveLevel(1, 30));
        assertEquals(120, ProgressionRules.effectiveLevel(3, 30));
        assertTrue(ProgressionRules.experienceToNextLocalLevel(2, 1) > ProgressionRules.experienceToNextLocalLevel(0, 1));
        assertTrue(ProgressionRules.totalExperienceForAscension(2) > ProgressionRules.totalExperienceForAscension(0));
    }

    @Test
    void effectiveLevelDoesNotRegressAfterAscension() {
        int before = ProgressionRules.effectiveLevel(0, 30);
        int after = ProgressionRules.effectiveLevel(1, 1);
        assertTrue(after > before, "1转 Lv.1 must be greater than 0转 Lv.30");
        int before2 = ProgressionRules.effectiveLevel(1, 30);
        int after2 = ProgressionRules.effectiveLevel(2, 1);
        assertTrue(after2 > before2);
    }

    // --- G-111: totalExperienceForAscension / totalExperienceToEffective / effectiveLevelCap 行为断言 ---

    @Test
    void effectiveLevelCapIsTheTerminalEffectiveCap() {
        assertEquals(ProgressionRules.MAX_ASCENSION * ProgressionRules.LEVELS_PER_ASCENSION
                + ProgressionRules.LOCAL_LEVEL_CAP, ProgressionRules.effectiveLevelCap());
        assertEquals(120, ProgressionRules.effectiveLevelCap());
        // 终态有效等级恰好落在有效上限上
        assertEquals(ProgressionRules.effectiveLevelCap(),
                ProgressionRules.effectiveLevel(ProgressionRules.MAX_ASCENSION, ProgressionRules.LOCAL_LEVEL_CAP));
    }

    @Test
    void totalExperienceToEffectiveBoundaries() {
        // 0 经验：1 转 1 级是起点，累计为 0
        assertEquals(0L, ProgressionRules.totalExperienceToEffective(1));
        // 刚好到本转上限：累计与下一转起点相等（转生只重置本地等级，不再消耗经验）
        long atStageCap = ProgressionRules.totalExperienceToEffective(ProgressionRules.LOCAL_LEVEL_CAP);
        long nextStageStart = ProgressionRules.totalExperienceToEffective(ProgressionRules.LOCAL_LEVEL_CAP + 1);
        assertEquals(atStageCap, nextStageStart);
        // 下一转内部仍然严格增长
        assertTrue(nextStageStart < ProgressionRules.totalExperienceToEffective(ProgressionRules.LOCAL_LEVEL_CAP + 2));
        // 超过上限（120 是最后一级）→ 越界必须拒绝，不能静默外推
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionRules.totalExperienceToEffective(ProgressionRules.effectiveLevelCap() + 1));
        assertThrows(IllegalArgumentException.class, () -> ProgressionRules.totalExperienceToEffective(0));
    }

    @Test
    void totalExperienceToEffectiveIsMonotonicAcrossTheWholeVerticalRange() {
        long previous = -1L;
        for (int effective = 1; effective <= ProgressionRules.effectiveLevelCap(); effective++) {
            long current = ProgressionRules.totalExperienceToEffective(effective);
            assertTrue(current >= previous, "effective " + effective + " regressed");
            previous = current;
        }
    }

    @Test
    void totalExperienceForAscensionMatchesTheWalkedPathWithinRoundingTolerance() {
        // 口径：预算（名义值）vs 逐级实走值（进度分母用的是实走值）。
        // 归一化取整会带来 0-2 点级差，这里显式钉住容差，防止将来静默漂移。
        for (int ascension = 0; ascension <= ProgressionRules.MAX_ASCENSION; ascension++) {
            long walked = 0L;
            for (int local = 1; local < ProgressionRules.LOCAL_LEVEL_CAP; local++) {
                walked += ProgressionRules.experienceToNextLocalLevel(ascension, local);
            }
            long budget = ProgressionRules.totalExperienceForAscension(ascension);
            assertTrue(Math.abs(budget - walked) <= 2L,
                    "ascension " + ascension + " budget=" + budget + " walked=" + walked);
        }
    }

    @Test
    void totalExperienceForAscensionBoundaries() {
        assertTrue(ProgressionRules.totalExperienceForAscension(0) > 0L);
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionRules.totalExperienceForAscension(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionRules.totalExperienceForAscension(ProgressionRules.MAX_ASCENSION + 1));
    }

    @Test
    void stageExperienceSpentTracksTheWalkedBudget() {
        // 0 经验、本转起点
        assertEquals(0L, ProgressionRules.stageExperienceSpent(0, 1, 0L));
        // 刚好到本转上限：实走值等于预算的 walked 值（允许取整容差）
        long atCap = ProgressionRules.stageExperienceSpent(0, ProgressionRules.LOCAL_LEVEL_CAP, 0L);
        assertTrue(Math.abs(atCap - ProgressionRules.totalExperienceForAscension(0)) <= 2L);
        // 到本转上限即阶段完成，剩余钳到 0（名义预算与实走值的取整差不当作未完成目标）
        assertEquals(0L, ProgressionRules.stageExperienceRemaining(0, ProgressionRules.LOCAL_LEVEL_CAP, 0L));
        assertTrue(ProgressionRules.stageExperienceRemaining(0, ProgressionRules.LOCAL_LEVEL_CAP - 1, 0L) > 0L);
        // 超过上限（上限等级仍有零头经验）：已投入继续随零头增长，剩余钳到 0
        long overflow = ProgressionRules.stageExperienceSpent(0, ProgressionRules.LOCAL_LEVEL_CAP, 500L);
        assertEquals(atCap + 500L, overflow);
        assertEquals(0L, ProgressionRules.stageExperienceRemaining(0, ProgressionRules.LOCAL_LEVEL_CAP, 500L));
        assertThrows(IllegalArgumentException.class, () -> ProgressionRules.stageExperienceSpent(0, 1, -1L));
    }

    // --- G-107 下半：技能点本地里程碑（唯一实现）行为断言 ---

    /** 旧口径（有效等级整除 3 的差分），只用于对照，不属于生产代码。 */
    private static int legacySkillPoints(int fromEffective, int toEffective) {
        return Math.max(0, toEffective / 3 - fromEffective / 3);
    }

    @Test
    void localMilestonesGiveTenPointsPerStageAndMatchTheLegacyPaceInsideAStage() {
        // 段内点位 3/6/9/.../30；0转 5 级 -> 1 点（与旧口径一致，段内不改变节奏）
        for (int local = 1; local <= ProgressionRules.LOCAL_LEVEL_CAP; local++) {
            int expected = local / ProgressionRules.LOCAL_LEVELS_PER_SKILL_POINT;
            assertEquals(expected, ProgressionRules.skillPointMilestonesPassed(local));
            assertEquals(legacySkillPoints(ProgressionRules.effectiveLevel(0, 1),
                            ProgressionRules.effectiveLevel(0, local)),
                    ProgressionRules.cumulativeSkillPoints(0, local),
                    "0 转段内旧新口径必须一致，local=" + local);
        }
        assertEquals(10, ProgressionRules.skillPointsForStage(0));
        assertEquals(10, ProgressionRules.skillPointsForStage(1));
        assertEquals(10, ProgressionRules.skillPointsForStage(2));
        assertEquals(10, ProgressionRules.skillPointsForStage(3));
    }

    @Test
    void fourStagesAccumulateFortyPointsAtEveryBoundary() {
        assertEquals(0, ProgressionRules.cumulativeSkillPoints(0, 1));
        assertEquals(10, ProgressionRules.cumulativeSkillPoints(0, ProgressionRules.LOCAL_LEVEL_CAP));
        assertEquals(10, ProgressionRules.cumulativeSkillPoints(1, 1), "跨段（30->31）不得吞点");
        assertEquals(20, ProgressionRules.cumulativeSkillPoints(1, ProgressionRules.LOCAL_LEVEL_CAP));
        assertEquals(20, ProgressionRules.cumulativeSkillPoints(2, 1));
        assertEquals(30, ProgressionRules.cumulativeSkillPoints(2, ProgressionRules.LOCAL_LEVEL_CAP));
        assertEquals(30, ProgressionRules.cumulativeSkillPoints(3, 1));
        assertEquals(40, ProgressionRules.cumulativeSkillPoints(3, ProgressionRules.LOCAL_LEVEL_CAP));
    }

    @Test
    void milestoneDeltaIsStageLocalAndNeverSpansAscension() {
        // 段内增量：1 -> 4 过 1 档
        assertEquals(1, ProgressionRules.skillPointsGained(0, 1, 4));
        // 满段 29 -> 30 过 1 档
        assertEquals(1, ProgressionRules.skillPointsGained(0, 29, 30));
        // 同档位内不重复发点（12/13/14 都落在第 4 档）
        assertEquals(0, ProgressionRules.skillPointsGained(0, 12, 14));
        // 重放同一 (转, 起始, 结束)：纯函数结果恒等，是幂等的基础
        assertEquals(ProgressionRules.skillPointsGained(2, 6, 12),
                ProgressionRules.skillPointsGained(2, 6, 12));
        // 本段内不可能跨转，唯一合法输入是同段；
        // 转生换段由 skillPointsForStage/累计口径处理，单段差分不参与跨段
        assertThrows(IllegalArgumentException.class, () -> ProgressionRules.skillPointsGained(0, 12, 10));
    }

    @Test
    void milestoneSourceIdsAreStageScopedAndStableForReplay() {
        assertEquals("local-milestone:0:0", ProgressionRules.skillPointMilestoneSource(0, 1));
        assertEquals("local-milestone:0:0", ProgressionRules.skillPointMilestoneSource(0, 2));
        assertEquals("local-milestone:0:1", ProgressionRules.skillPointMilestoneSource(0, 3));
        assertEquals("local-milestone:1:0", ProgressionRules.skillPointMilestoneSource(1, 1));
        assertEquals("local-milestone:1:0", ProgressionRules.skillPointMilestoneSource(1, 2));
        // 跨段（0转30 -> 1转1）档位 id 变了 ⇒ 新段里程碑不会被旧段幂等键挡住
        assertFalse(ProgressionRules.skillPointMilestoneSource(0, 30)
                .equals(ProgressionRules.skillPointMilestoneSource(1, 1)));
        // 同 (转, 里程碑) 重放得到同一个 id ⇒ 幂等依据稳定
        assertEquals(ProgressionRules.skillPointMilestoneSource(3, 15),
                ProgressionRules.skillPointMilestoneSource(3, 15));
    }

    @Test
    void localMilestoneRulesRejectOutOfRangeInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionRules.skillPointMilestonesPassed(0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionRules.skillPointMilestonesPassed(ProgressionRules.LOCAL_LEVEL_CAP + 1));
        assertThrows(IllegalArgumentException.class, () -> ProgressionRules.skillPointsForStage(-1));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionRules.skillPointsForStage(ProgressionRules.MAX_ASCENSION + 1));
        assertThrows(IllegalArgumentException.class, () -> ProgressionRules.cumulativeSkillPoints(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ProgressionRules.skillPointMilestoneSource(0, 0));
    }

    @Test
    void customDropsStopAtTwentyLevelDifference() {
        assertTrue(CombatRules.allowsCustomDrops(29, 10));
        assertFalse(CombatRules.allowsCustomDrops(30, 10));
    }

    @Test
    void bossExecuteIsHalvedAndPowerBounded() {
        assertEquals(0.05, CombatRules.executeThreshold(0.10, true, 1000, 10_000), 0.0001);
        assertEquals(0.02, CombatRules.executeThreshold(0.10, false, 200, 10_000), 0.0001);
    }
}

