package cn.originalrpg.api.service;

import java.util.UUID;

/**
 * Main-thread relationship boundary for short-lived cooperative groups.
 *
 * <p>This service deliberately exposes only a relationship decision. Skill
 * plugins must not depend on a dungeon plugin's mutable party collection, and
 * callers must fail closed when no provider is installed.</p>
 */
public interface PartyService {
    /** Returns true only while both players belong to the same cooperative group. */
    boolean areAllies(UUID first, UUID second);
}
