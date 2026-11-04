package cn.originalrpg.integrations;

import cn.originalrpg.api.model.SkillCooldownSnapshot;
import cn.originalrpg.api.model.SkillHudSnapshot;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/** Rendering shared by PAPI requests and the compact HUD, including loading states. */
final class HudPlaceholderValues {
    private static final Set<String> NUMERIC = Set.of(
            "hud_ready", "mana", "mana_percent", "skills_count", "skills_ready", "skill_selected",
            "ascension", "level", "local_level", "effective_level", "effective_level_cap",
            "experience", "experience_stage_budget", "experience_stage_spent",
            "experience_stage_remaining", "experience_total", "energy", "energy_max",
            "gold", "merit", "starlight", "cache_age_ms");

    private HudPlaceholderValues() {
    }

    static String missingValue(String parameter, int slotCount) {
        // BetterHud may evaluate numeric children before the hud_ready guard.
        // A denominator must remain positive even while data is unavailable.
        if (parameter.equals("mana_max")) return "1";
        if (NUMERIC.contains(parameter)) return "0";
        SkillPlaceholderKey key = SkillPlaceholderKey.parse(parameter, slotCount);
        if (key != null && (key.property().equals("cooldown") || key.property().equals("selected"))) {
            return "0";
        }
        return "";
    }

    static String skillValue(
            SkillHudSnapshot snapshot, List<SkillCooldownSnapshot> slots, int selectedSlot,
            String parameter, int slotCount, long now, BiFunction<String, String, String> localized
    ) {
        if (snapshot == null) return missingValue(parameter, slotCount);
        return switch (parameter) {
            case "mana" -> Integer.toString(snapshot.mana());
            case "mana_max" -> Integer.toString(Math.max(1, snapshot.manaCap()));
            case "mana_percent" -> Integer.toString(snapshot.manaCap() == 0 ? 0
                    : (int) Math.round(snapshot.mana() * 100.0 / snapshot.manaCap()));
            case "skills_count" -> Integer.toString(snapshot.learnedSkills().size());
            case "skills_ready" -> Long.toString(snapshot.learnedSkills().stream()
                    .filter(entry -> !entry.emptySlot() && entry.ready(now)).count());
            case "skills_cooldowns" -> snapshot.learnedSkills().stream()
                    .map(entry -> entry.skillId() + ":" + entry.remainingSeconds(now))
                    .collect(java.util.stream.Collectors.joining(","));
            case "skill_selected" -> validSelection(slots, selectedSlot, slotCount)
                    ? Integer.toString(selectedSlot + 1) : "0";
            default -> slotValue(slots, selectedSlot, parameter, slotCount, now, localized);
        };
    }

    static String slotValue(
            List<SkillCooldownSnapshot> slots, int selectedSlot, String parameter,
            int slotCount, long now, BiFunction<String, String, String> localized
    ) {
        SkillPlaceholderKey key = SkillPlaceholderKey.parse(parameter, slotCount);
        if (key == null) return null;
        int index = key.index();
        if (index >= slots.size() || slots.get(index).emptySlot()) {
            return key.property().equals("status") ? localized.apply("betterhud.skill-empty", "Empty")
                    : missingValue(parameter, slotCount);
        }
        SkillCooldownSnapshot skill = slots.get(index);
        long remaining = skill.remainingSeconds(now);
        return switch (key.property()) {
            case "id" -> skill.skillId();
            case "name" -> localized.apply("skills.names." + skill.skillId(), skill.skillId());
            case "cooldown" -> Long.toString(remaining);
            case "timer" -> remaining == 0L ? "" : Long.toString(remaining);
            case "status" -> remaining == 0L ? localized.apply("betterhud.skill-ready", "Ready")
                    : localized.apply("betterhud.skill-cooldown", remaining + "s")
                    .replace("{seconds}", Long.toString(remaining));
            case "glyph" -> "<image:skill_" + skill.skillId() + ">";
            case "selected" -> selectedSlot == index ? "1" : "0";
            default -> "";
        };
    }

    private static boolean validSelection(List<SkillCooldownSnapshot> slots, int selected, int slotCount) {
        return selected >= 0 && selected < slotCount && selected < slots.size()
                && !slots.get(selected).emptySlot();
    }
}
