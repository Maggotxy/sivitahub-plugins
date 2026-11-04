package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.MarketGoods;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class CommunityStorageRulesTest {
    @Test
    void everyAllowedConstructionSupplyHasTheExactPinnedPaperMaterialKey() {
        assertEquals(25, CommunityStorageRules.materials().size());
        for (String matcher : CommunityStorageRules.materials()) {
            Material material = Material.valueOf(matcher.substring("minecraft:".length()).toUpperCase(Locale.ROOT));
            assertEquals(matcher, material.getKey().toString());
            assertTrue(CommunityStorageRules.accepts(new MarketGoods(matcher, 64, null)), matcher);
        }
        assertThrows(UnsupportedOperationException.class,
                () -> CommunityStorageRules.materials().add("minecraft:diamond_block"));
    }

    @Test
    void rareMaterialsAndSignedEquipmentCannotUseTheUntaxedDonationScope() {
        assertFalse(CommunityStorageRules.accepts(null));
        for (String matcher : new String[]{"minecraft:diamond", "minecraft:diamond_block", "minecraft:iron_block",
                "minecraft:netherite_block", "minecraft:gold_ingot", "minecraft:chest", "minecraft:shulker_box"}) {
            assertFalse(CommunityStorageRules.accepts(new MarketGoods(matcher, 1, null)), matcher);
        }
        assertFalse(CommunityStorageRules.accepts(new MarketGoods("equipment:sword:rare", 1, UUID.randomUUID())));
    }
}
