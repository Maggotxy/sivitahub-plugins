package cn.originalrpg.commerce;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceCatalogPolicyTest {
    @Test
    void productionPowerAndMembershipProductsPass() {
        assertTrue(CommerceCatalogPolicy.validateProduct(
                "protection-stone", "AMETHYST_SHARD", 1, "protection_stone", 60, 1, 0).accepted());
        assertTrue(CommerceCatalogPolicy.validateProduct(
                "lucky-charm", "ECHO_SHARD", 1, "lucky_charm", 30, 2, 0).accepted());
        assertTrue(CommerceCatalogPolicy.validateProduct(
                "membership-3-days", "CLOCK", 1, "membership_time", 90, 1, 3).accepted());
    }

    @Test
    void yamlCannotAddPowerOrLowerPriceAndThroughputGates() {
        assertFalse(CommerceCatalogPolicy.validateProduct(
                "hidden-class", "PAPER", 1, "hidden_class", 1, 1, 0).accepted());
        assertFalse(CommerceCatalogPolicy.validateProduct(
                "protection-stone", "AMETHYST_SHARD", 1, "protection_stone", 59, 1, 0).accepted());
        assertFalse(CommerceCatalogPolicy.validateProduct(
                "lucky-charm", "ECHO_SHARD", 1, "lucky_charm", 30, 3, 0).accepted());
        assertFalse(CommerceCatalogPolicy.validateProduct(
                "membership-3-days", "CLOCK", 1, "membership_time", 90, 1, 31).accepted());
    }

    @Test
    void cumulativeRewardsCannotBeMadeEarlierOrLarger() {
        assertTrue(CommerceCatalogPolicy.validateReward(
                "veteran-support", "ECHO_SHARD", 2, "lucky_charm", new BigDecimal("100.00")).accepted());
        assertFalse(CommerceCatalogPolicy.validateReward(
                "veteran-support", "ECHO_SHARD", 3, "lucky_charm", new BigDecimal("100.00")).accepted());
        assertFalse(CommerceCatalogPolicy.validateReward(
                "veteran-support", "ECHO_SHARD", 2, "lucky_charm", new BigDecimal("99.99")).accepted());
        assertFalse(CommerceCatalogPolicy.validateReward(
                "veteran-support", "ECHO_SHARD", 2, "hidden_class", new BigDecimal("100.00")).accepted());
    }
}
