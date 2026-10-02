package cn.originalrpg.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

record EconomyWeekComparison(
        Status status,
        LocalDate currentDate,
        LocalDate baselineDate,
        int historicalDays,
        BigDecimal currentActiveGold,
        BigDecimal baselineActiveGold,
        BigDecimal activeGoldDelta,
        BigDecimal activeGoldChangeRatio,
        BigDecimal currentTotalGold,
        BigDecimal baselineTotalGold,
        BigDecimal totalGoldDelta,
        BigDecimal totalGoldChangeRatio
) {
    EconomyWeekComparison {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currentDate, "currentDate");
        Objects.requireNonNull(baselineDate, "baselineDate");
        Objects.requireNonNull(currentActiveGold, "currentActiveGold");
        Objects.requireNonNull(baselineActiveGold, "baselineActiveGold");
        Objects.requireNonNull(activeGoldDelta, "activeGoldDelta");
        Objects.requireNonNull(activeGoldChangeRatio, "activeGoldChangeRatio");
        Objects.requireNonNull(currentTotalGold, "currentTotalGold");
        Objects.requireNonNull(baselineTotalGold, "baselineTotalGold");
        Objects.requireNonNull(totalGoldDelta, "totalGoldDelta");
        Objects.requireNonNull(totalGoldChangeRatio, "totalGoldChangeRatio");
        if (historicalDays < 0 || historicalDays > 7) {
            throw new IllegalArgumentException("Historical day count must be between zero and seven");
        }
    }

    boolean available() {
        return status == Status.AVAILABLE;
    }

    enum Status {
        AVAILABLE,
        INSUFFICIENT_HISTORY,
        ZERO_BASELINE
    }
}
