package cn.originalrpg.skills;

import java.util.Locale;

/** Single naming contract shared by every authored skill-menu item model. */
final class SkillItemModels {
    private SkillItemModels() {
    }

    static String key(String skillId) {
        String normalized = skillId == null ? "" : skillId.toLowerCase(Locale.ROOT);
        if (!SkillCatalog.IMPLEMENTED.contains(normalized)) {
            throw new IllegalArgumentException("Unknown skill item model: " + skillId);
        }
        return "skill_" + normalized;
    }
}
