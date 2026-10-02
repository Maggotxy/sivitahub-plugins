package cn.originalrpg.api.model;

import java.util.Optional;

public record IncidentCompensationResult(Status status, Optional<IncidentCompensationAudit> audit) {
    public IncidentCompensationResult {
        audit = audit == null ? Optional.empty() : audit;
    }

    public enum Status {
        APPLIED,
        DUPLICATE,
        CONFLICT,
        BALANCE_LIMIT,
        MERIT_LIMIT,
        NOT_FOUND,
        INVALID
    }
}
