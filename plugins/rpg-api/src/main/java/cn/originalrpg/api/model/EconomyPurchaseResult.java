package cn.originalrpg.api.model;

import java.util.Optional;
import java.util.UUID;

public record EconomyPurchaseResult(Status status, EconomyProgress progress, Optional<UUID> deliveryId) {
    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        DUPLICATE,
        INVALID
    }
}
