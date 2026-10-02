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
