package cn.originalrpg.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class MerchantPricing {
    private static final BigDecimal MINIMUM_PRICE = new BigDecimal("0.01");

    /** 文档承诺的往返净损耗硬下限；配置低于此值即 fail-closed，不再静默夹到更低值。 */
    static final BigDecimal MINIMUM_ROUNDTRIP_LOSS_FLOOR = new BigDecimal("0.10");

    private MerchantPricing() {}

    /**
     * 读配置的 {@code black-market.minimum-roundtrip-loss} 并收敛到文档承诺值。
     *
     * <p>文档（{@code docs/ECONOMY.md}）把 10% 写成对玩家的承诺（G-208）。历史实现对
     * {@code 0.05..0.50} 做 clamp，等于 YAML 可以把承诺悄悄降到 5%。这里把下限抬到承诺值，
     * 并对低于下限的配置直接拒绝启动，使「承诺 10%」成为硬保证而不是默认值。
     */
    static BigDecimal requiredMinimumRoundtripLoss(double configured) {
        if (!Double.isFinite(configured)) {
            throw new IllegalStateException(
                    "black-market.minimum-roundtrip-loss must be a finite number, got " + configured);
        }
        BigDecimal value = BigDecimal.valueOf(configured);
        if (value.compareTo(MINIMUM_ROUNDTRIP_LOSS_FLOOR) < 0) {
            throw new IllegalStateException(
                    "black-market.minimum-roundtrip-loss " + value.toPlainString()
                            + " is below the documented 10% roundtrip loss floor; raise it to at least "
                            + MINIMUM_ROUNDTRIP_LOSS_FLOOR.toPlainString());
        }
        return value.min(new BigDecimal("0.50"));
    }

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
