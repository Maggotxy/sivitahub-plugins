package cn.originalrpg.api.model;

import java.util.Map;
import java.util.Set;

/**
 * Runtime-backed skill/variant pairs shared by persistence, casting and the
 * mutation station. Keeping this allow-list in the API prevents a YAML-only
 * variant from being sold before any combat code can execute it.
 */
public final class SkillVariantCatalog {
    private static final Map<String, Set<String>> IMPLEMENTED = Map.of(
            "heal", Set.of("INVERTED"),
            "war-cry", Set.of("IRON_VOW"),
            "shadow-step", Set.of("PREDATOR_MARK"),
            "fireball", Set.of("ARC_BURST"),
            "power-shot", Set.of("SPLIT_SHOT"),
            "improvisation", Set.of("DANGEROUS_GAMBIT")
    );

    private SkillVariantCatalog() {
    }

    public static Map<String, Set<String>> implemented() {
        return IMPLEMENTED;
    }

    public static Set<String> variants(String skillId) {
        return IMPLEMENTED.getOrDefault(skillId, Set.of());
    }

    public static boolean supports(String skillId, String variant) {
        return skillId != null && variant != null && variants(skillId).contains(variant);
    }
}
