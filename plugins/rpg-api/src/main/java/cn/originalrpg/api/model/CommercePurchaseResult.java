package cn.originalrpg.api.model;

import java.util.Optional;
import java.util.UUID;

public record CommercePurchaseResult(
        Status status,
        CommerceStatus account,
        Optional<UUID> deliveryId,
        int usedPurchases,
        int maximumPurchases
) {
    public CommercePurchaseResult {
        if (usedPurchases < 0 || maximumPurchases < 0) throw new IllegalArgumentException("Invalid purchase limit");
    }

    public enum Status {
        SUCCESS,
        INSUFFICIENT_CREDITS,
        LIMIT_REACHED,
        MEMBERSHIP_LIMIT_REACHED,
        DUPLICATE,
        INVALID
    }
}
