package cn.originalrpg.guide;

/** Completion rules for the equipment-slot teaching step. */
final class EquipmentJourneyPolicy {
    private EquipmentJourneyPolicy() {
    }

    /**
     * A zero-slot roll is a legitimate tradeable blank, not a broken item.
     * It confirms the slot outcome immediately; equipment with slots must
     * still reveal at least one through real use.
     */
    static boolean slotOutcomeConfirmed(Integer totalSlots, int revealedSlots) {
        if (revealedSlots > 0) return true;
        return totalSlots != null && totalSlots == 0;
    }
}
