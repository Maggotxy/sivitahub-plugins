package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillDefenseRulesTest {
    @Test
    void pvpAlwaysReceivesTheSmallerDefensiveReduction() {
        for (SkillDefenseRules.Mode mode : SkillDefenseRules.Mode.values()) {
            double pve = SkillDefenseRules.reduction(mode, false);
            double pvp = SkillDefenseRules.reduction(mode, true);
            org.junit.jupiter.api.Assertions.assertTrue(pvp < pve);
        }
    }

    @Test
    void authoredDamageNeverBecomesNegative() {
        assertEquals(3.8, SkillDefenseRules.apply(
                10.0, SkillDefenseRules.Mode.SHIELD_WALL, false), 1.0E-9);
        assertEquals(0.0, SkillDefenseRules.apply(
                Double.NaN, SkillDefenseRules.Mode.FORTRESS, false), 1.0E-9);
    }
}
