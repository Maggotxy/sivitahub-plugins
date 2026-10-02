package cn.originalrpg.economy;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopIntegrityValidatorTest {
    @Test
    void blackMarketBuybackRequiresTheSamePeriodAndOfferSource() {
        assertTrue(ShopIntegrityValidator.matchesMerchantProvenance(
                "p123", "mutation-shards", "p123:mutation-shards"));
        assertFalse(ShopIntegrityValidator.matchesMerchantProvenance(
                "p123", "mutation-shards", "p122:mutation-shards"));
        assertFalse(ShopIntegrityValidator.matchesMerchantProvenance(
                "p123", "mutation-shards", "p123:ruins-cores"));
        assertFalse(ShopIntegrityValidator.matchesMerchantProvenance(
                "p123", "mutation-shards", null));
    }

    @Test
    void recipeReachabilityIsTransitive() {
        Set<Material> reached = ShopIntegrityValidator.reachable(Set.of(Material.TORCH), Map.of(
                Material.TORCH, Set.of(Material.REDSTONE_TORCH),
                Material.REDSTONE_TORCH, Set.of(Material.REPEATER)
        ));
        assertTrue(reached.containsAll(Set.of(Material.TORCH, Material.REDSTONE_TORCH, Material.REPEATER)));
    }

    @Test
    void supplyToCommissionPathFailsClosed() throws Exception {
        YamlConfiguration config = validConfiguration();
        ShopIntegrityValidator.Report report = ShopIntegrityValidator.validate(config,
                Set.of(Material.TORCH), Map.of(Material.TORCH, Set.of(Material.COBBLESTONE)));
        assertFalse(report.commissionEnabled("mason"));
        assertTrue(report.merchantOfferEnabled("shards"));
    }

    @Test
    void anySystemIssuedStoreItemConversionFailsClosed() throws Exception {
        YamlConfiguration config = validConfiguration();
        ShopIntegrityValidator.Report report = ShopIntegrityValidator.validate(config,
                Set.of(Material.AMETHYST_SHARD), Map.of(
                        Material.AMETHYST_SHARD, Set.of(Material.AMETHYST_BLOCK),
                        Material.AMETHYST_BLOCK, Set.of(Material.COBBLESTONE)));
        assertFalse(report.commissionEnabled("mason"));
        assertTrue(report.systemIssuedClosure().contains(Material.COBBLESTONE));
    }

    @Test
    void evidenceGatedCommissionAndMarkedOfferPass() throws Exception {
        ShopIntegrityValidator.Report report = ShopIntegrityValidator.validate(validConfiguration(),
                Set.of(Material.TORCH), Map.of());
        assertTrue(report.commissionEnabled("mason"));
        assertTrue(report.merchantOfferEnabled("shards"));
    }

    @Test
    void excessiveMeritRewardFailsClosed() throws Exception {
        YamlConfiguration config = validConfiguration();
        config.set("commissions.mason.merit", 3);
        ShopIntegrityValidator.Report report = ShopIntegrityValidator.validate(config,
                Set.of(Material.TORCH), Map.of());
        assertFalse(report.commissionEnabled("mason"));
    }

    private static YamlConfiguration validConfiguration() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                commissions-policy:
                  total-daily-limit: 3
                  minimum-play-minutes: 60
                  default-offer-daily-limit: 1
                  default-evidence-cost: 1
                  maximum-gold-per-evidence: '250.00'
                  maximum-merit-per-evidence: 2
                commissions:
                  mason:
                    material: COBBLESTONE
                    amount: 64
                    gold: '55.00'
                black-market:
                  offers:
                    shards:
                      material: AMETHYST_SHARD
                      amount: 8
                      marker: adaptive
                      marker-value: mutation-shard
                      base-price: '900.00'
                      sell-multiplier-minimum: '0.80'
                      sell-multiplier-maximum: '1.80'
                      purchase-limit: 4
                      buyback-limit: 2
                """);
        return config;
    }
}
