package cn.originalrpg.api.model;

import java.util.Optional;

public record PetProgressResult(Status status, Optional<PetProgress> progress) {
    public enum Status {
        SUCCESS,
        DUPLICATE,
        NOT_FOUND,
        OWNER_MISMATCH,
        REQUIREMENT_NOT_MET,
        SKIN_LOCKED,
        INVALID
    }

    public static PetProgressResult of(Status status, PetProgress progress) {
        return new PetProgressResult(status, Optional.ofNullable(progress));
    }
}
