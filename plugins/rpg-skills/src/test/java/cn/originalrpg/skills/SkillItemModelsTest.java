package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SkillItemModelsTest {
    @Test
    void everyConcreteSkillHasOneStableNativeItemModelKey() {
        assertEquals(42, SkillCatalog.IMPLEMENTED.size());
        assertEquals(42, SkillCatalog.IMPLEMENTED.stream()
                .map(SkillItemModels::key).distinct().count());
        for (String skill : SkillCatalog.IMPLEMENTED) {
            assertEquals("skill_" + skill, SkillItemModels.key(skill));
        }
        assertThrows(IllegalArgumentException.class, () -> SkillItemModels.key("future-lore-only-skill"));
    }
}
