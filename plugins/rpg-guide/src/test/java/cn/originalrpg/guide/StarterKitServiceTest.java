package cn.originalrpg.guide;

import cn.originalrpg.api.model.Archetype;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StarterKitServiceTest {
    @Test
    void everyPlayableClassHasAnExplicitForgeableBase() {
        Map<Archetype, Material> expected = Map.of(
                Archetype.HEALER, Material.BREEZE_ROD,
                Archetype.WARRIOR, Material.STONE_AXE,
                Archetype.ASSASSIN, Material.STONE_SWORD,
                Archetype.MAGE, Material.BLAZE_ROD,
                Archetype.RANGER, Material.BOW,
                Archetype.FREEFORM, Material.WOODEN_SWORD);
        expected.forEach((archetype, material) -> assertEquals(
                material, StarterKitService.baseMaterial(archetype)));
        assertThrows(IllegalArgumentException.class,
                () -> StarterKitService.baseMaterial(Archetype.UNCHOSEN));
    }

    @Test
    void claimToolIsPlainGriefPreventionMaterialNotASecondClaimToken() {
        assertEquals(Material.GOLDEN_SHOVEL, Material.matchMaterial("GOLDEN_SHOVEL"));
    }
}
