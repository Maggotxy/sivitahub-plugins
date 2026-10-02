package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

public record SeasonExpeditionProfile(
        UUID playerId,
        String seasonId,
        long honor,
        int collectionStamps,
        int completedRuns,
        boolean currentRotationClaimed,
        OptionalLong personalBestMillis
) {
    public SeasonExpeditionProfile {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(seasonId, "seasonId");
        Objects.requireNonNull(personalBestMillis, "personalBestMillis");
    }
}
