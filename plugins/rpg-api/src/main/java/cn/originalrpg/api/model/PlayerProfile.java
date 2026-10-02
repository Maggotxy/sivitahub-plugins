package cn.originalrpg.api.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PlayerProfile(
        UUID playerId,
        Archetype archetype,
        int ascension,
        int level,
        long experience,
        int energy,
        int energyCap,
        int unspentAttributePoints,
        int skillPoints,
        Map<CoreAttribute, Integer> attributes
) {
    public PlayerProfile {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(archetype, "archetype");
        Objects.requireNonNull(attributes, "attributes");
        attributes = Map.copyOf(new EnumMap<>(attributes));
        if (ascension < 0 || level < 1 || experience < 0 || energy < 0 || energy > energyCap) {
            throw new IllegalArgumentException("Invalid profile progression values");
        }
    }

    /** Local level is stored in {@code level} (1..30); effective is asc*30+local. */
    public int effectiveLevel() {
        return cn.originalrpg.api.rules.ProgressionRules.effectiveLevel(ascension, level);
    }

    /** Explicit local level accessor for audits (same as {@code level()}). */
    public int localLevel() {
        return level;
    }

    public int attribute(CoreAttribute attribute) {
        return attributes.getOrDefault(attribute, 0);
    }
}

