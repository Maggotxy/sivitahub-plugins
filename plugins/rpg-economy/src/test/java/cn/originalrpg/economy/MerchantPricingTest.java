package cn.originalrpg.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantPricingTest {
    @Test
    void anyHistoricalLowPurchaseLosesAtLeastTenPercentAgainstAnyFutureHighBuyback() {
        BigDecimal base = new BigDecimal("900.00");
        BigDecimal minimum = new BigDecimal("0.80");
        BigDecimal maximum = new BigDecimal("1.80");
        BigDecimal requiredLoss = new BigDecimal("0.10");
        BigDecimal ratio = MerchantPricing.safeBuybackRatio(
                new BigDecimal("0.55"), minimum, maximum, requiredLoss);

        BigDecimal cheapestPurchase = MerchantPricing.prices(base, minimum, maximum, ratio, 0).sellPrice();
        BigDecimal highestBuyback = BigDecimal.ZERO;
        for (long sample = 0; sample <= 10_000; sample++) {
            highestBuyback = highestBuyback.max(
                    MerchantPricing.prices(base, minimum, maximum, ratio, sample).buybackPrice());
        }
        BigDecimal maximumAllowed = cheapestPurchase.multiply(BigDecimal.ONE.subtract(requiredLoss))
                .setScale(2, RoundingMode.DOWN);
        assertTrue(highestBuyback.compareTo(maximumAllowed) <= 0,
                "buyback=" + highestBuyback + " allowed=" + maximumAllowed);
    }

    @Test
    void everyConfiguredOfferShapeMaintainsItsLossFloor() {
        assertLoss("1800", "0.90", "2.00", "0.52", "0.10");
        assertLoss("5200", "0.85", "1.65", "0.50", "0.10");
        assertLoss("2700", "0.85", "1.65", "0.50", "0.10");
    }

    private static void assertLoss(String baseText, String minText, String maxText,
                                   String configuredText, String lossText) {
        BigDecimal base = new BigDecimal(baseText);
        BigDecimal minimum = new BigDecimal(minText);
        BigDecimal maximum = new BigDecimal(maxText);
        BigDecimal loss = new BigDecimal(lossText);
        BigDecimal ratio = MerchantPricing.safeBuybackRatio(
                new BigDecimal(configuredText), minimum, maximum, loss);
        BigDecimal lowest = MerchantPricing.prices(base, minimum, maximum, ratio, 0).sellPrice();
        BigDecimal highest = MerchantPricing.prices(base, minimum, maximum, ratio, 10_000).buybackPrice();
        assertTrue(highest.compareTo(lowest.multiply(BigDecimal.ONE.subtract(loss))) <= 0);
    }
}
