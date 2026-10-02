package cn.originalrpg.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void configuredRoundtripLossBelowTheDocumentedCommitmentIsRejected() {
        // G-208 行为级验收：0.05（旧 clamp 下限）必须被拒，而不是被静默夹到更低值。
        assertThrows(IllegalStateException.class,
                () -> MerchantPricing.requiredMinimumRoundtripLoss(0.05),
                "配置把承诺的 10% 净损耗降到 5% 时必须 fail-closed");
        assertThrows(IllegalStateException.class,
                () -> MerchantPricing.requiredMinimumRoundtripLoss(0.0999));
        assertThrows(IllegalStateException.class,
                () -> MerchantPricing.requiredMinimumRoundtripLoss(Double.NaN));

        // 承诺值与更高值仍然被接受；上限继续收敛到 0.50。
        assertEquals(0, MerchantPricing.requiredMinimumRoundtripLoss(0.10)
                .compareTo(new BigDecimal("0.10")));
        assertEquals(0, MerchantPricing.requiredMinimumRoundtripLoss(0.25)
                .compareTo(new BigDecimal("0.25")));
        assertEquals(0, MerchantPricing.requiredMinimumRoundtripLoss(0.90)
                .compareTo(new BigDecimal("0.50")));
    }

    @Test
    void shippedConfigKeepsTheDocumentedTenPercentFloor() throws Exception {
        // 配置真相在 resources/config.yml：默认值不得低于承诺值，否则启动即 fail-closed。
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        try (java.io.InputStream input = MerchantPricingTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input, "config.yml");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> root = yaml.load(input);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> blackMarket =
                    (java.util.Map<String, Object>) root.get("black-market");
            double configured = ((Number) blackMarket.get("minimum-roundtrip-loss")).doubleValue();
            assertEquals(0, MerchantPricing.requiredMinimumRoundtripLoss(configured)
                    .compareTo(new BigDecimal("0.10")));
        }
    }

    @Test
    void theShippedConfigWouldSurviveStartupValidation() throws Exception {
        // RpgEconomyPlugin.onEnable 会先做同一判定；这里证明出厂配置一定过得去，
        // 否则插件会在 enable 阶段直接失败（G-208）。
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        try (java.io.InputStream input = MerchantPricingTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input, "config.yml");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> root = yaml.load(input);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> blackMarket =
                    (java.util.Map<String, Object>) root.get("black-market");
            assertNotNull(blackMarket, "black-market 段必须存在");
            double configured = ((Number) blackMarket.get("minimum-roundtrip-loss")).doubleValue();
            // 不抛异常即视为通过启动校验。
            MerchantPricing.requiredMinimumRoundtripLoss(configured);
        }
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
