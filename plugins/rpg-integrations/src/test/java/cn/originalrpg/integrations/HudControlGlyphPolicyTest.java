package cn.originalrpg.integrations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HudControlGlyphPolicyTest {
    @Test
    void selectsOnlyReviewedChineseAndEnglishBitmapLabels() {
        assertEquals("<image:control_cast_zh>", HudControlGlyphPolicy.glyphForLocale("zh_cn", true));
        assertEquals("<image:control_cycle_zh>", HudControlGlyphPolicy.glyphForLocale(null, false));
        assertEquals("<image:control_cast_en>", HudControlGlyphPolicy.glyphForLocale("en_us", true));
        assertEquals("<image:control_cycle_en>", HudControlGlyphPolicy.glyphForLocale("EN_GB", false));
    }
}
