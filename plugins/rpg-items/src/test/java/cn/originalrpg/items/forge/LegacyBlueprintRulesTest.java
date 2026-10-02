package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LegacyBlueprintRulesTest {
    @Test
    void onlyFinishedOwnedTraitEquipmentCanBeSacrificed() {
        UUID owner = UUID.randomUUID();
        EquipmentSnapshot eligible = snapshot(owner, Optional.of("shadow-step"), 6, 6, 2);
        assertEquals(LegacyBlueprintRules.Eligibility.VALID,
                LegacyBlueprintRules.eligibility(eligible, owner, 2));
        assertEquals(LegacyBlueprintRules.Eligibility.OWNER_REQUIRED,
                LegacyBlueprintRules.eligibility(eligible, UUID.randomUUID(), 2));
        assertEquals(LegacyBlueprintRules.Eligibility.RARE_TRAIT_REQUIRED,
                LegacyBlueprintRules.eligibility(snapshot(owner, Optional.empty(), 6, 6, 2), owner, 2));
        assertEquals(LegacyBlueprintRules.Eligibility.FULL_REVELATION_REQUIRED,
                LegacyBlueprintRules.eligibility(snapshot(owner, Optional.of("shadow-step"), 6, 5, 2), owner, 2));
        assertEquals(LegacyBlueprintRules.Eligibility.LIMIT_BREAK_REQUIRED,
                LegacyBlueprintRules.eligibility(snapshot(owner, Optional.of("shadow-step"), 6, 6, 1), owner, 2));
    }

    @Test
    void theRareTraitPrerequisiteIsDeliberateAndDocumented() {
        // G-203 决策：把 RARE_TRAIT_REQUIRED 写进文档，而不是删掉这个前置。
        // 依据：蓝图本身就是"把稀有特性传给下一件装备"的载体，没有稀有特性的装备
        // 提取蓝图后产不出任何有意义的东西；文案层（items.legacy.legacy-lore /
        // trait-required）也一直把它当作玩家可见规则。所以它是设计意图，只是文档漏写。
        UUID owner = UUID.randomUUID();
        // 判定顺序固定：物品 -> 归属 -> 稀有特性 -> 全揭示 -> 突破等级。
        assertEquals(LegacyBlueprintRules.Eligibility.ITEM_REQUIRED,
                LegacyBlueprintRules.eligibility(null, owner, 2));
        assertEquals(LegacyBlueprintRules.Eligibility.OWNER_REQUIRED,
                LegacyBlueprintRules.eligibility(snapshot(owner, Optional.of("shadow-step"), 6, 6, 2),
                        UUID.randomUUID(), 2));
        assertEquals(LegacyBlueprintRules.Eligibility.RARE_TRAIT_REQUIRED,
                LegacyBlueprintRules.eligibility(snapshot(owner, Optional.empty(), 6, 6, 2), owner, 2));
        // 没有稀有特性时，即使其他条件都不满足，也先报这一条（顺序可预期）。
        assertEquals(LegacyBlueprintRules.Eligibility.RARE_TRAIT_REQUIRED,
                LegacyBlueprintRules.eligibility(snapshot(owner, Optional.empty(), 3, 0, 0), owner, 2));
    }

    @Test
    void blueprintCannotCrossTheWeaponArmorBoundary() {
        LegacyBlueprint blueprint = new LegacyBlueprint(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "shadow-step", "WEAPON", "ASSASSIN");
        assertTrue(LegacyBlueprintRules.compatible(blueprint, "WEAPON"));
        assertFalse(LegacyBlueprintRules.compatible(blueprint, "ARMOR"));
    }

    private static EquipmentSnapshot snapshot(
            UUID owner, Optional<String> trait, int total, int revealed, int limit
    ) {
        return new EquipmentSnapshot(UUID.randomUUID(), UUID.randomUUID(), Optional.of(owner), "EPIC", "WEAPON",
                "ASSASSIN", total, revealed, 5000, limit, trait, Map.of());
    }
}
