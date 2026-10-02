package cn.originalrpg.skills;

import cn.originalrpg.api.model.EquipmentSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillUseRulesTest {
    @Test
    void commandFallbackStillRequiresARealForgedWeaponUnlessOperatorIsTesting() {
        EquipmentSnapshot weapon = equipment("WEAPON");
        EquipmentSnapshot armor = equipment("ARMOR");

        assertTrue(SkillUseRules.canCastWithHeldEquipment(Optional.of(weapon), false));
        assertFalse(SkillUseRules.canCastWithHeldEquipment(Optional.of(armor), false));
        assertFalse(SkillUseRules.canCastWithHeldEquipment(Optional.empty(), false));
        assertTrue(SkillUseRules.canCastWithHeldEquipment(Optional.empty(), true));
    }

    @Test
    void borrowedSkillUsesLegalRuntimeLevelWithoutPretendingItWasLearned() {
        var progress = SkillUseRules.borrowedProgress("shadow-step");

        assertEquals("shadow-step", progress.skillId());
        assertEquals(1, progress.level());
        assertEquals(0, progress.pointCost());
        assertTrue(progress.variant().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SkillUseRules.borrowedProgress(" "));
    }

    @Test
    void healthCostsAreExplicitNonlethalAndCannotBecomeFreeAtLowHealth() {
        assertTrue(SkillUseRules.canPayHealthCost(5.01, 4.0));
        assertEquals(1.01, SkillUseRules.healthAfterCost(5.01, 4.0), 1.0E-9);
        assertFalse(SkillUseRules.canPayHealthCost(4.0, 4.0));
        assertFalse(SkillUseRules.canPayHealthCost(1.0, 4.0));
        assertThrows(IllegalArgumentException.class, () -> SkillUseRules.healthAfterCost(4.0, 4.0));
    }

    @Test
    void healingCombinesIntelligenceEquipmentAndRareTraitDeterministically() {
        assertEquals(1.0, SkillUseRules.healingMultiplier(0, 0.01, 2.0, 0, false, 1.35), 1.0E-9);
        assertEquals(1.65, SkillUseRules.healingMultiplier(10, 0.01, 2.0, 50, false, 1.35), 1.0E-9);
        assertEquals(2.2275, SkillUseRules.healingMultiplier(10, 0.01, 2.0, 50, true, 1.35), 1.0E-9);
    }

    @Test
    void healingBoundsUnsafeConfigurationAndNegativeStats() {
        // Intelligence cannot reduce healing, and equipment penalties bottom at 50%.
        assertEquals(0.5, SkillUseRules.healingMultiplier(-20, 0.01, 2.0, -900, false, 1.35), 1.0E-9);
        // Per-point scaling, attribute cap and rare-trait multiplier all have hard safety caps.
        assertEquals(7.5, SkillUseRules.healingMultiplier(10_000, 9.0, 99.0, -900, true, 99.0), 1.0E-9);
        // Negative configuration cannot turn healing into damage or reduce the base multiplier.
        assertEquals(1.0, SkillUseRules.healingMultiplier(100, -5.0, -5.0, 0, true, -5.0), 1.0E-9);
        // Non-finite YAML values fall back to shipped defaults instead of reaching Bukkit health APIs.
        assertEquals(1.485, SkillUseRules.healingMultiplier(
                10, Double.NaN, Double.POSITIVE_INFINITY, 0, true, Double.NaN), 1.0E-9);
    }

    private static EquipmentSnapshot equipment(String type) {
        return new EquipmentSnapshot(UUID.randomUUID(), UUID.randomUUID(), Optional.empty(),
                "COMMON", type, "UNIVERSAL", 1, 0, 0, 0, Optional.empty(), Map.of());
    }
}
