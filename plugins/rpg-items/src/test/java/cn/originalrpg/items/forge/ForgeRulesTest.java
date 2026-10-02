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
    void rareAndEpicSlotCeilingsRiseWithTheBaseMaterial() {
        // G-202 行为级验收：文档曾写成固定区间，实际按 design intent 随基材上浮。
        // 公式：普通 min(8,3+品质等级)、稀有 min(8,5+品质等级/2)、史诗 min(8,7+品质等级/3)。
        assertEquals(5, ForgeRules.slotUpperBound("RARE", "WOODEN_SWORD"));
        assertEquals(5, ForgeRules.slotUpperBound("RARE", "STONE_SWORD"));
        assertEquals(6, ForgeRules.slotUpperBound("RARE", "IRON_SWORD"));
        assertEquals(6, ForgeRules.slotUpperBound("RARE", "DIAMOND_SWORD"));
        assertEquals(7, ForgeRules.slotUpperBound("RARE", "NETHERITE_SWORD"));

        assertEquals(7, ForgeRules.slotUpperBound("EPIC", "WOODEN_SWORD"));
        assertEquals(7, ForgeRules.slotUpperBound("EPIC", "IRON_SWORD"));
        assertEquals(8, ForgeRules.slotUpperBound("EPIC", "DIAMOND_SWORD"));
        assertEquals(8, ForgeRules.slotUpperBound("EPIC", "NETHERITE_SWORD"));

        // 传说固定 8；下限只由品质决定，与基材无关。
        assertEquals(8, ForgeRules.slotUpperBound("LEGENDARY", "WOODEN_SWORD"));
        assertEquals(6, ForgeRules.slotLowerBound("LEGENDARY"));
        assertEquals(4, ForgeRules.slotLowerBound("EPIC"));
        assertEquals(2, ForgeRules.slotLowerBound("RARE"));
        assertEquals(0, ForgeRules.slotLowerBound("COMMON"));
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
