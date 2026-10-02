package cn.originalrpg.api.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class EquipmentBaseRulesTest {
    @Test
    void everyForgeBaseHasImmediateClassAppropriateValue() {
        assertEquals(2, EquipmentBaseRules.power("BREEZE_ROD", "COMMON", "WEAPON"));
        assertEquals(5, EquipmentBaseRules.power("DIAMOND_SWORD", "RARE", "WEAPON"));
        assertEquals(8, EquipmentBaseRules.power("NETHERITE_AXE", "LEGENDARY", "WEAPON"));
        assertEquals(0, EquipmentBaseRules.power("IRON_CHESTPLATE", "RARE", "ARMOR"));
        assertEquals(4, EquipmentBaseRules.guard("IRON_CHESTPLATE", "RARE", "ARMOR"));
        assertEquals(0, EquipmentBaseRules.guard("STONE_SWORD", "COMMON", "WEAPON"));
    }
}
