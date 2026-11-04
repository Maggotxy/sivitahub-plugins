package cn.originalrpg.market;

import cn.originalrpg.api.model.MarketListing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class PreparedEscrowBatchTest {
    @Test
    void recoverySeesBothScopesBeforeItCanDecideWhichMarkersAreOrphans() {
        MarketListing ordinary = listing(UUID.randomUUID(), MarketListing.State.PREPARED);
        MarketListing custody = listing(UUID.randomUUID(), MarketListing.State.PREPARED);
        List<MarketListing> ordinaryInput = new ArrayList<>(List.of(ordinary));
        List<MarketListing> custodyInput = new ArrayList<>(List.of(custody));
        PreparedEscrowBatch batch = PreparedEscrowBatch.combine(ordinaryInput, custodyInput);
        ordinaryInput.clear(); custodyInput.clear();
        assertEquals(List.of(ordinary, custody), batch.listings());
        assertEquals(java.util.Set.of(custody.id()), batch.communityIds());
        assertThrows(UnsupportedOperationException.class, () -> batch.listings().clear());
        assertThrows(UnsupportedOperationException.class, () -> batch.communityIds().clear());
    }

    @Test
    void overlappingIdentitiesFailBeforeRecoveryCanRemoveAnyPhysicalMarker() {
        MarketListing listing = listing(UUID.randomUUID(), MarketListing.State.PREPARED);
        assertThrows(IllegalStateException.class, () -> PreparedEscrowBatch.combine(List.of(listing), List.of(listing)));
        assertThrows(IllegalStateException.class, () -> PreparedEscrowBatch.combine(List.of(listing, listing), List.of()));
        assertThrows(IllegalStateException.class, () -> PreparedEscrowBatch.combine(List.of(), List.of(listing, listing)));
    }

    @Test
    void alreadyStoredOrQuarantinedRowsAreNeverTreatedAsUnfinishedRemoval() {
        for (MarketListing.State state : MarketListing.State.values()) {
            if (state == MarketListing.State.PREPARED) continue;
            MarketListing listing = listing(UUID.randomUUID(), state);
            assertThrows(IllegalStateException.class, () -> PreparedEscrowBatch.combine(List.of(listing), List.of()), state.name());
            assertThrows(IllegalStateException.class, () -> PreparedEscrowBatch.combine(List.of(), List.of(listing)), state.name());
        }
    }

    private static MarketListing listing(UUID id, MarketListing.State state) {
        return new MarketListing(id, UUID.randomUUID(), "immutable-payload", BigDecimal.ZERO,
                BigDecimal.ZERO, state, null, Instant.EPOCH, null);
    }
}
