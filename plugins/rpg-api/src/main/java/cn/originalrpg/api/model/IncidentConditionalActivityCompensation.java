package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A recovery reward that is granted only when its original authoritative
 * activity, gold and merit ledger entries are all absent.
 */
public record IncidentConditionalActivityCompensation(
        String evidenceId,
        EconomyActivity activity,
        BigDecimal gold,
        long merit,
        String idempotencyKey,
        String reason
) {
    public IncidentConditionalActivityCompensation {
        evidenceId = Objects.requireNonNull(evidenceId, "evidenceId").strip();
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(gold, "gold");
        gold = gold.setScale(2, RoundingMode.UNNECESSARY);
        idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey").strip();
        reason = Objects.requireNonNull(reason, "reason").strip();
        if (evidenceId.isEmpty() || idempotencyKey.isEmpty() || reason.isEmpty()
                || gold.signum() < 0 || merit < 0 || gold.signum() == 0 && merit == 0) {
            throw new IllegalArgumentException("Conditional incident reward is incomplete or negative");
        }
    }
}
