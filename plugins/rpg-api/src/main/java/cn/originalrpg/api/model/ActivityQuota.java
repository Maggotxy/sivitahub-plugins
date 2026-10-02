package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

public record ActivityQuota(BigDecimal goldEarned, long meritEarned) {
    public ActivityQuota {
        Objects.requireNonNull(goldEarned, "goldEarned");
        if (goldEarned.signum() < 0 || meritEarned < 0) {
            throw new IllegalArgumentException("Activity quota values cannot be negative");
        }
    }
}
