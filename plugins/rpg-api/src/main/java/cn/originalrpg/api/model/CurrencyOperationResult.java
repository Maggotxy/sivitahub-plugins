package cn.originalrpg.api.model;

import java.math.BigDecimal;

public record CurrencyOperationResult(Status status, BigDecimal balance, BigDecimal amount) {
    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        INVALID_AMOUNT,
        /** The original debit or reversal metadata does not authorize this refund. */
        REFUND_REJECTED,
        DUPLICATE
    }
}
