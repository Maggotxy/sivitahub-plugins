package cn.originalrpg.api.model;

import java.util.Locale;
import java.util.Optional;

public enum Archetype {
    UNCHOSEN,
    HEALER,
    WARRIOR,
    ASSASSIN,
    MAGE,
    RANGER,
    FREEFORM;

    public static Optional<Archetype> parse(String input) {
        String normalized = input.trim().toUpperCase(Locale.ROOT);
        for (Archetype archetype : values()) {
            if (archetype.name().equals(normalized)) {
                return Optional.of(archetype);
            }
        }
        return Optional.empty();
    }
}
