package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Durable decision made for one conditional incident reward. */
public record IncidentConditionalActivityAudit(
        String idempotencyKey,
        EconomyActivity activity,
        BigDecimal gold,
        long merit,
        Status status
) {
    public IncidentConditionalActivityAudit {
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(gold, "gold");
        Objects.requireNonNull(status, "status");
    }

    public enum Status {
        APPLIED,
        ALREADY_PRESENT
    }
}
