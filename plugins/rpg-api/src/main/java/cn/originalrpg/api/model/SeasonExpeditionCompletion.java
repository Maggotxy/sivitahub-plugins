package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable evidence for one completed seasonal expedition. The run id is the
 * settlement idempotency key; callers must reuse it when retrying the same run.
 */
public record SeasonExpeditionCompletion(
        UUID runId,
        String seasonId,
        String rotationId,
        String dungeonId,
        int partySize,
        long durationMillis,
        List<UUID> participantIds,
        Instant completedAt
) {
    public SeasonExpeditionCompletion {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(seasonId, "seasonId");
        Objects.requireNonNull(rotationId, "rotationId");
        Objects.requireNonNull(dungeonId, "dungeonId");
        Objects.requireNonNull(participantIds, "participantIds");
        Objects.requireNonNull(completedAt, "completedAt");
        participantIds = List.copyOf(participantIds);
    }
}
