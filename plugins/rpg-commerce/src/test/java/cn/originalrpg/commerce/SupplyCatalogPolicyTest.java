package cn.originalrpg.commerce;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyCatalogPolicyTest {
    @Test
    void reviewedConvenienceSupplyIsAccepted() {
        assertTrue(SupplyCatalogPolicy.validate("torches", "TORCH", 64,
                new BigDecimal("480.00"), new BigDecimal("456.00"), 2).accepted());
    }

    @Test
    void renewableRawMaterialCannotBeAddedOnlyThroughYaml() {
        assertFalse(SupplyCatalogPolicy.validate("cheap-iron", "IRON_INGOT", 64,
                new BigDecimal("1.00"), new BigDecimal("0.95"), 100).accepted());
    }

    @Test
    void reviewedIdCannotBeRepointedOrMadeCheaper() {
        assertFalse(SupplyCatalogPolicy.validate("torches", "IRON_INGOT", 64,
                new BigDecimal("480.00"), new BigDecimal("456.00"), 2).accepted());
        assertFalse(SupplyCatalogPolicy.validate("torches", "TORCH", 64,
                new BigDecimal("100.00"), new BigDecimal("95.00"), 2).accepted());
    }

    @Test
    void excessiveDiscountOrThroughputFailsClosed() {
        assertFalse(SupplyCatalogPolicy.validate("arrows", "ARROW", 64,
                new BigDecimal("700.00"), new BigDecimal("300.00"), 2).accepted());
        assertFalse(SupplyCatalogPolicy.validate("arrows", "ARROW", 64,
                new BigDecimal("700.00"), new BigDecimal("665.00"), 3).accepted());
    }
}
