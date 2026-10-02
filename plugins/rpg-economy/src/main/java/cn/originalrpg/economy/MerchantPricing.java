package cn.originalrpg.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class MerchantPricing {
    private static final BigDecimal MINIMUM_PRICE = new BigDecimal("0.01");

    private MerchantPricing() {}

    static BigDecimal safeBuybackRatio(
            BigDecimal configured, BigDecimal minimumMultiplier, BigDecimal maximumMultiplier,
            BigDecimal minimumRoundtripLoss
    ) {
        BigDecimal ceiling = minimumMultiplier.divide(maximumMultiplier, 8, RoundingMode.DOWN)
                .multiply(BigDecimal.ONE.subtract(minimumRoundtripLoss));
        return configured.min(ceiling).max(MINIMUM_PRICE);
    }

    static Prices prices(
            BigDecimal basePrice, BigDecimal minimumMultiplier, BigDecimal maximumMultiplier,
            BigDecimal buybackRatio, long mixedPeriodValue
    ) {
        BigDecimal fraction = BigDecimal.valueOf(Math.floorMod(mixedPeriodValue, 10_001L), 4);
        BigDecimal multiplier = minimumMultiplier.add(
                maximumMultiplier.subtract(minimumMultiplier).multiply(fraction));
        BigDecimal sell = basePrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        BigDecimal buyback = sell.multiply(buybackRatio).setScale(2, RoundingMode.DOWN);
        return new Prices(sell.max(MINIMUM_PRICE), buyback.max(MINIMUM_PRICE));
    }

    record Prices(BigDecimal sellPrice, BigDecimal buybackPrice) {}
}
