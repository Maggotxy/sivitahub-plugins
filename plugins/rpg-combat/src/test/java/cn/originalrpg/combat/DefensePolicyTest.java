package cn.originalrpg.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefensePolicyTest {
    @Test
    void positiveDefenseUsesDiminishingReturnsAndHonorsReductionCap() {
        assertEquals(1.0, DefensePolicy.multiplier(0, 0.009, 0.60, 1.50), 1.0E-9);
        assertEquals(1.0 / 1.9, DefensePolicy.multiplier(100, 0.009, 0.60, 1.50), 1.0E-9);
        assertEquals(0.40, DefensePolicy.multiplier(10_000, 0.009, 0.60, 1.50), 1.0E-9);
    }

    @Test
    void negativeDefenseIncreasesDamageButHonorsVulnerabilityCap() {
        assertEquals(1.09, DefensePolicy.multiplier(-10, 0.009, 0.60, 1.50), 1.0E-9);
        assertEquals(1.50, DefensePolicy.multiplier(-10_000, 0.009, 0.60, 1.50), 1.0E-9);
    }

    @Test
    void attacksAndPhysicalHazardsAreDefendableButAbsoluteDamageIsNot() {
        assertTrue(DefensePolicy.isDefendable("ENTITY_ATTACK"));
        assertTrue(DefensePolicy.isDefendable("PROJECTILE"));
        assertTrue(DefensePolicy.isDefendable("FALL"));
        assertTrue(DefensePolicy.isDefendable("LAVA"));
        assertFalse(DefensePolicy.isDefendable("VOID"));
        assertFalse(DefensePolicy.isDefendable("STARVATION"));
        assertFalse(DefensePolicy.isDefendable("DROWNING"));
        assertFalse(DefensePolicy.isDefendable(null));
    }
}
