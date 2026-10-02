package cn.originalrpg.enhancement;

import java.util.List;

/** Pure refinement policy shared by the menu and its contract tests. */
final class RefinementRules {
    static final List<Integer> SLOT_BUTTONS = List.of(10, 11, 12, 13, 14, 15, 16, 17);

    private RefinementRules() {
    }

    static double successRate(double baseRate, double luckyBonus, boolean lucky) {
        return Math.clamp(baseRate + (lucky ? luckyBonus : 0.0), 0.0, 1.0);
    }

    static int slotIndexForButton(int rawSlot, int revealedSlots) {
        int index = SLOT_BUTTONS.indexOf(rawSlot);
        return index >= 0 && index < Math.clamp(revealedSlots, 0, SLOT_BUTTONS.size()) ? index : -1;
    }

    static boolean validSelection(int selectedSlot, int revealedSlots) {
        return selectedSlot >= 0 && selectedSlot < Math.clamp(revealedSlots, 0, SLOT_BUTTONS.size());
    }

    static void validateRollRange(int minimum, int maximum) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("refinement.minimum-roll must not exceed maximum-roll");
        }
    }
}
