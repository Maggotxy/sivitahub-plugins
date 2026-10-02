package cn.originalrpg.api.model;

import java.util.Locale;
import java.util.Optional;

/** Stable identifiers for the one-time journey that teaches the real RPG loop. */
public enum JourneyStep {
    HUB,
    CLASS,
    ATTRIBUTE,
    EXPLORE,
    HUNT,
    FORGE,
    REVEAL,
    DUNGEON;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<JourneyStep> parse(String input) {
        if (input == null) return Optional.empty();
        try {
            return Optional.of(valueOf(input.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
