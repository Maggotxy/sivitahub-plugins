package cn.originalrpg.api.model;

import java.util.List;
import java.util.UUID;

/** Bounded projection of custody rows from the one market escrow, never a second inventory authority. */
public record CommunityStorageSnapshot(
        UUID communityId, String name, String tag, int capacity,
        List<MarketListing> supplies, List<CommunityStorageRequest> requests
) {
    public CommunityStorageSnapshot {
        supplies = List.copyOf(supplies);
        requests = List.copyOf(requests);
    }
}
