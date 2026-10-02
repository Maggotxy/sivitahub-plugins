package cn.originalrpg.skills;

import java.util.Set;

/** Book IDs whose settlement semantics are concrete in this release. */
final class SkillBookCatalog {
    static final Set<String> IMPLEMENTED = Set.of(
            "common-insight", "ancient-echo", "cinnabar-revelation", "silent-thunder");
    static final String SKILL_POINTS = "skill-points";

    private SkillBookCatalog() {
    }
}
