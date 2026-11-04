package cn.originalrpg.api.model;

import java.util.Objects;

/** Result of an authenticated order import; only SUCCESS introduces new support credit. */
public record SupportPaymentResult(Status status, String orderId, long balance, long credited,
                                   long playerWeekIssued, long playerWeekLimit) {
    public SupportPaymentResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(orderId, "orderId");
        if (balance < 0 || credited < 0 || playerWeekIssued < 0 || playerWeekLimit < 0) {
            throw new IllegalArgumentException("Negative support payment result");
        }
    }
    public enum Status {
        SUCCESS, DUPLICATE, DISABLED, INVALID, INVALID_SIGNATURE, UNKNOWN_KEY,
        WRONG_AUDIENCE, EXPIRED, NOT_YET_VALID, PER_ORDER_LIMIT,
        PLAYER_WEEK_LIMIT, GLOBAL_WEEK_LIMIT, BALANCE_LIMIT, CONFLICT, RETRY_REQUIRED
    }
}
