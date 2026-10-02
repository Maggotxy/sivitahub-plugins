package cn.originalrpg.items.forge;

import java.util.List;

/** Pure, monotonic rules for durable equipment-slot revelation. */
final class EquipmentRevelationRules {
    private static final int MAXIMUM_SLOTS = 8;

    private EquipmentRevelationRules() {
    }

    /**
     * Equipment progress is permanent. Configuration changes may make later
     * slots harder to reveal, but they must never close a slot that was
     * already persisted and signed. Invalid legacy values are bounded by the
     * physical slot count instead of being allowed to escape the schema.
     */
    static int afterExperienceGain(
            int previouslyRevealed,
            int experience,
            int totalSlots,
            List<Integer> thresholds
    ) {
        int boundedTotal = Math.clamp(totalSlots, 0, MAXIMUM_SLOTS);
        int preserved = Math.clamp(previouslyRevealed, 0, boundedTotal);
        return Math.max(preserved, derivedFromExperience(experience, boundedTotal, thresholds));
    }

    private static int derivedFromExperience(int experience, int totalSlots, List<Integer> thresholds) {
        int revealed = 0;
        int boundedExperience = Math.max(0, experience);
        for (int threshold : thresholds) {
            if (revealed >= totalSlots || boundedExperience < Math.max(0, threshold)) break;
            revealed++;
        }
        return revealed;
    }
}
