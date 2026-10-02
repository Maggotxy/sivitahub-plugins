package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

public record CurrencyExchangeQuote(
        String routeId,
        BigDecimal goldCost,
        long meritCost,
        long starlightCost,
        BigDecimal goldReward,
        long meritReward,
        long starlightReward,
        long dailyLimit,
        long weeklyLimit,
        long dailyRemaining,
        long weeklyRemaining
) {
    public CurrencyExchangeQuote {
        Objects.requireNonNull(routeId, "routeId");
        Objects.requireNonNull(goldCost, "goldCost");
        Objects.requireNonNull(goldReward, "goldReward");
        if (goldCost.signum() < 0 || meritCost < 0 || starlightCost < 0
                || goldReward.signum() < 0 || meritReward < 0 || starlightReward < 0
                || dailyLimit <= 0 || weeklyLimit <= 0 || dailyRemaining < 0 || weeklyRemaining < 0) {
            throw new IllegalArgumentException("Invalid currency exchange quote");
        }
    }
}
