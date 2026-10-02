package cn.originalrpg.api.model;

import java.math.BigDecimal;

public record MarketOperationResult(Status status, MarketListing listing, BigDecimal fee) {
    public enum Status {
        SUCCESS,
        NOT_FOUND,
        NOT_OWNER,
        NOT_ACTIVE,
        OWN_LISTING,
        INSUFFICIENT_FUNDS,
        BALANCE_LIMIT,
        LISTING_LIMIT,
        DUPLICATE_ITEM,
        DUPLICATE,
        /** Phase 2 lifecycle gate: the item identity is not tradeable in its current state. */
        ITEM_NOT_TRADEABLE,
        INVALID
    }
}
