package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillSequenceRulesTest {
    @Test
    void tpsAndPopulationSelectAStableLoadProfile() {
        assertEquals(SkillSequenceRules.LoadProfile.NORMAL,
                SkillSequenceRules.profile(20.0, 12, 32, 56));
        assertEquals(SkillSequenceRules.LoadProfile.PRESSURE,
                SkillSequenceRules.profile(18.0, 12, 32, 56));
        assertEquals(SkillSequenceRules.LoadProfile.PRESSURE,
                SkillSequenceRules.profile(20.0, 32, 32, 56));
        assertEquals(SkillSequenceRules.LoadProfile.CRITICAL,
                SkillSequenceRules.profile(15.9, 3, 32, 56));
        assertEquals(SkillSequenceRules.LoadProfile.CRITICAL,
                SkillSequenceRules.profile(20.0, 56, 32, 56));
    }

    @Test
    void activeAndWorkBudgetsOnlyDecreaseWithPressure() {
        assertEquals(64, SkillSequenceRules.activeLimit(
                SkillSequenceRules.LoadProfile.NORMAL, 64, 36, 18));
        assertEquals(36, SkillSequenceRules.activeLimit(
                SkillSequenceRules.LoadProfile.PRESSURE, 64, 36, 18));
        assertEquals(18, SkillSequenceRules.activeLimit(
                SkillSequenceRules.LoadProfile.CRITICAL, 64, 36, 18));
        assertEquals(6, SkillSequenceRules.workBudget(
                SkillSequenceRules.LoadProfile.CRITICAL, 32, 16, 6));
        assertEquals(64, SkillSequenceRules.gameplayActiveLimit(8));
        assertEquals(256, SkillSequenceRules.gameplayActiveLimit(256));
        assertEquals(512, SkillSequenceRules.gameplayActiveLimit(900));
    }
}
