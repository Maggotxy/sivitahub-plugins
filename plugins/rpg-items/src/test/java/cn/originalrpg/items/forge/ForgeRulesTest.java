package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeRulesTest {
    @Test
    void vanillaMaterialTierRaisesForgeCeiling() {
        assertEquals(0, ForgeRules.baseQuality("WOODEN_SWORD"));
        assertEquals(2, ForgeRules.baseQuality("IRON_SWORD"));
        assertEquals(3, ForgeRules.baseQuality("DIAMOND_SWORD"));
        assertEquals(4, ForgeRules.baseQuality("NETHERITE_SWORD"));
        assertEquals(3, ForgeRules.slotUpperBound("COMMON", "WOODEN_SWORD"));
        assertEquals(7, ForgeRules.slotUpperBound("COMMON", "NETHERITE_SWORD"));
    }

    @Test
    void qualityAndCatalystImproveButNeverBypassMaximumSuccess() {
        double wood = ForgeRules.successChance("WOODEN_SWORD", 0, 0.65, 0.95);
        double netherite = ForgeRules.successChance("NETHERITE_SWORD", 0, 0.65, 0.95);
        assertTrue(netherite > wood);
        assertEquals(0.95, ForgeRules.successChance("NETHERITE_SWORD", 99, 0.65, 0.95));
    }

    @Test
    void allSlotBoundsRemainInsideSignedSchemaLimit() {
        for (String rarity : new String[]{"COMMON", "RARE", "EPIC", "LEGENDARY"}) {
            int lower = ForgeRules.slotLowerBound(rarity);
            int upper = ForgeRules.slotUpperBound(rarity, "NETHERITE_SWORD");
            assertTrue(lower >= 0 && lower <= upper && upper <= 8);
        }
    }
}
