package cn.originalrpg.commerce;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Code-reviewed boundaries for Starlight products and cumulative support rewards.
 * YAML may make an offer dearer or scarcer, but cannot add power, lower a gate,
 * increase throughput, or repoint a reviewed identifier to another item marker.
 */
final class CommerceCatalogPolicy {
    private static final Map<String, ProductRule> PRODUCTS = Map.of(
            "protection-stone", new ProductRule(
                    "AMETHYST_SHARD", "protection_stone", 1, 60L, 1, 0),
            "lucky-charm", new ProductRule(
                    "ECHO_SHARD", "lucky_charm", 1, 30L, 2, 0),
            "membership-3-days", new ProductRule(
                    "CLOCK", "membership_time", 1, 90L, 1, 3),
            "prismatic-aura-voucher", new ProductRule(
                    "NETHER_STAR", "founder_cosmetic", 1, 1600L, 1, 0),
            "elytra-wings-voucher", new ProductRule(
                    "PHANTOM_MEMBRANE", "elytra_wings", 1, 480L, 1, 0),
            "elytra-comet-voucher", new ProductRule(
                    "FIREWORK_STAR", "elytra_comet", 1, 960L, 1, 0)
    );
    private static final Map<String, RewardRule> REWARDS = Map.of(
            "first-support", new RewardRule(
                    "AMETHYST_SHARD", "protection_stone", 1, new BigDecimal("30.00")),
            "veteran-support", new RewardRule(
                    "ECHO_SHARD", "lucky_charm", 2, new BigDecimal("100.00")),
            "founder-style", new RewardRule(
                    "NETHER_STAR", "founder_cosmetic", 1, new BigDecimal("300.00"))
    );

    private CommerceCatalogPolicy() {
    }

    static Verdict validateProduct(
            String id,
            String material,
            int amount,
            String marker,
            long starlightCost,
            int weeklyLimit,
            int membershipDays
    ) {
        ProductRule rule = PRODUCTS.get(id);
        if (rule == null) return Verdict.reject("product is not in the code-reviewed Starlight catalog");
        if (!rule.material().equals(material) || !rule.marker().equals(marker)) {
            return Verdict.reject("material or effect marker differs from the reviewed product");
        }
        if (amount < 1 || amount > rule.maximumAmount()) {
            return Verdict.reject("item amount exceeds the reviewed product");
        }
        if (starlightCost < rule.minimumCost()) {
            return Verdict.reject("Starlight cost is below the reviewed floor");
        }
        if (weeklyLimit < 1 || weeklyLimit > rule.maximumWeeklyPurchases()) {
            return Verdict.reject("weekly throughput exceeds the reviewed product");
        }
        if (membershipDays != rule.membershipDays()) {
            return Verdict.reject("membership duration differs from the reviewed product");
        }
        return Verdict.accept();
    }

    static Verdict validateReward(
            String id,
            String material,
            int amount,
            String marker,
            BigDecimal requiredLifetime
    ) {
        RewardRule rule = REWARDS.get(id);
        if (rule == null) return Verdict.reject("reward is not in the code-reviewed cumulative catalog");
        if (!rule.material().equals(material) || !rule.marker().equals(marker)) {
            return Verdict.reject("material or effect marker differs from the reviewed reward");
        }
        if (amount < 1 || amount > rule.maximumAmount()) {
            return Verdict.reject("reward amount exceeds the reviewed maximum");
        }
        if (requiredLifetime == null || requiredLifetime.compareTo(rule.minimumLifetime()) < 0) {
            return Verdict.reject("lifetime support gate is below the reviewed floor");
        }
        return Verdict.accept();
    }

    record Verdict(boolean accepted, String reason) {
        private static Verdict accept() {
            return new Verdict(true, "accepted");
        }

        private static Verdict reject(String reason) {
            return new Verdict(false, reason);
        }
    }

    private record ProductRule(
            String material,
            String marker,
            int maximumAmount,
            long minimumCost,
            int maximumWeeklyPurchases,
            int membershipDays
    ) {
    }

    private record RewardRule(
            String material,
            String marker,
            int maximumAmount,
            BigDecimal minimumLifetime
    ) {
    }
}
