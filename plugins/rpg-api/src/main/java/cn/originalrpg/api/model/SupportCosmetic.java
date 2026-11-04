package cn.originalrpg.api.model;

import java.util.Optional;

/** Reviewed cosmetic-only entitlement keys. No item, currency, membership or gameplay target can be supplied. */
public enum SupportCosmetic {
    PRISMATIC_AURA("aura-prismatic", "aura", "prismatic"),
    ELYTRA_WINGS("trail-wings", "trail", "wings"),
    ELYTRA_COMET("trail-comet", "trail", "comet");

    private final String id;
    private final String category;
    private final String rendererId;

    SupportCosmetic(String id, String category, String rendererId) {
        this.id = id; this.category = category; this.rendererId = rendererId;
    }
    public String id() { return id; }
    public String category() { return category; }
    public String rendererId() { return rendererId; }
    public static Optional<SupportCosmetic> parse(String id) {
        if (id == null) return Optional.empty();
        for (SupportCosmetic value : values()) if (value.id.equals(id)) return Optional.of(value);
        return Optional.empty();
    }
}
