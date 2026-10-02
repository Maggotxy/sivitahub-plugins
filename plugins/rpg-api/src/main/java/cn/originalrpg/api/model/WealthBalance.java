package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

public record WealthBalance(BigDecimal gold, long merit, long starlight) {
    public WealthBalance {
        Objects.requireNonNull(gold, "gold");
        if (gold.signum() < 0 || merit < 0 || starlight < 0) {
            throw new IllegalArgumentException("Wealth balances cannot be negative");
        }
    }
}
