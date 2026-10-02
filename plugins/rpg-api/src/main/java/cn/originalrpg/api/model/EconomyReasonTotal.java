package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

public record EconomyReasonTotal(String reason, BigDecimal amount) {
    public EconomyReasonTotal {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) throw new IllegalArgumentException("amount");
    }
}
