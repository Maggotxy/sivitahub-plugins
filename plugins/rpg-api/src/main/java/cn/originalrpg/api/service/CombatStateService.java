package cn.originalrpg.api.service;

import java.util.UUID;

/** Shared combat-escape boundary for navigation, homes, dungeons, and future shards. */
public interface CombatStateService {
    boolean inCombat(UUID playerId);

    long remainingSeconds(UUID playerId);

    void markCombat(UUID playerId);

    void clear(UUID playerId);
}
