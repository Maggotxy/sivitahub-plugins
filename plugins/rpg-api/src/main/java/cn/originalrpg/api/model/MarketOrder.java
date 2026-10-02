package cn.originalrpg.api.model;

/**
 * Phase 4: Market V2 buy-order + craft-order (single escrow ledger, C-009).
 * Buy orders are escrowed gold; craft orders are escrowed materials.
 * Both reuse market's single settlement and tax model.
 */
public record MarketOrder(
        String orderId,
        java.util.UUID buyerId,
        String itemMatcher, // material or item_id pattern
        long price, // gold escrow
        int quantity,
        String status // PREPARED/ACTIVE/FILLED/CANCELLED/EXPIRED
) {
    public static final String PREPARED = "PREPARED";
    public static final String ACTIVE = "ACTIVE";
    public static final String FILLED = "FILLED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
}
