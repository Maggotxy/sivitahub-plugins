package cn.originalrpg.pets;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetProgressRulesTest {
    @Test
    void identityIsStableAndCatalogued() {
        UUID id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        PetProgressRules.Identity first = PetProgressRules.identity(id);
        assertEquals(first, PetProgressRules.identity(id));
        assertTrue(PetProgressRules.PERSONALITIES.contains(first.personalityId()));
        assertTrue(PetProgressRules.TRAITS.contains(first.traitId()));
    }

    @Test
    void evolutionHasTwoLongTermGates() {
        assertEquals(10, PetProgressRules.minimumLevelForNextStage(0));
        assertEquals(250, PetProgressRules.minimumBondForNextStage(0));
        assertEquals("resonant", PetProgressRules.skinForNextStage(0));
        assertEquals(20, PetProgressRules.minimumLevelForNextStage(1));
        assertEquals(750, PetProgressRules.minimumBondForNextStage(1));
        assertEquals("ascendant", PetProgressRules.skinForNextStage(1));
    }

    @Test
    void loyalPersonalityOnlyAcceleratesBondSlightly() {
        assertEquals(2, PetProgressRules.adjustedBond("calm", 2));
        assertEquals(3, PetProgressRules.adjustedBond("loyal", 2));
        assertEquals(12, PetProgressRules.adjustedBond("loyal", 10));
    }
}
