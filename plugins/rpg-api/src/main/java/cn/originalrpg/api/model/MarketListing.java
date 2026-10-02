package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MarketListing(
        UUID id,
        UUID sellerId,
        String itemPayload,
        BigDecimal price,
        BigDecimal feeRate,
        State state,
        UUID buyerId,
        Instant createdAt,
        Instant completedAt
) {
    public MarketListing {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(feeRate, "feeRate");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum State {
        PREPARED,
        ACTIVE,
        SOLD,
        CANCELLED,
        EXPIRED,
        QUARANTINED
    }
}
