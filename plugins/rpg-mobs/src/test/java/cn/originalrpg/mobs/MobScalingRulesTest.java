package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobScalingRulesTest {
    @Test
    void totalHealthAndDamageRemainInsideConfiguredCaps() {
        assertEquals(1.035, MobScalingRules.healthMultiplier(1, false, 1.0, 8.0, 1.8), 0.0001);
        assertEquals(8.0, MobScalingRules.healthMultiplier(500, true, 1.0, 8.0, 1.8));
        assertEquals(4.0, MobScalingRules.damageMultiplier(500, true, 1.0, 4.0, 1.25));
        assertEquals(12.0, MobScalingRules.armorBonus(500, true, 12.0));
    }

    @Test
    void invalidBoundsAreRejectedInsteadOfProducingBrokenAttributes() {
        assertThrows(IllegalArgumentException.class,
                () -> MobScalingRules.healthMultiplier(0, false, 1.0, 8.0, 1.8));
        assertThrows(IllegalArgumentException.class,
                () -> MobScalingRules.damageMultiplier(1, false, 4.0, 1.0, 1.25));
    }

    @Test
    void newcomersHaveTighterVarianceAndMixedGroupsProtectTheirLowestMember() {
        assertEquals(3, MobScalingRules.positiveOffsetUpperBound(1, 10, 3));
        assertEquals(3, MobScalingRules.positiveOffsetUpperBound(10, 10, 3));
        assertEquals(6, MobScalingRules.positiveOffsetUpperBound(11, 10, 3));
        assertEquals(8, MobScalingRules.positiveOffsetUpperBound(11, 10, 3, 8));
        assertEquals(8, MobScalingRules.mixedGroupLevel(213, 2, 6));
        assertEquals(7, MobScalingRules.mixedGroupLevel(7, 2, 6));
        assertFalse(MobScalingRules.specialAbilitiesAllowed(4, 5));
        assertTrue(MobScalingRules.specialAbilitiesAllowed(5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> MobScalingRules.mixedGroupLevel(20, 1, -1));
    }
}
