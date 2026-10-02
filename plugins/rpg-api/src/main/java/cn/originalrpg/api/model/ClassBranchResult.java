package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.Optional;

public record ClassBranchResult(Status status, int stage, Optional<String> branchId) {
    public ClassBranchResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(branchId, "branchId");
        if (stage < 1 || stage > 3) throw new IllegalArgumentException("Branch stage must be 1-3");
    }

    public enum Status {
        SUCCESS,
        ALREADY_CHOSEN,
        STAGE_LOCKED,
        PREREQUISITE_MISMATCH,
        CLASS_MISMATCH,
        INVALID
    }
}
