package cn.originalrpg.api.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps inventory lore before it reaches the client tooltip renderer.
 *
 * <p>Vanilla inventory lore does not wrap automatically. The weighted limit
 * treats CJK/full-width glyphs as two Latin units so Chinese and English use a
 * similar visual width without depending on an optional resource-pack font.</p>
 */
public final class MenuLoreLayout {
    public static final int DEFAULT_MAX_UNITS = 44;

    private MenuLoreLayout() {
    }

    public static List<String> wrap(String value) {
        return wrap(value, DEFAULT_MAX_UNITS);
    }

    public static List<String> wrap(String value, int maximumUnits) {
        if (value == null || value.isBlank()) return List.of();
        int limit = Math.clamp(maximumUnits, 12, 80);
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.strip().split("\\R", -1)) {
            if (paragraph.isBlank()) continue;
            StringBuilder current = new StringBuilder();
            int currentUnits = 0;
            for (String token : paragraph.strip().split("\\s+")) {
                int tokenUnits = units(token);
                if (tokenUnits <= limit) {
                    int separator = current.isEmpty() ? 0 : 1;
                    if (!current.isEmpty() && currentUnits + separator + tokenUnits > limit) {
                        lines.add(current.toString());
                        current.setLength(0);
                        currentUnits = 0;
                        separator = 0;
                    }
                    if (separator > 0) current.append(' ');
                    current.append(token);
                    currentUnits += separator + tokenUnits;
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                    currentUnits = 0;
                }
                int offset = 0;
                while (offset < token.length()) {
                    StringBuilder part = new StringBuilder();
                    int partUnits = 0;
                    while (offset < token.length()) {
                        int codePoint = token.codePointAt(offset);
                        int codePointUnits = units(codePoint);
                        if (!part.isEmpty() && partUnits + codePointUnits > limit) break;
                        part.appendCodePoint(codePoint);
                        partUnits += codePointUnits;
                        offset += Character.charCount(codePoint);
                    }
                    if (offset < token.length()) lines.add(part.toString());
                    else {
                        current.append(part);
                        currentUnits = partUnits;
                    }
                }
            }
            if (!current.isEmpty()) lines.add(current.toString());
        }
        return List.copyOf(lines);
    }

    static int units(String value) {
        return value.codePoints().map(MenuLoreLayout::units).sum();
    }

    private static int units(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ? 2 : 1;
    }
}
