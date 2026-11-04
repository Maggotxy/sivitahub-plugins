package cn.originalrpg.cosmetics;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommerceCatalogAuditTest {
    @Test
    void readsInstalledConfigurationWithoutGuessingItsDataDirectory() {
        YamlConfiguration live = validCatalog();
        assertTrue(CommerceCatalogAudit.inspect(plugin(live)).isEmpty());
        live.set("products.elytra-wings-voucher.marker", "wrong_marker");
        var errors = CommerceCatalogAudit.inspect(plugin(live));
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("wrong_marker"));
    }

    @Test
    void detectsMissingProductsAndIndependentPriceDrift() {
        YamlConfiguration live = validCatalog();
        live.set("products.elytra-comet-voucher", null);
        live.set("products.prismatic-aura-voucher.points", 1);
        var errors = CommerceCatalogAudit.inspect(plugin(live));
        assertEquals(2, errors.size());
        assertTrue(errors.stream().anyMatch(s -> s.contains("elytra-comet-voucher")));
        assertTrue(errors.stream().anyMatch(s -> s.contains("1600") && s.contains("uses 1")));
    }

    @Test
    void commerceLoadsBeforeTheOptionalStartupReconciliation() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8)) {
            var descriptor = YamlConfiguration.loadConfiguration(reader);
            assertTrue(descriptor.getStringList("softdepend").contains("RpgCommerce"));
        }
    }

    private static YamlConfiguration validCatalog() {
        var config = new YamlConfiguration();
        for (CosmeticCatalog item : CosmeticCatalog.values()) {
            config.set("products." + item.productId() + ".marker", item.voucherMarker());
            config.set("products." + item.productId() + ".points", item.starlightCost());
        }
        return config;
    }

    private static Plugin plugin(YamlConfiguration config) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConfig")) return config;
                    throw new AssertionError("The audit must inspect the installed config, not a guessed path: " + method.getName());
                });
    }
}
