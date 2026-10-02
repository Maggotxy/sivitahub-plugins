package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.UUID;

/** One immutable item payload queued into the shared durable mailbox. */
public record DungeonRewardDelivery(
        UUID deliveryId,
        String itemPayload,
        String source
) {
    public DungeonRewardDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(itemPayload, "itemPayload");
        Objects.requireNonNull(source, "source");
        if (itemPayload.isBlank() || source.isBlank()) {
            throw new IllegalArgumentException("Dungeon reward delivery fields must not be blank");
        }
    }
}
