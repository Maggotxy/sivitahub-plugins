package cn.originalrpg.classes;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.rules.HiddenPathReachability;
import cn.originalrpg.api.rules.HiddenPathRules;
import cn.originalrpg.api.rules.ProgressionRules;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fail-fast gate for the shipped hidden routes. The route facts live in three
 * plugins (RpgSkills / RpgClasses / RpgDungeons), so the contradiction which
 * made BLOODPACT unreachable could only be seen once they were compared. This
 * class reads the three live configurations and refuses to enable the plugin
 * when a route names a gate its own ascension can never satisfy.
 *
 * <p>It deliberately does not weaken any route: the documented build (level 20,
 * Agi 32 / Sta 18, the required skill, the hidden dungeon and its book) stays
 * authored exactly as designed.
 */
final class HiddenRouteReachabilityGate {
    private HiddenRouteReachabilityGate() {
    }

    /**
     * @return the requirement codes which no player can ever satisfy, per route.
     *         An empty map means every route is reachable.
     */
    static Map<HiddenPath, Set<String>> unattainableRoutes(
            ConfigurationSection skillRoot,
            ConfigurationSection bookRoot,
            ConfigurationSection dungeonRoot
    ) {
        Objects.requireNonNull(skillRoot, "skillRoot");
        Objects.requireNonNull(bookRoot, "bookRoot");
        Objects.requireNonNull(dungeonRoot, "dungeonRoot");
        Map<HiddenPath, HiddenPathReachability> authored = HiddenPathReachability.authored();
        Map<HiddenPath, Set<String>> result = new java.util.LinkedHashMap<>();
        for (HiddenPath path : HiddenPath.values()) {
            HiddenPathReachability facts = dungeons(
                    path, skills(path, authored.get(path), skillRoot), bookRoot, dungeonRoot);
            Set<String> blockers = HiddenPathReachability.unattainable(path, facts);
            if (!blockers.isEmpty()) result.put(path, blockers);
        }
        return Map.copyOf(result);
    }

    /** Renders the startup failure for a contradiction. */
    static String describe(HiddenPath path, Set<String> blockers) {
        return "Hidden route " + path + " is unreachable: it requires " + new TreeSet<>(blockers)
                + ", which its own ascension " + HiddenPathRules.minimumAscension(path) + " gate cannot satisfy";
    }

    private static HiddenPathReachability skills(
            HiddenPath path, HiddenPathReachability authored, ConfigurationSection skillRoot
    ) {
        ConfigurationSection skill = Objects.requireNonNull(
                skillRoot.getConfigurationSection(authored.requiredSkill()),
                "required skill " + authored.requiredSkill() + " of " + path);
        return new HiddenPathReachability(
                authored.requiredSkill(),
                Math.clamp(skill.getInt("minimum-ascension", 0), 0, ProgressionRules.MAX_ASCENSION),
                Set.of(skill.getString("branch", "")),
                Math.max(1, skill.getInt("minimum-level", 1)),
                authored.requiredDungeon(),
                authored.hiddenDungeonEffectiveLevel(),
                authored.ticketSource(),
                authored.ticketSourceEffectiveLevel(),
                authored.requiredSkillBook(),
                authored.skillBookEffectiveLevel());
    }

    private static HiddenPathReachability dungeons(
            HiddenPath path, HiddenPathReachability authored,
            ConfigurationSection bookRoot, ConfigurationSection dungeonRoot
    ) {
        ConfigurationSection hidden = Objects.requireNonNull(
                dungeonRoot.getConfigurationSection(authored.requiredDungeon()),
                "hidden dungeon " + authored.requiredDungeon() + " of " + path);
        ConfigurationSection hiddenBook = Objects.requireNonNull(
                bookRoot.getConfigurationSection(hidden.getString("rewards.skill-book.id", "")),
                "skill book of " + authored.requiredDungeon());
        ConfigurationSection ticketSource = Objects.requireNonNull(
                dungeonRoot.getConfigurationSection(authored.ticketSource()),
                "ticket source " + authored.ticketSource() + " of " + path);
        return new HiddenPathReachability(
                authored.requiredSkill(),
                authored.requiredSkillAscension(),
                authored.requiredSkillBranches(),
                authored.requiredSkillEffectiveLevel(),
                authored.requiredDungeon(),
                Math.max(1, hidden.getInt("minimum-effective-level", 1)),
                authored.ticketSource(),
                Math.max(1, ticketSource.getInt("minimum-effective-level", 1)),
                hidden.getString("rewards.skill-book.id", authored.requiredSkillBook()),
                Math.max(1, hiddenBook.getInt("requirements.minimum-effective-level", 1)));
    }
}
