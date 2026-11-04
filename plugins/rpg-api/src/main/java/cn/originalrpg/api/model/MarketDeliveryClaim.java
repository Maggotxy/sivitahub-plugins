package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Database-owned mailbox reservation, before physical inventory admission.
 * Only {@link Status#CLAIMED} carries a payload. It is not permission to insert
 * an item: the same token must first win authorizeDelivery on the owning actor's
 * session. An indeterminate physical stage never becomes claimable by timeout.
 */
public record MarketDeliveryClaim(Status status, UUID deliveryId, UUID claimToken,
                                  MarketDelivery delivery, Instant expiresAt) {
    public MarketDeliveryClaim {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(claimToken, "claimToken");
        if (status == Status.CLAIMED) {
            Objects.requireNonNull(delivery, "delivery");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!deliveryId.equals(delivery.id())) throw new IllegalArgumentException("Claim and delivery differ");
        }
    }

    public enum Status { CLAIMED, BUSY, INDETERMINATE, DELIVERED, REFUSED }
}
