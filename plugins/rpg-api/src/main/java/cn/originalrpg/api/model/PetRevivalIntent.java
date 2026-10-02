package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Durable cross-database intent for one MyPet revival. */
public record PetRevivalIntent(
        UUID operationId,
        UUID playerId,
        UUID petId,
        BigDecimal goldCost,
        String debitKey,
        State state,
        String failureReason
) {
    public enum State {
        PENDING,
        COMPLETED,
        QUARANTINED,
        /** Gold was returned by an audited administrator review. */
        COMPENSATED
    }

    public PetRevivalIntent {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(petId, "petId");
        goldCost = Objects.requireNonNull(goldCost, "goldCost").setScale(2, RoundingMode.HALF_UP);
        Objects.requireNonNull(debitKey, "debitKey");
        Objects.requireNonNull(state, "state");
        failureReason = failureReason == null ? "" : failureReason;
    }
}
