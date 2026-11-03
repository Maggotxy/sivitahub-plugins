package cn.originalrpg.integrations;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the public, one-based skill-slot placeholder into a bounded list index. */
record SkillPlaceholderKey(int index, String property) {
    private static final Pattern PARAMETER = Pattern.compile(
            "skill_([1-9][0-9]*)_(id|name|cooldown|timer|status|glyph|selected)");

    static SkillPlaceholderKey parse(String parameter, int slotCount) {
        if (parameter == null || slotCount < 1) return null;
        Matcher matcher = PARAMETER.matcher(parameter);
        if (!matcher.matches()) return null;
        try {
            int slot = Integer.parseInt(matcher.group(1));
            return slot <= slotCount ? new SkillPlaceholderKey(slot - 1, matcher.group(2)) : null;
        } catch (NumberFormatException exception) {
            // Placeholder text is external input; an oversized number is an unknown key.
            return null;
        }
    }
}
