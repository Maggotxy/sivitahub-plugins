package cn.originalrpg.integrations;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillPlaceholderKeyTest {
    @Test
    void everyHudSlotExposesAllSupportedProperties() {
        List<String> properties = List.of("id", "name", "cooldown", "timer", "status", "glyph", "selected");
        for (int slot = 1; slot <= 4; slot++) {
            for (String property : properties) {
                SkillPlaceholderKey key = SkillPlaceholderKey.parse("skill_" + slot + "_" + property, 4);
                assertNotNull(key, "slot " + slot + " / " + property);
                assertEquals(slot - 1, key.index());
                assertEquals(property, key.property());
            }
        }
    }

    @Test
    void rejectsMalformedAndOutOfRangeKeysWithoutThrowing() {
        for (String parameter : List.of("skill_1-4_name", "skill_0_name", "skill_-1_name",
                "skill_5_name", "skill_01_name", "skill_2147483648_name",
                "skill_999999999999999999999999_name", "skill_1_unknown",
                "skill_1_name_extra", "prefix_skill_1_name", "skill_1_name\n", "")) {
            assertNull(SkillPlaceholderKey.parse(parameter, 4), parameter);
        }
        assertNull(SkillPlaceholderKey.parse(null, 4));
    }

    @Test
    void followsConfiguredBoundsWithoutCharacterClassRangeAssumptions() {
        assertEquals(new SkillPlaceholderKey(0, "name"), SkillPlaceholderKey.parse("skill_1_name", 1));
        assertNull(SkillPlaceholderKey.parse("skill_2_name", 1));
        assertEquals(new SkillPlaceholderKey(11, "name"), SkillPlaceholderKey.parse("skill_12_name", 12));
        assertNull(SkillPlaceholderKey.parse("skill_13_name", 12));
        assertNull(SkillPlaceholderKey.parse("skill_1_name", 0));
        assertNull(SkillPlaceholderKey.parse("skill_1_name", -1));
    }
}
