package cn.originalrpg.market;

/**
 * Direction-aware protection for high-value RPG items in third-party or
 * vanilla container screens.
 *
 * <p>A protected item must not enter an external container or be dropped from
 * one. Recovery in the opposite direction is always allowed so an item placed
 * there by an older build can never become permanently trapped.</p>
 */
final class ContainerTransferPolicy {
    private ContainerTransferPolicy() {
    }

    static Decision decide(
            int rawSlot,
            int topSize,
            String action,
            boolean shiftClick,
            boolean swapOffhand,
            boolean restrictedCurrent,
            boolean restrictedCursor,
            boolean restrictedHotbar,
            boolean restrictedOffhand
    ) {
        if (rawSlot < 0) {
            return restrictedCursor ? Decision.BLOCK_DROP : Decision.ALLOW;
        }
        boolean clickedTop = rawSlot < topSize;
        if (!clickedTop) {
            return shiftClick && restrictedCurrent ? Decision.BLOCK_INSERT : Decision.ALLOW;
        }
        return switch (action) {
            // The protected stack only travels from the external inventory
            // back to the player. This is the recovery path.
            case "PICKUP_ALL", "PICKUP_HALF", "PICKUP_ONE", "PICKUP_SOME",
                    "MOVE_TO_OTHER_INVENTORY", "COLLECT_TO_CURSOR", "PICKUP_FROM_BUNDLE" -> Decision.ALLOW;
            // The cursor enters the external slot. If the slot currently holds
            // a protected item, a normal cursor may still swap it back out.
            case "PLACE_ALL", "PLACE_ONE", "PLACE_SOME", "SWAP_WITH_CURSOR" ->
                    restrictedCursor ? Decision.BLOCK_INSERT : Decision.ALLOW;
            case "PICKUP_ALL_INTO_BUNDLE", "PICKUP_SOME_INTO_BUNDLE",
                    "PLACE_FROM_BUNDLE", "PLACE_ALL_INTO_BUNDLE", "PLACE_SOME_INTO_BUNDLE" ->
                    restrictedCurrent || restrictedCursor ? Decision.BLOCK_INSERT : Decision.ALLOW;
            // Number-key/offhand swaps also recover the current top item while
            // inserting the selected player item.
            case "HOTBAR_SWAP", "HOTBAR_MOVE_AND_READD" ->
                    (swapOffhand ? restrictedOffhand : restrictedHotbar)
                            ? Decision.BLOCK_INSERT : Decision.ALLOW;
            case "DROP_ALL_SLOT", "DROP_ONE_SLOT" ->
                    restrictedCurrent ? Decision.BLOCK_DROP : Decision.ALLOW;
            case "CLONE_STACK", "NOTHING" -> Decision.ALLOW;
            default -> restrictedCurrent || restrictedCursor || restrictedHotbar || restrictedOffhand
                    ? Decision.BLOCK_UNKNOWN : Decision.ALLOW;
        };
    }

    enum Decision {
        ALLOW,
        BLOCK_INSERT,
        BLOCK_DROP,
        BLOCK_UNKNOWN;

        boolean blocked() {
            return this != ALLOW;
        }
    }
}
