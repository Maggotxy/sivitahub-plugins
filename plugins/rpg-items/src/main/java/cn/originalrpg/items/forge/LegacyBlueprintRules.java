package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentSnapshot;

import java.util.UUID;

final class LegacyBlueprintRules {
    private LegacyBlueprintRules() {
    }

    enum Eligibility {
        VALID,
        ITEM_REQUIRED,
        OWNER_REQUIRED,
        RARE_TRAIT_REQUIRED,
        FULL_REVELATION_REQUIRED,
        LIMIT_BREAK_REQUIRED
    }

    static Eligibility eligibility(EquipmentSnapshot snapshot, UUID playerId, int minimumLimitBreak) {
        if (snapshot == null || playerId == null) return Eligibility.ITEM_REQUIRED;
        if (snapshot.boundOwner().isEmpty() || !snapshot.boundOwner().orElseThrow().equals(playerId)) {
            return Eligibility.OWNER_REQUIRED;
        }
        if (snapshot.rareTrait().isEmpty()) return Eligibility.RARE_TRAIT_REQUIRED;
        if (snapshot.revealedSlots() < snapshot.totalSlots()) return Eligibility.FULL_REVELATION_REQUIRED;
        if (snapshot.limitBreakLevel() < Math.max(0, minimumLimitBreak)) return Eligibility.LIMIT_BREAK_REQUIRED;
        return Eligibility.VALID;
    }

    static boolean compatible(LegacyBlueprint blueprint, String equipmentType) {
        return blueprint != null && equipmentType != null && blueprint.equipmentType().equals(equipmentType);
    }
}
