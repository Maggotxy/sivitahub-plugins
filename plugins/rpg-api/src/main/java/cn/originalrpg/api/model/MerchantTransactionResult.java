package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MerchantTransactionResult(
        Status status,
        BigDecimal goldDelta,
        BigDecimal balance,
        int usedUnits,
        int maximumUnits,
        Optional<UUID> deliveryId
) {
    public MerchantTransactionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(goldDelta, "goldDelta");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(deliveryId, "deliveryId");
        if (usedUnits < 0 || maximumUnits < 0) throw new IllegalArgumentException("Invalid merchant limit");
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        INSUFFICIENT_FUNDS,
        LIMIT_REACHED,
        BALANCE_LIMIT,
        INVALID
    }
}
