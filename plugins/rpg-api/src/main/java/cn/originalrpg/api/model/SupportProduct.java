package cn.originalrpg.api.model;

import java.util.Objects;

/** A configured support-credit price for one permanent, account-bound cosmetic entitlement. */
public record SupportProduct(SupportCosmetic cosmetic, long cost) {
    public SupportProduct {
        Objects.requireNonNull(cosmetic, "cosmetic");
        if (cost < 1 || cost > 1_000_000_000L) throw new IllegalArgumentException("Invalid cosmetic price");
    }
}
