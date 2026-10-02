package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentLoreProjectionTest {
    @Test
    void projectsRefinementAndLimitBreakFromPersistentValuesOnEveryRebuild() {
        List<String> stored = List.of("critical:12", "intelligence:-5", "skill-haste:7", "", "", "", "", "");
        for (int rebuild = 0; rebuild < 20; rebuild++) {
            EquipmentLoreProjection projection = EquipmentLoreProjection.project(
                    6, 3, 140 + rebuild, 4, true, false, stored);
            assertEquals(6, projection.totalSlots());
            assertEquals(3, projection.revealedSlots());
            assertEquals(4, projection.limitBreakLevel());
            assertEquals("+12", projection.refinedSlots().get(0).orElseThrow().signedValue());
            assertEquals("-5", projection.refinedSlots().get(1).orElseThrow().signedValue());
            assertEquals("+7", projection.refinedSlots().get(2).orElseThrow().signedValue());
            assertTrue(projection.refinedSlots().get(3).isEmpty());
        }
    }

    @Test
    void clampsCorruptCountersAndKeepsOwnershipStatesMutuallyExclusive() {
        EquipmentLoreProjection bound = EquipmentLoreProjection.project(
                20, 20, -4, -9, true, true, List.of());
        assertEquals(8, bound.totalSlots());
        assertEquals(8, bound.revealedSlots());
        assertEquals(0, bound.experience());
        assertEquals(0, bound.limitBreakLevel());
        assertEquals(EquipmentLoreProjection.OwnershipState.BOUND, bound.ownership());

        EquipmentLoreProjection unlocked = EquipmentLoreProjection.project(
                2, 1, 0, 1, false, true, List.of("strength:3"));
        assertEquals(EquipmentLoreProjection.OwnershipState.TRADE_UNLOCKED, unlocked.ownership());

        EquipmentLoreProjection unbound = EquipmentLoreProjection.project(
                2, 1, 0, 1, false, false, List.of("strength:3"));
        assertEquals(EquipmentLoreProjection.OwnershipState.UNBOUND, unbound.ownership());
    }
}
