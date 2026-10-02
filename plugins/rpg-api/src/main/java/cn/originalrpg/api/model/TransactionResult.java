package cn.originalrpg.api.model;

import java.math.BigDecimal;

public record TransactionResult(Status status, BigDecimal senderBalance, BigDecimal fee) {
    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        INVALID_AMOUNT,
        DUPLICATE
    }
}
