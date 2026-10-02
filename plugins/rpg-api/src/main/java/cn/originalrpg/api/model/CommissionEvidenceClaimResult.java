package cn.originalrpg.api.model;

import java.util.Objects;

public record CommissionEvidenceClaimResult(Status status, CommissionEvidenceSnapshot snapshot) {
    public CommissionEvidenceClaimResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(snapshot, "snapshot");
    }

    public enum Status {
        SUCCESS,
        INSUFFICIENT_EVIDENCE,
        TOTAL_LIMIT,
        OFFER_LIMIT,
        INVALID
    }
}
