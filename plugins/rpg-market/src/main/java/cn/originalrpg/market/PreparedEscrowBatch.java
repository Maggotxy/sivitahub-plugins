package cn.originalrpg.market;

import cn.originalrpg.api.model.MarketListing;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** One recovery snapshot protects both scopes before any orphan marker can be removed. */
record PreparedEscrowBatch(List<MarketListing> listings, Set<UUID> communityIds) {
    PreparedEscrowBatch {
        listings = List.copyOf(listings);
        communityIds = Set.copyOf(communityIds);
    }

    static PreparedEscrowBatch combine(List<MarketListing> market, List<MarketListing> community) {
        List<MarketListing> combined = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Set<UUID> custody = new HashSet<>();
        for (MarketListing listing : market) {
            if (listing.state() != MarketListing.State.PREPARED || !seen.add(listing.id())) {
                throw new IllegalStateException("Invalid market preparation recovery snapshot");
            }
            combined.add(listing);
        }
        for (MarketListing listing : community) {
            if (listing.state() != MarketListing.State.PREPARED || !seen.add(listing.id())) {
                throw new IllegalStateException("Ambiguous community/market custody identity");
            }
            custody.add(listing.id());
            combined.add(listing);
        }
        return new PreparedEscrowBatch(combined, custody);
    }
}
