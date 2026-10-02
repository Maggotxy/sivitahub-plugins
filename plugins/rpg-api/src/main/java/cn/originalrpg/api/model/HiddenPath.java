package cn.originalrpg.api.model;

import java.util.Locale;
import java.util.Optional;

/**
 * A scarce, account-bound build route earned through reproducible gameplay.
 * This deliberately is not an Archetype: a player keeps their base class,
 * branches and ordinary skill tree after accepting a hidden path.
 */
public enum HiddenPath {
    BLOODPACT,
    TALISMAN;

    public static Optional<HiddenPath> parse(String input) {
        if (input == null) return Optional.empty();
        try {
            return Optional.of(valueOf(input.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
