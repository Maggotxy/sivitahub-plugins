package cn.originalrpg.combat;

import cn.originalrpg.api.model.Archetype;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatBalanceTest {
    @Test
    void onlyVerifiedPlayerAlliesBlockFriendlyFire() {
        assertTrue(CombatBalance.blocksFriendlyFire(true, true));
        assertFalse(CombatBalance.blocksFriendlyFire(true, false));
        assertFalse(CombatBalance.blocksFriendlyFire(false, true));
    }

    @Test
    void onlySkillDamageReceivesBossAndPvpNormalization() {
        assertEquals(1.0, CombatBalance.skillTargetMultiplier(false, true, false, 0.5, 0.75));
        assertEquals(0.5, CombatBalance.skillTargetMultiplier(true, true, true, 0.5, 0.75));
        assertEquals(0.75, CombatBalance.skillTargetMultiplier(true, false, true, 0.5, 0.75));
        assertEquals(1.0, CombatBalance.skillTargetMultiplier(true, false, false, 0.5, 0.75));
        assertEquals(1.0, CombatBalance.skillTargetMultiplier(true, true, false, 5.0, 0.75));
    }

    @Test
    void borrowedProjectilePowerComposesBeforePvpAndBossNormalization() {
        assertEquals(0.65, CombatBalance.skillDamageMultiplier(
                true, false, false, 0.65, 0.50, 0.75), 1.0E-9);
        assertEquals(0.325, CombatBalance.skillDamageMultiplier(
                true, true, true, 0.65, 0.50, 0.75), 1.0E-9);
        assertEquals(0.4875, CombatBalance.skillDamageMultiplier(
                true, false, true, 0.65, 0.50, 0.75), 1.0E-9);
        assertEquals(1.0, CombatBalance.skillDamageMultiplier(
                false, true, true, 0.25, 0.50, 0.75), 1.0E-9);
        assertEquals(2.0, CombatBalance.skillDamageMultiplier(
                true, false, false, 99.0, 0.50, 0.75), 1.0E-9);
    }

    @Test
    void executeBudgetUsesEachClassPrimaryOffenseInsteadOfStrengthForEveryone() {
        assertEquals(11, CombatBalance.primaryOffense(Archetype.WARRIOR, 11, 7, 5));
        assertEquals(7, CombatBalance.primaryOffense(Archetype.ASSASSIN, 11, 7, 5));
        assertEquals(5, CombatBalance.primaryOffense(Archetype.MAGE, 11, 7, 5));
        assertEquals(7, CombatBalance.primaryOffense(Archetype.RANGER, 11, 7, 5));
        assertEquals(5, CombatBalance.primaryOffense(Archetype.HEALER, 11, 7, 5));
        assertEquals(11, CombatBalance.primaryOffense(Archetype.FREEFORM, 11, 7, 5));
    }
}
