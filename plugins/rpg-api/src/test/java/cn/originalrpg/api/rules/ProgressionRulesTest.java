package cn.originalrpg.api.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

