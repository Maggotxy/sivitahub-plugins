package cn.originalrpg.api.model;

import java.util.Optional;
import java.util.UUID;

public record RewardClaimResult(Status status, CommerceStatus account, Optional<UUID> deliveryId) {
    public RewardClaimResult {
        deliveryId = deliveryId == null ? Optional.empty() : deliveryId;
    }

    public enum Status {
        SUCCESS,
        ALREADY_CLAIMED,
        NOT_ELIGIBLE,
        INVALID
    }
}
