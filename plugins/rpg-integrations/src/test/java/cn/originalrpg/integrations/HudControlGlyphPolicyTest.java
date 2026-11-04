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
    @Test
    void offlineHudLookupUsesServerLocaleInsteadOfAssumingChinese() {
        cn.originalrpg.api.service.MessageService messages = (cn.originalrpg.api.service.MessageService)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{cn.originalrpg.api.service.MessageService.class}, (proxy, method, args) -> {
                            if (!method.getName().equals("locale")) throw new AssertionError(method.getName());
                            org.junit.jupiter.api.Assertions.assertNull(args[0]);
                            return "en_us";
                        });
        assertEquals("<image:control_cycle_en>", HudControlGlyphPolicy.glyph(messages, null, false));
        assertEquals("<image:control_cast_en>", HudControlGlyphPolicy.glyph(null, null, true));
    }

}
