package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HubNpcLabelPolicyTest {
    @Test
    void preservesShortChineseAndEnglishIdentityPlates() {
        assertEquals("职业导师 · 砺心", HubNpcLabelPolicy.label("职业导师 · 砺心", "class"));
        assertEquals("Forgekeeper · Arlan", HubNpcLabelPolicy.label("Forgekeeper · Arlan", "forge"));
    }

    @Test
    void foldsAllConfiguredTextIntoOneLine() {
        String label = HubNpcLabelPolicy.label("  职业导师\n\t·   砺心\u0007  ", "class");

        assertEquals("职业导师 · 砺心", label);
        assertFalse(label.contains("\n"));
        assertFalse(label.contains("\t"));
    }

    @Test
    void truncatesByCodePointWithoutSplittingEmoji() {
        String label = HubNpcLabelPolicy.label(
                "12345678901234567890123🧭extra", "guide");

        assertEquals(HubNpcLabelPolicy.MAX_CODE_POINTS, label.codePointCount(0, label.length()));
        assertTrue(label.endsWith("…"));
    }

    @Test
    void usesSafeFallbackForBlankConfiguration() {
        assertEquals("guide", HubNpcLabelPolicy.label("\n\t", "guide"));
        assertEquals("NPC", HubNpcLabelPolicy.label(null, null));
    }
}
