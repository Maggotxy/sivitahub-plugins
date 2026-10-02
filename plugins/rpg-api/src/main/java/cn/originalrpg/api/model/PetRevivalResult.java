package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.Optional;

/** Authoritative outcome of a pet revival intent mutation. */
public record PetRevivalResult(Status status, Optional<PetRevivalIntent> intent) {
    public enum Status {
        STARTED,
        PENDING,
        COMPLETED,
        QUARANTINED,
        COMPENSATED,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        CONFLICT,
        NOT_FOUND,
        INVALID
    }

    public PetRevivalResult {
        Objects.requireNonNull(status, "status");
        intent = intent == null ? Optional.empty() : intent;
    }

    public static PetRevivalResult withoutIntent(Status status) {
        return new PetRevivalResult(status, Optional.empty());
    }
}
