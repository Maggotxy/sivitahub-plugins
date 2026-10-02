package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** One economy source restored by an audited incident compensation. */
public record IncidentActivityCompensation(EconomyActivity activity, BigDecimal gold, long merit) {
    public IncidentActivityCompensation {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(gold, "gold");
        gold = gold.setScale(2, RoundingMode.UNNECESSARY);
        if (gold.signum() < 0 || merit < 0 || gold.signum() == 0 && merit == 0) {
            throw new IllegalArgumentException("An incident activity line must restore a positive reward");
        }
    }
}
