package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Immutable request used to atomically reserve one paid pet revival. */
public record PetRevivalRequest(
        UUID operationId,
        UUID playerId,
        UUID petId,
        BigDecimal goldCost,
        String debitKey
) {
    public PetRevivalRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(petId, "petId");
        Objects.requireNonNull(goldCost, "goldCost");
        Objects.requireNonNull(debitKey, "debitKey");
    }
}
