package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.UUID;

public record SeasonExpeditionPlayerResult(
        UUID playerId,
        int honorGranted,
        long totalHonor,
        int collectionStamps,
        int completedRuns,
        boolean weeklyClaimedNow,
        boolean newPersonalBest,
        long personalBestMillis
) {
    public SeasonExpeditionPlayerResult {
        Objects.requireNonNull(playerId, "playerId");
    }
}
