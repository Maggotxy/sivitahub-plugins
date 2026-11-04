package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SupportCosmetic;
import cn.originalrpg.api.model.SupportProduct;
import cn.originalrpg.api.model.SupportReceipt;
import org.bukkit.configuration.ConfigurationSection;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, explicit payment deployment policy. The shipped policy is disabled,
 * with no public keys, credit quotas or prices. Enabling it requires an operator's
 * trusted bridge key, unique deployment audience, finite quotas and reviewed prices.
 */
public record SupportCreditPolicy(boolean enabled, String audience, Map<String, PublicKey> trustedKeys,
                                  long maximumPerOrder, long playerWeeklyLimit, long globalWeeklyLimit,
                                  Duration maximumReceiptLifetime, Duration allowedClockSkew,
                                  Map<SupportCosmetic, Long> cosmeticPrices) {
    public static final long MAX_BALANCE = 1_000_000_000_000L;
    public SupportCreditPolicy {
        trustedKeys = Map.copyOf(trustedKeys);
        cosmeticPrices = Map.copyOf(cosmeticPrices);
        if (audience == null || trustedKeys.size() > 16 || maximumPerOrder < 0 || maximumPerOrder > 1_000_000_000L
                || playerWeeklyLimit < 0 || playerWeeklyLimit > MAX_BALANCE
                || globalWeeklyLimit < 0 || globalWeeklyLimit > 1_000_000_000_000_000L
                || maximumReceiptLifetime == null || maximumReceiptLifetime.compareTo(Duration.ofSeconds(60)) < 0
                || maximumReceiptLifetime.compareTo(Duration.ofHours(24)) > 0
                || allowedClockSkew == null || allowedClockSkew.isNegative() || allowedClockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid support-credit policy bounds");
        }
        trustedKeys.forEach((id, key) -> {
            if (!SupportReceipt.KEY_ID.matcher(id).matches() || !(key instanceof EdECPublicKey ed)
                    || !ed.getParams().getName().equals("Ed25519")) {
                throw new IllegalArgumentException("Support credit requires named Ed25519 public keys");
            }
        });
        cosmeticPrices.forEach((cosmetic, cost) -> new SupportProduct(cosmetic, cost));
        if (enabled && (!SupportReceipt.AUDIENCE.matcher(audience).matches() || trustedKeys.isEmpty()
                || maximumPerOrder < 1 || playerWeeklyLimit < maximumPerOrder
                || globalWeeklyLimit < playerWeeklyLimit || cosmeticPrices.isEmpty())) {
            throw new IllegalArgumentException("Enabled support credit requires an audience, trusted key, finite quotas and cosmetic catalog");
        }
    }

    public static SupportCreditPolicy disabled() {
        return new SupportCreditPolicy(false, "", Map.of(), 0, 0, 0, Duration.ofHours(1), Duration.ofSeconds(30), Map.of());
    }

    public List<SupportProduct> catalog() {
        if (!enabled) return List.of();
        return cosmeticPrices.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> new SupportProduct(entry.getKey(), entry.getValue())).toList();
    }

    public static SupportCreditPolicy from(ConfigurationSection config) {
        ConfigurationSection section = config.getConfigurationSection("support-credit");
        if (section == null) return disabled();
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        ConfigurationSection keySection = section.getConfigurationSection("trusted-keys");
        if (keySection != null) keySection.getValues(false).forEach((id, encoded) -> {
            try {
                if (!(encoded instanceof String value) || value.length() > 256) throw new IllegalArgumentException("Invalid public key encoding");
                byte[] bytes = Base64.getDecoder().decode(value);
                keys.put(id, KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes)));
            } catch (Exception error) {
                throw new IllegalArgumentException("Invalid Ed25519 public key: " + id, error);
            }
        });
        Map<SupportCosmetic, Long> prices = new LinkedHashMap<>();
        ConfigurationSection catalog = section.getConfigurationSection("cosmetic-prices");
        if (catalog != null) catalog.getValues(false).forEach((id, cost) -> {
            SupportCosmetic cosmetic = SupportCosmetic.parse(id).orElseThrow(
                    () -> new IllegalArgumentException("Unreviewed support cosmetic: " + id));
            prices.put(cosmetic, integer(cost, "cosmetic price"));
        });
        return new SupportCreditPolicy(section.getBoolean("enabled", false), section.getString("audience", ""), keys,
                integer(section.get("maximum-per-order", 0L), "maximum-per-order"),
                integer(section.get("player-weekly-limit", 0L), "player-weekly-limit"),
                integer(section.get("global-weekly-limit", 0L), "global-weekly-limit"),
                Duration.ofSeconds(integer(section.get("maximum-receipt-lifetime-seconds", 3600L), "receipt lifetime")),
                Duration.ofSeconds(integer(section.get("allowed-clock-skew-seconds", 30L), "clock skew")), prices);
    }

    private static long integer(Object value, String field) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Support credit " + field + " must be an integer", error); }
    }
}
