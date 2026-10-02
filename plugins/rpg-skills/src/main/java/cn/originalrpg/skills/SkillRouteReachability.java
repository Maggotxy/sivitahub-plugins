package cn.originalrpg.skills;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.rules.HiddenPathReachability;
import cn.originalrpg.api.rules.HiddenPathRules;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The skill half of the hidden-route contract. Every route names a skill that
 * must already be learned, and that skill's own ascension gate must be no later
 * than the route's. G-106 shipped BLOODPACT at ascension 2 while
 * {@code vanished-horizon} only opens on the stage-3 {@code unseen} branch, so
 * the route was permanently unreachable.
 *
 * <p>This class is called at startup so the contradiction fails the plugin
 * instead of silently hiding the route forever.
 */
final class SkillRouteReachability {
    private SkillRouteReachability() {
    }

    /**
     * @return routes whose required skill cannot be learned by the time the
     *         route itself opens. Empty means every authored route agrees.
     */
    static Map<HiddenPath, Set<String>> unattainableRoutes(ConfigurationSection skillRoot) {
        Objects.requireNonNull(skillRoot, "skillRoot");
        Map<HiddenPath, HiddenPathReachability> authored = HiddenPathReachability.authored();
        Map<HiddenPath, Set<String>> result = new LinkedHashMap<>();
        for (HiddenPath path : HiddenPath.values()) {
            HiddenPathReachability route = authored.get(path);
            ConfigurationSection skill = Objects.requireNonNull(
                    skillRoot.getConfigurationSection(route.requiredSkill()),
                    "required skill " + route.requiredSkill() + " of " + path);
            int skillAscension = skill.getInt("minimum-ascension", 0);
            if (skillAscension > HiddenPathRules.minimumAscension(path)) {
                result.put(path, Set.of(HiddenPathRules.requiredSkill(path)));
            }
        }
        return Map.copyOf(result);
    }

    /** Renders the startup failure for a contradiction. */
    static String describe(HiddenPath path, Set<String> blockers) {
        return "Hidden route " + path + " requires " + blockers + " which opens after its own ascension "
                + HiddenPathRules.minimumAscension(path) + " gate";
    }
}
