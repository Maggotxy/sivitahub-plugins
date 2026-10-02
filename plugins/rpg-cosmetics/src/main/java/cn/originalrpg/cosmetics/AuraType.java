package cn.originalrpg.cosmetics;

import java.util.Locale;
import java.util.Optional;

enum AuraType {
    OFF("off", Shape.NONE, false),
    EMBER("ember", Shape.HELIX, false),
    VERDANT("verdant", Shape.PULSE, false),
    ARCANE("arcane", Shape.ORBIT, false),
    TIDE("tide", Shape.WAVE, false),
    PRISMATIC("prismatic", Shape.DOUBLE_HELIX, true);

    private final String id;
    private final Shape shape;
    private final boolean voucherRequired;

    AuraType(String id, Shape shape, boolean voucherRequired) {
        this.id = id;
        this.shape = shape;
        this.voucherRequired = voucherRequired;
    }

    String id() {
        return id;
    }

    Shape shape() {
        return shape;
    }

    boolean voucherRequired() {
        return voucherRequired;
    }

    static Optional<AuraType> parse(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        for (AuraType type : values()) {
            if (type.id.equals(normalized)) return Optional.of(type);
        }
        return Optional.empty();
    }

    enum Shape { NONE, HELIX, PULSE, ORBIT, WAVE, DOUBLE_HELIX }
}
