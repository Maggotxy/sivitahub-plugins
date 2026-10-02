package cn.originalrpg.api.model;

import java.util.Objects;

public record CurrencyExchangeResult(
        Status status,
        String routeId,
        long units,
        WealthBalance balance,
        long dailyRemaining,
        long weeklyRemaining
) {
    public CurrencyExchangeResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(routeId, "routeId");
        if (units < 0 || dailyRemaining < 0 || weeklyRemaining < 0) {
            throw new IllegalArgumentException("Invalid currency exchange result");
        }
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        INSUFFICIENT_FUNDS,
        LIMIT_EXCEEDED,
        BALANCE_LIMIT,
        INVALID
    }
}
