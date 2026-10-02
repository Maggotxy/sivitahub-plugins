package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EconomyProgress(
        UUID playerId,
        BigDecimal gold,
        long merit,
        LocalDate activityDate,
        Map<EconomyActivity, ActivityQuota> dailyQuotas
) {
    public EconomyProgress {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(gold, "gold");
        Objects.requireNonNull(activityDate, "activityDate");
        Objects.requireNonNull(dailyQuotas, "dailyQuotas");
        EnumMap<EconomyActivity, ActivityQuota> quotaCopy = new EnumMap<>(EconomyActivity.class);
        quotaCopy.putAll(dailyQuotas);
        dailyQuotas = Map.copyOf(quotaCopy);
        if (gold.signum() < 0 || merit < 0) {
            throw new IllegalArgumentException("Economy progress values cannot be negative");
        }
    }

    public ActivityQuota quota(EconomyActivity activity) {
        return dailyQuotas.getOrDefault(activity, new ActivityQuota(BigDecimal.ZERO.setScale(2), 0));
    }
}
