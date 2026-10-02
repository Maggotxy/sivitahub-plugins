package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Immutable server-owned progression attached to one MyPet UUID. */
public record PetProgress(
        UUID petId,
        UUID ownerId,
        String speciesId,
        String personalityId,
        String traitId,
        int bond,
        int evolutionStage,
        String selectedSkinId,
        long battles,
        Set<String> unlockedSkinIds,
        Instant createdAt,
        Instant updatedAt
) {
    public PetProgress {
        unlockedSkinIds = Set.copyOf(unlockedSkinIds);
    }
}
