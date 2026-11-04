package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SupportCosmetic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SupportCreditPolicyTest {
    @Test void shippedConfigurationDoesNotEnableOrPriceRealMoneyCredit() throws Exception {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) current = current.getParent();
        assertNotNull(current);
        var config = YamlConfiguration.loadConfiguration(current.resolve("plugins/rpg-core/src/main/resources/config.yml").toFile());
        var policy = SupportCreditPolicy.from(config);
        assertFalse(policy.enabled()); assertTrue(policy.trustedKeys().isEmpty()); assertTrue(policy.catalog().isEmpty());
        assertEquals(0, policy.maximumPerOrder()); assertEquals(0, policy.playerWeeklyLimit());
    }

    @Test void explicitPublicKeyAudienceFiniteLimitsAndReviewedCosmeticsLoadWithoutPrivateMaterial() throws Exception {
        var config = configured();
        var policy = SupportCreditPolicy.from(config);
        assertTrue(policy.enabled()); assertEquals("deployment-a", policy.audience());
        assertEquals(120L, policy.cosmeticPrices().get(SupportCosmetic.PRISMATIC_AURA));
        assertEquals(1, policy.trustedKeys().size());
        assertEquals("EdDSA", policy.trustedKeys().get("bridge-a").getAlgorithm());
    }

    @Test void missingKeysUnknownProductsAndNonIntegralQuotasFailClosed() throws Exception {
        var config = configured(); config.set("support-credit.trusted-keys", null);
        assertThrows(IllegalArgumentException.class, () -> SupportCreditPolicy.from(config));
        var unknown = configured(); unknown.set("support-credit.cosmetic-prices.protection-stone", 10);
        assertThrows(IllegalArgumentException.class, () -> SupportCreditPolicy.from(unknown));
        var fractional = configured(); fractional.set("support-credit.player-weekly-limit", 1000.5);
        assertThrows(IllegalArgumentException.class, () -> SupportCreditPolicy.from(fractional));
        var unsafeQuota = configured(); unsafeQuota.set("support-credit.global-weekly-limit", 50);
        assertThrows(IllegalArgumentException.class, () -> SupportCreditPolicy.from(unsafeQuota));
    }

    @Test void privateOrWrongAlgorithmKeyEncodingCannotBeLoadedAsATrustedPublicKey() throws Exception {
        var privateKey = configured();
        privateKey.set("support-credit.trusted-keys.bridge-a", Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded()));
        assertThrows(IllegalArgumentException.class, () -> SupportCreditPolicy.from(privateKey));
        var rsa = configured();
        rsa.set("support-credit.trusted-keys.bridge-a", Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded()));
        assertThrows(IllegalArgumentException.class, () -> SupportCreditPolicy.from(rsa));
    }

    private static YamlConfiguration configured() throws Exception {
        var config = new YamlConfiguration();
        config.set("support-credit.enabled", true); config.set("support-credit.audience", "deployment-a");
        config.set("support-credit.trusted-keys.bridge-a", Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded()));
        config.set("support-credit.maximum-per-order", 500); config.set("support-credit.player-weekly-limit", 1000);
        config.set("support-credit.global-weekly-limit", 2000); config.set("support-credit.cosmetic-prices.aura-prismatic", 120);
        return config;
    }
}
