package cn.originalrpg.items.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Deterministic, player-facing projection of signed equipment progression data. */
record EquipmentLoreProjection(
        int totalSlots,
        int revealedSlots,
        int experience,
        int limitBreakLevel,
        List<Optional<RefinedSlotValue>> refinedSlots,
        OwnershipState ownership
) {
    enum OwnershipState {
        BOUND,
        TRADE_UNLOCKED,
        UNBOUND
    }

    static EquipmentLoreProjection project(
            int rawTotalSlots,
            int rawRevealedSlots,
            int rawExperience,
            int rawLimitBreakLevel,
            boolean hasOwner,
            boolean tradeUnlocked,
            List<String> encodedSlots
    ) {
        int total = Math.clamp(rawTotalSlots, 0, 8);
        int revealed = Math.clamp(rawRevealedSlots, 0, total);
        ArrayList<Optional<RefinedSlotValue>> slots = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            String encoded = encodedSlots != null && index < encodedSlots.size()
                    ? encodedSlots.get(index) : null;
            slots.add(index < revealed ? RefinedSlotValue.parse(encoded) : Optional.empty());
        }
        OwnershipState ownership = hasOwner ? OwnershipState.BOUND
                : tradeUnlocked ? OwnershipState.TRADE_UNLOCKED : OwnershipState.UNBOUND;
        return new EquipmentLoreProjection(
                total,
                revealed,
                Math.max(0, rawExperience),
                Math.max(0, rawLimitBreakLevel),
                List.copyOf(slots),
                ownership);
    }
}
