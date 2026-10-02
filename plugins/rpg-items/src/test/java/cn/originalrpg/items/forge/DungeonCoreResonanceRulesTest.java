package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonCoreResonanceRulesTest {
    private static final Set<String> SIX_SHIPPED_CORES = Set.of(
            "ancient-ruins", "ancient-ruins-hidden",
            "sulfur-depths", "sulfur-depths-hidden",
            "storm-vault", "storm-vault-hidden");

    @Test
    void allSixDungeonRewardIdentitiesHaveRuntimeConsumers() {
        assertEquals(SIX_SHIPPED_CORES, DungeonCoreResonanceRules.ids());
    }

    @Test
    void hiddenCoresGuaranteeAtLeastRareWithoutBreakingSchemaBounds() {
        for (String id : SIX_SHIPPED_CORES) {
            var resonance = DungeonCoreResonanceRules.inspect(id).orElseThrow();
            String rarity = DungeonCoreResonanceRules.enforceMinimumRarity("COMMON", resonance);
            int minimum = DungeonCoreResonanceRules.minimumSlots(rarity, resonance);
            int maximum = ForgeRules.slotUpperBound(rarity, "NETHERITE_SWORD");
            assertTrue(minimum >= 0 && minimum <= maximum && maximum <= 8, id);
            if (resonance.hidden()) assertTrue(!rarity.equals("COMMON"), id);
        }
    }

    @Test
    void everyProbabilityHasAHardUpperBound() {
        for (String id : SIX_SHIPPED_CORES) {
            var resonance = DungeonCoreResonanceRules.inspect(id).orElseThrow();
            assertTrue(DungeonCoreResonanceRules.successChance(0.95, 0.95, resonance) <= 0.95, id);
            assertTrue(DungeonCoreResonanceRules.rarityLuck(0.22, resonance) <= 0.30, id);
            assertTrue(DungeonCoreResonanceRules.rareTraitChance(0.10, resonance) <= 0.10, id);
            assertTrue(DungeonCoreResonanceRules.inheritanceChance(0.50, resonance) <= 0.50, id);
        }
    }

    @Test
    void familiesCreateDifferentBuildValue() {
        var ruins = DungeonCoreResonanceRules.inspect("ancient-ruins-hidden").orElseThrow();
        var sulfur = DungeonCoreResonanceRules.inspect("sulfur-depths-hidden").orElseThrow();
        var storm = DungeonCoreResonanceRules.inspect("storm-vault-hidden").orElseThrow();
        assertTrue(ruins.rareTraitBonus() > sulfur.rareTraitBonus());
        assertTrue(sulfur.minimumSlotBonus() > ruins.minimumSlotBonus());
        assertTrue(storm.rarityLuckBonus() > sulfur.rarityLuckBonus());
    }
}
