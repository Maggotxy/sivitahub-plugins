package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentTraitCatalogTest {
    @Test
    void everyBaseClassHasOneConcreteBorrowedSkillRune() {
        assertEquals(Map.of(
                "skill-heal", "heal",
                "skill-war-cry", "war-cry",
                "shadow-step", "shadow-step",
                "skill-fireball", "fireball",
                "skill-power-shot", "power-shot",
                "skill-improvisation", "improvisation"
        ), EquipmentTraitCatalog.borrowedSkills());
        assertTrue(EquipmentTraitCatalog.implemented().containsAll(
                EquipmentTraitCatalog.borrowedSkills().keySet()));
        assertTrue(EquipmentTraitCatalog.borrowedSkill("life-steal").isEmpty());
    }

    @Test
    void runeRequiresPersistentOwnerAndCompleteRevelation() {
        UUID owner = UUID.randomUUID();
        assertTrue(EquipmentTraitCatalog.activeFor(snapshot(owner, 6, 6), owner));
        assertFalse(EquipmentTraitCatalog.activeFor(snapshot(owner, 6, 5), owner));
        assertFalse(EquipmentTraitCatalog.activeFor(snapshot(owner, 0, 0), owner));
        assertFalse(EquipmentTraitCatalog.activeFor(snapshot(owner, 6, 6), UUID.randomUUID()));
        assertFalse(EquipmentTraitCatalog.activeFor(snapshot(null, 6, 6), owner));
    }

    private static EquipmentSnapshot snapshot(UUID owner, int total, int revealed) {
        return new EquipmentSnapshot(UUID.randomUUID(), UUID.randomUUID(), Optional.ofNullable(owner),
                "EPIC", "WEAPON", "UNIVERSAL", total, revealed, 5000, 2,
                Optional.of("skill-heal"), Map.of());
    }
}
