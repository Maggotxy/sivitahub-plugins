package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerTransferPolicyTest {
    private static final int TOP_SIZE = 54;

    @Test
    void protectedDungeonCoreCanAlwaysLeaveAnExternalBackpack() {
        assertAllowed(top("PICKUP_ALL", true, false, false, false));
        assertAllowed(top("PICKUP_HALF", true, false, false, false));
        assertAllowed(top("MOVE_TO_OTHER_INVENTORY", true, false, false, false));
        assertAllowed(top("SWAP_WITH_CURSOR", true, false, false, false));
        assertAllowed(topHotbar("HOTBAR_SWAP", true, false, false));
    }

    @Test
    void protectedItemsStillCannotEnterExternalBackpacks() {
        assertBlocked(top("PLACE_ALL", false, true, false, false));
        assertBlocked(top("SWAP_WITH_CURSOR", false, true, false, false));
        assertBlocked(topHotbar("HOTBAR_SWAP", false, true, false));
        assertBlocked(topOffhand(true));
        assertBlocked(decide(60, "MOVE_TO_OTHER_INVENTORY", true,
                false, true, false, false, false));
    }

    @Test
    void protectedItemsCannotBeDroppedStraightFromAnExternalBackpack() {
        ContainerTransferPolicy.Decision decision = top(
                "DROP_ALL_SLOT", true, false, false, false);
        assertEquals(ContainerTransferPolicy.Decision.BLOCK_DROP, decision);
        assertBlocked(decision);
    }

    @Test
    void ordinaryItemsRemainUnaffected() {
        assertAllowed(top("PLACE_ALL", false, false, false, false));
        assertAllowed(decide(60, "MOVE_TO_OTHER_INVENTORY", true,
                false, false, false, false, false));
    }

    @Test
    void bundleActionsHaveAnExplicitDirectionAwarePolicy() {
        assertAllowed(top("PICKUP_FROM_BUNDLE", true, false, false, false));
        assertBlocked(top("PICKUP_ALL_INTO_BUNDLE", true, false, false, false));
        assertBlocked(top("PLACE_FROM_BUNDLE", false, true, false, false));
        assertBlocked(top("PLACE_ALL_INTO_BUNDLE", true, false, false, false));
    }

    private static ContainerTransferPolicy.Decision top(
            String action, boolean current, boolean cursor, boolean hotbar, boolean offhand
    ) {
        return decide(10, action, action.equals("MOVE_TO_OTHER_INVENTORY"),
                false, current, cursor, hotbar, offhand);
    }

    private static ContainerTransferPolicy.Decision topHotbar(
            String action, boolean current, boolean hotbar, boolean offhand
    ) {
        return decide(10, action, false, false, current, false, hotbar, offhand);
    }

    private static ContainerTransferPolicy.Decision topOffhand(boolean restrictedOffhand) {
        return decide(10, "HOTBAR_SWAP", false, true,
                true, false, false, restrictedOffhand);
    }

    private static ContainerTransferPolicy.Decision decide(
            int rawSlot, String action, boolean shiftClick, boolean swapOffhand,
            boolean current, boolean cursor, boolean hotbar, boolean offhand
    ) {
        return ContainerTransferPolicy.decide(rawSlot, TOP_SIZE, action, shiftClick, swapOffhand,
                current, cursor, hotbar, offhand);
    }

    private static void assertAllowed(ContainerTransferPolicy.Decision decision) {
        assertFalse(decision.blocked(), decision.name());
    }

    private static void assertBlocked(ContainerTransferPolicy.Decision decision) {
        assertTrue(decision.blocked(), decision.name());
    }
}
