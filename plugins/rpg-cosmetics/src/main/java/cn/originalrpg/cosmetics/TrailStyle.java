package cn.originalrpg.cosmetics;

import java.util.Locale;
import java.util.Optional;

enum TrailStyle {
    OFF("off", Shape.NONE, null),
    WAKE("wake", Shape.WAKE, null),
    // G-205：marker 的唯一权威是 CosmeticCatalog（启动时与 rpg-commerce 对账），
    // 这里保留的是渲染侧标识。两者不一致由 CosmeticCatalogTest 直接断言。
    WINGS("wings", Shape.WINGS, "elytra_wings"),
    COMET("comet", Shape.COMET, "elytra_comet");

    private final String id;
    private final Shape shape;
    private final String voucherMarker;

    TrailStyle(String id, Shape shape, String voucherMarker) {
        this.id = id;
        this.shape = shape;
        this.voucherMarker = voucherMarker;
    }

    String id() {
        return id;
    }

    Shape shape() {
        return shape;
    }

    String voucherMarker() {
        return voucherMarker;
    }

    boolean voucherRequired() {
        return voucherMarker != null;
    }

    static Optional<TrailStyle> parse(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (TrailStyle style : values()) {
            if (style.id.equals(normalized)) return Optional.of(style);
        }
        return Optional.empty();
    }

    static Optional<TrailStyle> forVoucher(String marker) {
        if (marker == null) return Optional.empty();
        for (TrailStyle style : values()) {
            if (marker.equals(style.voucherMarker)) return Optional.of(style);
        }
        return Optional.empty();
    }

    enum Shape { NONE, WAKE, WINGS, COMET }
}
