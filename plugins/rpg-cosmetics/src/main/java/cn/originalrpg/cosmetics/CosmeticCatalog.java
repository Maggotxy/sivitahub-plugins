package cn.originalrpg.cosmetics;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 外观解锁目录：把「渲染模块到底在为什么外观收费/解锁」写下来（G-205）。
 *
 * <p>文档曾把外观定价记在 {@code rpg-cosmetics}，实际收费发生在 {@code rpg-commerce}。
 * 结论是定价本来就该在收费模块（星辉账本、周额度、幂等键都在那里），文档已订正。
 * 但只订正文档会留下一个真实风险：{@code rpg-cosmetics} 只硬编码 PDC 解锁键与凭证 marker，
 * 一旦 commerce 侧改了 id / marker / 价格，cosmetics 会静默按旧语义渲染。
 *
 * <p>因此这里把 cosmetics 侧真正依赖的三件事声明成受审目录，并在启动时与 commerce 的
 * 配置交叉校验（见 {@code RpgCosmeticsPlugin#validateCommerceCatalog}）：
 * 商品 id、凭证 marker、星辉价格。任一不一致即拒绝启用该外观，而不是继续渲染一个
 * 玩家已买不到或买错价的东西。
 */
enum CosmeticCatalog {
    // 只登记「哪个外观」（用模块内的稳定 id 字符串），不引用枚举实例：
    // 引用实例会让 CosmeticCatalog 与 AuraType/TrailStyle 形成类初始化循环。
    PRISMATIC_AURA("prismatic-aura-voucher", "founder_cosmetic", 1600L, Kind.AURA, "prismatic"),
    ELYTRA_WINGS("elytra-wings-voucher", "elytra_wings", 480L, Kind.TRAIL, "wings"),
    ELYTRA_COMET("elytra-comet-voucher", "elytra_comet", 960L, Kind.TRAIL, "comet");

    /** 该凭证解锁的外观类别。 */
    enum Kind { AURA, TRAIL }

    private final String productId;
    private final String voucherMarker;
    private final long starlightCost;
    private final Kind kind;
    private final String cosmeticId;

    CosmeticCatalog(String productId, String voucherMarker, long starlightCost, Kind kind, String cosmeticId) {
        this.productId = productId;
        this.voucherMarker = voucherMarker;
        this.starlightCost = starlightCost;
        this.kind = kind;
        this.cosmeticId = cosmeticId;
    }

    String productId() {
        return productId;
    }

    String voucherMarker() {
        return voucherMarker;
    }

    long starlightCost() {
        return starlightCost;
    }

    Kind kind() {
        return kind;
    }

    /** 被解锁外观在所属模块内的稳定 id（{@link AuraType#id()} / {@link TrailStyle#id()}）。 */
    String cosmeticId() {
        return cosmeticId;
    }

    static Optional<CosmeticCatalog> forMarker(String marker) {
        if (marker == null) return Optional.empty();
        for (CosmeticCatalog entry : values()) {
            if (entry.voucherMarker.equals(marker)) return Optional.of(entry);
        }
        return Optional.empty();
    }

    static Optional<CosmeticCatalog> forProductId(String id) {
        if (id == null) return Optional.empty();
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (CosmeticCatalog entry : values()) {
            if (entry.productId.equals(normalized)) return Optional.of(entry);
        }
        return Optional.empty();
    }

    /** 目录的稳定视图，便于测试与日志逐条输出。 */
    static Map<String, Long> declaredCosts() {
        Map<String, Long> costs = new LinkedHashMap<>();
        for (CosmeticCatalog entry : values()) costs.put(entry.productId, entry.starlightCost);
        return Map.copyOf(costs);
    }
}
