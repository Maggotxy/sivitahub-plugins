package cn.originalrpg.cosmetics;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosmeticCatalogTest {
    @Test
    void theCatalogNamesOneProductPerPurchasableCosmetic() {
        assertEquals(3, CosmeticCatalog.values().length);
        assertEquals(Optional.of(CosmeticCatalog.PRISMATIC_AURA),
                CosmeticCatalog.forMarker("founder_cosmetic"));
        assertEquals(Optional.of(CosmeticCatalog.ELYTRA_WINGS),
                CosmeticCatalog.forMarker("elytra_wings"));
        assertEquals(Optional.of(CosmeticCatalog.ELYTRA_COMET),
                CosmeticCatalog.forMarker("elytra_comet"));
        assertTrue(CosmeticCatalog.forMarker("unknown").isEmpty());
        assertTrue(CosmeticCatalog.forMarker(null).isEmpty());
        // 大小写不敏感地按商品 id 反查，供启动对账使用。
        assertEquals(Optional.of(CosmeticCatalog.ELYTRA_COMET),
                CosmeticCatalog.forProductId("ELYTRA-COMET-VOUCHER"));
        assertTrue(CosmeticCatalog.forProductId("not-a-product").isEmpty());
    }

    @Test
    void trailAndAuraUnlockMarkersComeFromTheCatalog() {
        // G-205 行为级验收：渲染侧的 marker 必须与定价目录同源，不能各留一份字面量。
        assertEquals(CosmeticCatalog.ELYTRA_WINGS.voucherMarker(),
                TrailStyle.WINGS.voucherMarker());
        assertEquals(CosmeticCatalog.ELYTRA_COMET.voucherMarker(),
                TrailStyle.COMET.voucherMarker());
        assertEquals(Optional.of(TrailStyle.WINGS),
                TrailStyle.forVoucher(CosmeticCatalog.ELYTRA_WINGS.voucherMarker()));
        assertTrue(TrailStyle.forVoucher(CosmeticCatalog.ELYTRA_COMET.voucherMarker()).isPresent());
    }

    @Test
    void theCatalogMatchesTheCommercePricingTable() throws IOException {
        // G-205 的核心断言：定价在 rpg-commerce，渲染目录必须与之逐条一致。
        Map<String, Object> products = map(commerceConfig(), "products");
        for (CosmeticCatalog entry : CosmeticCatalog.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> product = (Map<String, Object>) products.get(entry.productId());
            assertNotNull(product, "rpg-commerce 缺少外观商品 " + entry.productId());
            assertEquals(entry.voucherMarker(), product.get("marker"),
                    entry.productId() + " 的 marker 与渲染侧不一致");
            assertEquals(entry.starlightCost(), ((Number) product.get("points")).longValue(),
                    entry.productId() + " 的星辉价格与渲染侧不一致");
        }
        assertEquals(1600L, CosmeticCatalog.PRISMATIC_AURA.starlightCost());
        assertEquals(480L, CosmeticCatalog.ELYTRA_WINGS.starlightCost());
        assertEquals(960L, CosmeticCatalog.ELYTRA_COMET.starlightCost());
    }

    @Test
    void theCatalogCoversEveryVoucherGatedCosmetic() {
        // 目录登记的每个外观都必须是真实存在的凭证门控外观，不能指向免费项。
        for (CosmeticCatalog entry : CosmeticCatalog.values()) {
            switch (entry.kind()) {
                case AURA -> assertEquals(CosmeticCatalog.Kind.AURA, entry.kind(),
                        entry.productId() + " 必须对应真实外观 " + entry.cosmeticId());
                case TRAIL -> assertTrue(TrailStyle.parse(entry.cosmeticId()).isPresent(),
                        entry.productId() + " 指向了不存在的外观 " + entry.cosmeticId());
            }
        }
        assertEquals("prismatic", CosmeticCatalog.PRISMATIC_AURA.cosmeticId());
        assertEquals("wings", CosmeticCatalog.ELYTRA_WINGS.cosmeticId());
        assertEquals("comet", CosmeticCatalog.ELYTRA_COMET.cosmeticId());
        assertTrue(AuraType.PRISMATIC.voucherRequired());
        assertTrue(TrailStyle.WINGS.voucherRequired());
        assertTrue(TrailStyle.COMET.voucherRequired());
    }

    @Test
    void auraClassInitializationDoesNotDependOnTheCatalog() {
        // 防止类初始化循环回归：两侧独立解析必须都成功。
        assertEquals(AuraType.PRISMATIC, AuraType.parse("prismatic").orElseThrow());
        assertEquals(TrailStyle.WINGS, TrailStyle.parse("wings").orElseThrow());
        assertEquals(CosmeticCatalog.PRISMATIC_AURA,
                CosmeticCatalog.forProductId("PRISMATIC-AURA-VOUCHER").orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> commerceConfig() throws IOException {
        Path root = repositoryRoot();
        try (InputStream input = Files.newInputStream(
                root.resolve("plugins/rpg-commerce/src/main/resources/config.yml"))) {
            return (Map<String, Object>) new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Cannot locate repository root from user.dir");
        return current;
    }
}
