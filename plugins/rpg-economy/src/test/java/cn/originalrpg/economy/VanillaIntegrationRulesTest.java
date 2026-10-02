package cn.originalrpg.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaIntegrationRulesTest {
    @Test
    void placedOrSilkTouchedOreCannotProduceResonanceWithoutNaturalExperience() {
        assertFalse(VanillaIntegrationRules.isNaturalResonantOre("DIAMOND_ORE", 0, false));
        assertFalse(VanillaIntegrationRules.isNaturalResonantOre("DIAMOND_ORE", 5, true));
        assertTrue(VanillaIntegrationRules.isNaturalResonantOre("DIAMOND_ORE", 5, false));
        assertFalse(VanillaIntegrationRules.isNaturalResonantOre("IRON_ORE", 5, false));
    }

    @Test
    void dailyCapTruncatesAndNewDateResets() {
        assertEquals(2, VanillaIntegrationRules.grantableEssence("2026-08-06", "2026-08-06", 10, 12, 4));
        assertEquals(0, VanillaIntegrationRules.grantableEssence("2026-08-06", "2026-08-06", 12, 12, 1));
        assertEquals(4, VanillaIntegrationRules.grantableEssence("2026-08-07", "2026-08-06", 12, 12, 4));
    }
}
