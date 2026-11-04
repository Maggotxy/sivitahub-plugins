package cn.originalrpg.api.i18n;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfiguredDisplayTextTest {
    @Test
    void untouchedShippedNameFollowsSelectedLanguage() {
        assertEquals("Guide Aria", ConfiguredDisplayText.resolve("导览阿莉娅", "导览阿莉娅", null, "Guide Aria"));
        assertEquals("Guide Aria", ConfiguredDisplayText.resolve(null, "导览阿莉娅", null, "Guide Aria"));
    }

    @Test
    void customizedLegacyNameSurvivesAnEnglishDefault() {
        assertEquals("Lamb's Guide", ConfiguredDisplayText.resolve("Lamb's Guide", "导览阿莉娅", null, "Guide Aria"));
    }

    @Test
    void explicitLocaleOverrideWinsAndBlankOverrideDoesNotEraseACustomName() {
        assertEquals("Our Welcome Guide", ConfiguredDisplayText.resolve("旧名称", "导览阿莉娅", "Our Welcome Guide", "Guide Aria"));
        assertEquals("Custom", ConfiguredDisplayText.resolve("Custom", "Stock", "  ", "Translated"));
    }
}
