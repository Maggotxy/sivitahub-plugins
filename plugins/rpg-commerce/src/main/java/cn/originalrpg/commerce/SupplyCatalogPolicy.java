package cn.originalrpg.commerce;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Code-reviewed allow-list for ordinary items issued by the Gold supply shop.
 *
 * <p>The YAML file may tune a price upward, but it cannot silently introduce a
 * new vanilla commodity, deepen the membership discount, or increase daily
 * throughput. Adding a product therefore requires a source change and tests,
 * which keeps an innocent configuration edit from creating a system-shop
 * conversion loop.</p>
 */
final class SupplyCatalogPolicy {
    private static final BigDecimal MAXIMUM_MEMBER_DISCOUNT = new BigDecimal("0.05");
    private static final Map<String, Rule> REVIEWED = Map.of(
            "torches", new Rule("TORCH", 64, 2, new BigDecimal("480.00")),
            "cooked-beef", new Rule("COOKED_BEEF", 16, 2, new BigDecimal("560.00")),
            "arrows", new Rule("ARROW", 64, 2, new BigDecimal("700.00")),
            "ender-pearls", new Rule("ENDER_PEARL", 8, 1, new BigDecimal("720.00")),
            "firework-rockets", new Rule("FIREWORK_ROCKET", 16, 2, new BigDecimal("880.00"))
    );

    private SupplyCatalogPolicy() {
    }

    static Verdict validate(
            String id,
            String material,
            int amount,
            BigDecimal publicPrice,
            BigDecimal memberPrice,
            int dailyLimit
    ) {
        Rule rule = id == null ? null : REVIEWED.get(id);
        if (rule == null) return Verdict.reject("product is not in the code-reviewed supply allow-list");
        if (!rule.material().equals(material)) return Verdict.reject("material differs from the reviewed catalog");
        if (amount < 1 || amount > rule.maximumAmount()) {
            return Verdict.reject("stack amount exceeds the reviewed catalog");
        }
        if (dailyLimit < 1 || dailyLimit > rule.maximumDailyPurchases()) {
            return Verdict.reject("daily throughput exceeds the reviewed catalog");
        }
        if (publicPrice == null || memberPrice == null || publicPrice.signum() <= 0
                || memberPrice.signum() <= 0 || memberPrice.compareTo(publicPrice) > 0) {
            return Verdict.reject("public/member price is invalid");
        }
        if (publicPrice.compareTo(rule.minimumPublicPrice()) < 0) {
            return Verdict.reject("public price is below the audited price floor");
        }
        BigDecimal minimumMemberPrice = publicPrice.multiply(BigDecimal.ONE.subtract(MAXIMUM_MEMBER_DISCOUNT))
                .setScale(2, RoundingMode.DOWN);
        if (memberPrice.compareTo(minimumMemberPrice) < 0) {
            return Verdict.reject("membership discount exceeds five percent");
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

    private record Rule(
            String material,
            int maximumAmount,
            int maximumDailyPurchases,
            BigDecimal minimumPublicPrice
    ) {
    }
}
