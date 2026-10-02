package cn.originalrpg.api.model;

public record CreditResult(
        Status status,
        long balance,
        long maximumPerGrant,
        long dailyGranted,
        long dailyLimit,
        long weeklyGranted,
        long weeklyLimit
) {
    public CreditResult {
        if (balance < 0 || maximumPerGrant < 0 || dailyGranted < 0 || dailyLimit < 0
                || weeklyGranted < 0 || weeklyLimit < 0) {
            throw new IllegalArgumentException("Credit result values cannot be negative");
        }
    }

    public CreditResult(Status status, long balance) {
        this(status, balance, 0L, 0L, 0L, 0L, 0L);
    }

    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        PER_GRANT_LIMIT_REACHED,
        DAILY_LIMIT_REACHED,
        WEEKLY_LIMIT_REACHED,
        BALANCE_LIMIT,
        DUPLICATE,
        INVALID
    }
}
