package cn.originalrpg.items.forge;

import java.util.Optional;
import java.util.Set;

/**
 * The signed PDC representation of one refined equipment slot.
 *
 * <p>Lore is a projection only. It must always be rebuilt from this value and
 * must never be used as a persistence source.</p>
 */
record RefinedSlotValue(String stat, int value) {
    private static final Set<String> SUPPORTED_STATS = Set.of(
            "strength", "defense", "agility", "intelligence",
            "stamina", "critical", "healing", "skill-haste"
    );

    static Optional<RefinedSlotValue> parse(String encoded) {
        if (encoded == null || encoded.isBlank()) return Optional.empty();
        int separator = encoded.lastIndexOf(':');
        if (separator <= 0 || separator == encoded.length() - 1) return Optional.empty();
        String stat = encoded.substring(0, separator);
        if (!SUPPORTED_STATS.contains(stat)) return Optional.empty();
        try {
            return Optional.of(new RefinedSlotValue(
                    stat, Integer.parseInt(encoded.substring(separator + 1))));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    String signedValue() {
        return value >= 0 ? "+" + value : Integer.toString(value);
    }
}
