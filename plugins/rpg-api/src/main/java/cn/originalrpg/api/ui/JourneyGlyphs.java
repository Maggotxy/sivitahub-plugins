package cn.originalrpg.api.ui;

import cn.originalrpg.api.model.JourneyStep;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.util.Objects;

/** Original eight-step journey art exposed as lightweight resource-pack text glyphs. */
public final class JourneyGlyphs {
    public static final char HUB = '\uE100';
    public static final char CLASS = '\uE101';
    public static final char ATTRIBUTE = '\uE102';
    public static final char EXPLORE = '\uE103';
    public static final char HUNT = '\uE104';
    public static final char FORGE = '\uE105';
    public static final char REVEAL = '\uE106';
    public static final char DUNGEON = '\uE107';
    public static final Key FONT = Key.key("originalrpg", "journey");

    private JourneyGlyphs() {
    }

    public static char glyph(JourneyStep step) {
        return switch (Objects.requireNonNull(step, "step")) {
            case HUB -> HUB;
            case CLASS -> CLASS;
            case ATTRIBUTE -> ATTRIBUTE;
            case EXPLORE -> EXPLORE;
            case HUNT -> HUNT;
            case FORGE -> FORGE;
            case REVEAL -> REVEAL;
            case DUNGEON -> DUNGEON;
        };
    }

    public static Component icon(JourneyStep step) {
        return Component.text(String.valueOf(glyph(step))).font(FONT);
    }

    public static Component withIcon(JourneyStep step, Component content) {
        return Component.empty().append(icon(step)).append(Component.space()).append(content);
    }
}
