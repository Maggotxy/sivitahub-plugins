package cn.originalrpg.admin;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EconomyHealthPolicyTest {
    @Test
    void emptyNewServerIsNotReportedAsCriticalInflation() {
        assertEquals(EconomyHealthPolicy.State.INSUFFICIENT_DATA,
                evaluate("0", "0", "0", "0", "0", 0, "0", 0, 0));
        assertEquals(EconomyHealthPolicy.State.INSUFFICIENT_DATA,
                evaluate("300", "0", "1", "0", "1", 0, "0", 0, 0));
    }

    @Test
    void documentedSinkAndSourceThresholdsProduceWarnings() {
        assertEquals(EconomyHealthPolicy.State.WARNING,
                evaluate("10000", "0.84", "0.20", "0.20", "0.20", 0, "12", 20, 1));
        assertEquals(EconomyHealthPolicy.State.WARNING,
                evaluate("10000", "0.95", "0.20", "0.20", "0.46", 0, "12", 20, 1));
    }

    @Test
    void severeLowSinksOrIntegrityQuarantineAreCritical() {
        assertEquals(EconomyHealthPolicy.State.CRITICAL,
                evaluate("10000", "0.69", "0.20", "0.20", "0.20", 0, "12", 20, 1));
        assertEquals(EconomyHealthPolicy.State.CRITICAL,
                evaluate("0", "0", "0", "0", "0", 1, "0", 0, 0));
    }

    @Test
    void healthySevenDayWindowStaysHealthy() {
        assertEquals(EconomyHealthPolicy.State.HEALTHY,
                evaluate("10000", "0.97", "0.30", "0.20", "0.30", 0, "48", 30, 4));
    }

    @Test
    void completeActiveGoldGrowthAboveFourPercentProducesWarning() {
        assertEquals(EconomyHealthPolicy.State.WARNING, EconomyHealthPolicy.evaluate(
                decimal("10000"), decimal("0.97"), decimal("0.30"), decimal("0.20"),
                decimal("0.30"), 0, decimal("48"), 30, 4, decimal("0.0401")));
        assertEquals(EconomyHealthPolicy.State.HEALTHY, EconomyHealthPolicy.evaluate(
                decimal("10000"), decimal("0.97"), decimal("0.30"), decimal("0.20"),
                decimal("0.30"), 0, decimal("48"), 30, 4, decimal("0.0400")));
    }

    private static EconomyHealthPolicy.State evaluate(
            String minted, String sink, String wealth, String market, String source,
            long quarantined, String age, long sold, long expired
    ) {
        return EconomyHealthPolicy.evaluate(decimal(minted), decimal(sink), decimal(wealth), decimal(market),
                decimal(source), quarantined, decimal(age), sold, expired);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
