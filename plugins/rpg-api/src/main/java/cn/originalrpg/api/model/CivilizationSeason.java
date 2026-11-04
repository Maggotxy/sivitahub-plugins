package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A completed, verified season settlement visible in civilization history. */
public record CivilizationSeason(UUID settlementId, String seasonId, Instant completedAt) {
    public CivilizationSeason {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(seasonId, "seasonId");
        Objects.requireNonNull(completedAt, "completedAt");
    }
}
