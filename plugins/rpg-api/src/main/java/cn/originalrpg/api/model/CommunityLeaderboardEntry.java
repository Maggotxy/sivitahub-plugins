package cn.originalrpg.api.model;

import java.util.Objects;

public record CommunityLeaderboardEntry(int rank, CommunitySummary community) {
    public CommunityLeaderboardEntry {
        Objects.requireNonNull(community, "community");
        if (rank < 1) throw new IllegalArgumentException("Rank must be positive");
    }
}
