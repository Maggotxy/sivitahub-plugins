package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillCrowdControlRulesTest {
    @Test
    void ordinaryMonstersReceiveTheAuthoredEffect() {
        assertEquals(new SkillCrowdControlRules.EffectPlan(180, 4),
                SkillCrowdControlRules.plan(false, 180, 4));
    }

    @Test
    void bossesReceiveHalfDurationAndOneLowerAmplifier() {
        assertEquals(new SkillCrowdControlRules.EffectPlan(90, 3),
                SkillCrowdControlRules.plan(true, 180, 4));
        assertEquals(new SkillCrowdControlRules.EffectPlan(50, 0),
                SkillCrowdControlRules.plan(true, 100, 0));
    }

    @Test
    void playersUseTheSameBoundedHalfStrengthControlPlan() {
        assertEquals(new SkillCrowdControlRules.EffectPlan(60, 1),
                SkillCrowdControlRules.plan(false, true, 120, 2));
    }
}
