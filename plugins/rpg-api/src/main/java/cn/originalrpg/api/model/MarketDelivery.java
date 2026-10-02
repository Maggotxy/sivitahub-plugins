package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MarketDelivery(UUID id, UUID playerId, String itemPayload, String source, Instant createdAt) {
    public MarketDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
