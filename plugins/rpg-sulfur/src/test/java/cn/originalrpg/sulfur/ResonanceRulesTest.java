package cn.originalrpg.sulfur;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResonanceRulesTest {
    @Test
    void mapsNewAndVanillaMaterialsToDistinctBuildDirections() {
        assertEquals("skill-haste", ResonanceRules.classify("POTENT_SULFUR").orElseThrow().bias());
        assertEquals(3, ResonanceRules.classify("POTENT_SULFUR").orElseThrow().strength());
        assertEquals("critical", ResonanceRules.classify("POLISHED_CINNABAR").orElseThrow().bias());
        assertEquals("healing", ResonanceRules.classify("HONEY_BLOCK").orElseThrow().bias());
        assertEquals("stamina", ResonanceRules.classify("MOSS_BLOCK").orElseThrow().bias());
        assertEquals("agility", ResonanceRules.classify("OAK_LOG").orElseThrow().bias());
        assertEquals("defense", ResonanceRules.classify("DEEPSLATE").orElseThrow().bias());
        assertTrue(ResonanceRules.classify("DIRT").isEmpty());
        assertTrue(ResonanceRules.classify("IRON_INGOT").isEmpty());
        assertTrue(ResonanceRules.classify("GOLDEN_APPLE").isEmpty());
        assertTrue(ResonanceRules.classify("HONEY_BOTTLE").isEmpty());
        assertTrue(ResonanceRules.classify("STONE_SWORD").isEmpty());
    }

    @Test
    void strengthChangesWeightWithoutGuaranteeingTheRoll() {
        assertEquals(250.0 / 950.0, ResonanceRules.biasedChance(2), 0.000001);
        assertEquals(325.0 / 1025.0, ResonanceRules.biasedChance(3), 0.000001);
    }
}
