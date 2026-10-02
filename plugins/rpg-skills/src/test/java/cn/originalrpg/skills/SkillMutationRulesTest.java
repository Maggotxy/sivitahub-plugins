package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMutationRulesTest {
    @Test
    void healerMutationReplacesHealingWithDamage() {
        var base = SkillMutationRules.heal(Optional.empty());
        var mutated = SkillMutationRules.heal(Optional.of(SkillMutationRules.INVERTED));

        assertFalse(base.damaging());
        assertEquals(8.0, base.power());
        assertTrue(mutated.damaging());
        assertEquals(10.0, mutated.power());
    }

    @Test
    void warriorMutationTradesPartyUtilityForPersonalBulwarkAndSlowness() {
        var base = SkillMutationRules.warCry(Optional.empty());
        var mutated = SkillMutationRules.warCry(Optional.of(SkillMutationRules.IRON_VOW));

        assertTrue(base.partyWide());
        assertTrue(base.weaknessTicks() > 0);
        assertFalse(mutated.partyWide());
        assertTrue(mutated.resistanceAmplifier() >= 0);
        assertTrue(mutated.slownessTicks() > 0);
        assertEquals(0, mutated.weaknessTicks());
    }

    @Test
    void assassinMutationCannotRetainInvisibility() {
        var base = SkillMutationRules.shadowStep(Optional.empty(), false, false, 60, 140);
        var mutated = SkillMutationRules.shadowStep(
                Optional.of(SkillMutationRules.PREDATOR_MARK), false, true, 60, 140);

        assertFalse(base.markedStrike());
        assertTrue(base.invisibilityTicks() > 0);
        assertTrue(mutated.markedStrike());
        assertEquals(0, mutated.invisibilityTicks());
        assertTrue(mutated.damage() > 0.0);
        assertTrue(mutated.markTicks() > 0);
    }

    @Test
    void mageMutationIsTargetCappedAndGivesUpProjectileRange() {
        var base = SkillMutationRules.fireball(Optional.empty());
        var mutated = SkillMutationRules.fireball(Optional.of(SkillMutationRules.ARC_BURST));

        assertTrue(base.projectile());
        assertFalse(mutated.projectile());
        assertEquals(3, mutated.maximumTargets());
        assertTrue(mutated.radius() > 0.0);
        assertTrue(mutated.damage() > 0.0);
    }

    @Test
    void rangerMutationLowersPerArrowBurstForCoverage() {
        var base = SkillMutationRules.powerShot(Optional.empty());
        var mutated = SkillMutationRules.powerShot(Optional.of(SkillMutationRules.SPLIT_SHOT));

        assertEquals(1, base.arrows());
        assertTrue(base.critical());
        assertEquals(3, mutated.arrows());
        assertFalse(mutated.critical());
        assertTrue(mutated.damageMultiplier() < base.damageMultiplier());
        assertTrue(mutated.spreadRadians() > 0.0);
    }

    @Test
    void freeformMutationAlwaysAddsADrawbackToItsSecondBuff() {
        var base = SkillMutationRules.improvisation(Optional.empty());
        var mutated = SkillMutationRules.improvisation(Optional.of(SkillMutationRules.DANGEROUS_GAMBIT));

        assertEquals(1, base.positiveEffects());
        assertFalse(base.drawback());
        assertEquals(2, mutated.positiveEffects());
        assertTrue(mutated.drawback());
        assertTrue(mutated.drawbackTicks() > 0);
    }

    @Test
    void everyMutationSelectsADistinctBoundedVisualIdentity() {
        assertEquals("heal-inverted", SkillMutationRules.visualId(
                "heal", Optional.of(SkillMutationRules.INVERTED)));
        assertEquals("war-cry-iron-vow", SkillMutationRules.visualId(
                "war-cry", Optional.of(SkillMutationRules.IRON_VOW)));
        assertEquals("shadow-step-predator-mark", SkillMutationRules.visualId(
                "shadow-step", Optional.of(SkillMutationRules.PREDATOR_MARK)));
        assertEquals("fireball-arc-burst", SkillMutationRules.visualId(
                "fireball", Optional.of(SkillMutationRules.ARC_BURST)));
        assertEquals("power-shot-split-shot", SkillMutationRules.visualId(
                "power-shot", Optional.of(SkillMutationRules.SPLIT_SHOT)));
        assertEquals("improvisation-dangerous-gambit", SkillMutationRules.visualId(
                "improvisation", Optional.of(SkillMutationRules.DANGEROUS_GAMBIT)));
    }
}
