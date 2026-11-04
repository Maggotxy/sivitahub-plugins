package cn.originalrpg.api.model;

/**
 * A funded purchase request in the existing market (C-009).
 * BUY accepts matching escrowed goods. CRAFT accepts signed equipment made by
 * the fulfiller, who supplies the crafting materials. Both reserve the buyer's
 * quoted gold budget, including the 5%/10% fee; no second wallet or item escrow
 * is created. Quantity is the unfilled quantity, not the original amount.
 */
public record MarketOrder(
        String orderId,
        java.util.UUID buyerId,
        String itemMatcher,
        long price, // maximum gold budget per item, including the fee
        int quantity,
        String status,
        Kind kind
) {
    public enum Kind { BUY, CRAFT }

    public MarketOrder(String orderId, java.util.UUID buyerId, String itemMatcher,
                       long price, int quantity, String status) {
        this(orderId, buyerId, itemMatcher, price, quantity, status, Kind.BUY);
    }

    public static final String PREPARED = "PREPARED";
    public static final String ACTIVE = "ACTIVE";
    public static final String FILLED = "FILLED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
}
