package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SeasonExpeditionLeaderboardEntry(
        int rank,
        UUID runId,
        long durationMillis,
        List<UUID> memberIds,
        Instant completedAt,
        boolean serverFirst
) {
    public SeasonExpeditionLeaderboardEntry {
        Objects.requireNonNull(runId, "runId");
        memberIds = List.copyOf(Objects.requireNonNull(memberIds, "memberIds"));
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
