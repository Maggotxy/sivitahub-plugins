package cn.originalrpg.api.model;

import java.time.Instant;

/** One species row in a player's permanent companion codex. */
public record PetCollectionEntry(
        String speciesId,
        int captureCount,
        int highestEvolutionStage,
        Instant firstCapturedAt,
        Instant lastCapturedAt
) {
}
