package cn.originalrpg.market;

import java.math.BigDecimal;

/** Code-owned economic guardrails for the player market configuration. */
final class MarketConfigPolicy {
    private static final BigDecimal MINIMUM_NORMAL_FEE = new BigDecimal("0.05");
    private static final BigDecimal MINIMUM_EQUIPMENT_FEE = new BigDecimal("0.10");
    private static final BigDecimal MAXIMUM_FEE = new BigDecimal("0.25");
    private static final BigDecimal MINIMUM_LISTING_PRICE = new BigDecimal("1.00");
    private static final BigDecimal MAXIMUM_LISTING_PRICE = new BigDecimal("10000000.00");

    private MarketConfigPolicy() {
    }

    static void validate(
            BigDecimal normalFee,
            BigDecimal equipmentFee,
            BigDecimal minimumPrice,
            BigDecimal maximumPrice
    ) {
        if (normalFee == null || equipmentFee == null || minimumPrice == null || maximumPrice == null
                || normalFee.compareTo(MINIMUM_NORMAL_FEE) < 0
                || equipmentFee.compareTo(MINIMUM_EQUIPMENT_FEE) < 0
                || normalFee.compareTo(MAXIMUM_FEE) > 0
                || equipmentFee.compareTo(MAXIMUM_FEE) > 0
                || equipmentFee.compareTo(normalFee) < 0
                || minimumPrice.compareTo(MINIMUM_LISTING_PRICE) < 0
                || maximumPrice.compareTo(minimumPrice) < 0
                || maximumPrice.compareTo(MAXIMUM_LISTING_PRICE) > 0) {
            throw new IllegalArgumentException("Market fees or listing bounds violate economic guardrails");
        }
    }
}
