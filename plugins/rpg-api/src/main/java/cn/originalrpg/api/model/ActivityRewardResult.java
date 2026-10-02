package cn.originalrpg.api.model;

import java.math.BigDecimal;

public record ActivityRewardResult(
        Status status,
        EconomyProgress progress,
        BigDecimal goldGranted,
        long meritGranted
) {
    public enum Status {
        SUCCESS,
        PARTIAL,
        DAILY_LIMIT,
        BALANCE_LIMIT,
        MERIT_LIMIT,
        DUPLICATE,
        INVALID
    }
}
