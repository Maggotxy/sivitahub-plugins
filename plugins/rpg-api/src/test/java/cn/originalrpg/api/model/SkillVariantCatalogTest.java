package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillVariantCatalogTest {
    @Test
    void everyBaseClassOwnsOneStrictlyWhitelistedMutation() {
        assertEquals(Set.of("heal", "war-cry", "shadow-step", "fireball", "power-shot", "improvisation"),
                SkillVariantCatalog.implemented().keySet());
        assertTrue(SkillVariantCatalog.implemented().values().stream().allMatch(variants -> variants.size() == 1));
        assertTrue(SkillVariantCatalog.supports("power-shot", "SPLIT_SHOT"));
        assertFalse(SkillVariantCatalog.supports("power-shot", "INVERTED"));
        assertFalse(SkillVariantCatalog.supports("unknown", "SPLIT_SHOT"));
    }
}
