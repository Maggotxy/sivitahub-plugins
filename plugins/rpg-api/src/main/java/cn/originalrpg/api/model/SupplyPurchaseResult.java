package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Result of a Gold-priced system supply purchase. The service chooses the
 * membership price inside the same transaction that checks the period quota,
 * burns Gold, and creates the durable item delivery.
 */
public record SupplyPurchaseResult(
        Status status,
        BigDecimal chargedGold,
        BigDecimal goldBalance,
        boolean memberDiscountApplied,
        Optional<UUID> deliveryId,
        int usedPurchases,
        int maximumPurchases
) {
    public SupplyPurchaseResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(chargedGold, "chargedGold");
        Objects.requireNonNull(goldBalance, "goldBalance");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        if (chargedGold.signum() < 0 || goldBalance.signum() < 0
                || usedPurchases < 0 || maximumPurchases < 0) {
            throw new IllegalArgumentException("Invalid supply purchase result");
        }
    }

    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        LIMIT_REACHED,
        DUPLICATE,
        INVALID
    }
}
