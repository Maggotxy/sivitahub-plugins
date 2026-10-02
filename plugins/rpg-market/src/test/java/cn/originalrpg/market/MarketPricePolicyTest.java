package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPricePolicyTest {
    private static final BigDecimal MIN = new BigDecimal("1.00");
    private static final BigDecimal MAX = new BigDecimal("10000000.00");

    @Test
    void acceptedPricesAreStoredAtExactlyTwoDecimals() {
        assertEquals(new BigDecimal("1.00"), MarketPricePolicy.normalize("1", MIN, MAX).orElseThrow());
        assertEquals(new BigDecimal("12.30"), MarketPricePolicy.normalize("12.3", MIN, MAX).orElseThrow());
    }

    @Test
    void rejectsRoundingAmbiguityAndNonPlainNumbers() {
        assertTrue(MarketPricePolicy.normalize("1.001", MIN, MAX).isEmpty());
        assertTrue(MarketPricePolicy.normalize("1e3", MIN, MAX).isEmpty());
        assertTrue(MarketPricePolicy.normalize("0.99", MIN, MAX).isEmpty());
        assertTrue(MarketPricePolicy.normalize("10000000.01", MIN, MAX).isEmpty());
    }
}
