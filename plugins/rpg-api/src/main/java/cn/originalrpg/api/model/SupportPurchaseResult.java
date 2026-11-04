package cn.originalrpg.api.model;

import java.util.Objects;

/** Cosmetic ownership and the debit commit together. An already-owned cosmetic is never charged twice. */
public record SupportPurchaseResult(Status status, long balance, long charged, SupportCosmetic cosmetic) {
    public SupportPurchaseResult {
        Objects.requireNonNull(status, "status");
        if (balance < 0 || charged < 0) throw new IllegalArgumentException("Negative support purchase result");
    }
    public enum Status { SUCCESS, DUPLICATE, ALREADY_OWNED, DISABLED, INVALID, PRICE_CHANGED, INSUFFICIENT_FUNDS, CONFLICT }
}
