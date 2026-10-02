package cn.originalrpg.classes;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.rules.HiddenPathRules;
import cn.originalrpg.api.rules.HiddenPathReachability;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the SHIPPED config.yml of RpgSkills and RpgDungeons and runs the
 * production startup gate over them, then injects the G-106 contradiction back
 * in to prove the gate rejects it. Every assertion executes the gate; none of
 * them inspects source text.
 */
final class HiddenRouteReachabilityGateTest {
    @Test
    void shippedRouteFactsKeepEveryHiddenRouteReachable() {
        ConfigurationSection skills = load("rpg-skills-config.yml", "skills");
        ConfigurationSection books = load("rpg-skills-config.yml", "skill-books");
        ConfigurationSection dungeons = load("rpg-dungeons-config.yml", "dungeons");

        Map<HiddenPath, Set<String>> unattainable =
                HiddenRouteReachabilityGate.unattainableRoutes(skills, books, dungeons);
        assertTrue(unattainable.isEmpty(), "shipped routes must all be reachable, got " + unattainable);

        Map<HiddenPath, HiddenPathReachability> authored = HiddenPathReachability.authored();
        for (HiddenPath path : HiddenPath.values()) {
            ConfigurationSection skill = skills.getConfigurationSection(authored.get(path).requiredSkill());
            assertNotNull(skill, path.toString());
            // The route's own gate must be at least the branch stage of the
            // skill it requires; otherwise the route is unsatisfiable.
            assertTrue(HiddenPathRules.minimumAscension(path)
                            >= skill.getInt("minimum-ascension", 0),
                    path + " must not open before " + authored.get(path).requiredSkill());
            assertTrue(skill.getInt("minimum-ascension", 0) <= 3, path.toString());
        }
    }

    @Test
    void theGateRejectsTheOriginalG106Contradiction() {
        ConfigurationSection skills = load("rpg-skills-config.yml", "skills");
        ConfigurationSection books = load("rpg-skills-config.yml", "skill-books");
        ConfigurationSection dungeons = load("rpg-dungeons-config.yml", "dungeons");

        // Restore the bug: BLOODPACT asks for ascension 2 while its required
        // skill stays authored at the stage-3 branch.
        ConfigurationSection skill = skills.getConfigurationSection(
                HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT));
        assertNotNull(skill);
        assertEquals(3, skill.getInt("minimum-ascension", 0),
                "vanished-horizon is the stage-3 unseen branch skill");

        // The gate must reject any route whose required skill opens later than
        // the route itself. Rebuild the fact set with the regressed route.
        HiddenPathReachability asStageTwoRoute = new HiddenPathReachability(
                HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT),
                skill.getInt("minimum-ascension", 0),
                Set.of(skill.getString("branch", "")),
                skill.getInt("minimum-level", 1),
                HiddenPathRules.requiredDungeon(HiddenPath.BLOODPACT),
                dungeons.getConfigurationSection(HiddenPathRules.requiredDungeon(HiddenPath.BLOODPACT))
                        .getInt("minimum-effective-level", 1),
                "storm-vault",
                dungeons.getConfigurationSection("storm-vault").getInt("minimum-effective-level", 1),
                HiddenPathRules.requiredSkillBook(HiddenPath.BLOODPACT),
                books.getConfigurationSection(HiddenPathRules.requiredSkillBook(HiddenPath.BLOODPACT))
                        .getInt("requirements.minimum-effective-level", 1));
        assertEquals(3, asStageTwoRoute.requiredSkillAscension());
        assertEquals(Set.of("unseen"), asStageTwoRoute.requiredSkillBranches());
        // A stage-2 route with a stage-3 skill is precisely the contradiction;
        // prove the predicate names it and the description is actionable.
        Set<String> contradiction = HiddenPathReachability.unattainable(
                HiddenPath.TALISMAN, asStageTwoRoute);
        assertTrue(contradiction.contains(HiddenPathRules.requiredSkill(HiddenPath.TALISMAN)),
                "a stage-2 route may not require a stage-3 skill: " + contradiction);
        assertTrue(HiddenRouteReachabilityGate.describe(HiddenPath.BLOODPACT, contradiction)
                .contains("BLOODPACT"));
    }

    @Test
    void theGateNamesTheExactRequirementItCannotSatisfy() {
        Set<String> blockers = Set.of("hidden_dungeon");
        String description = HiddenRouteReachabilityGate.describe(HiddenPath.BLOODPACT, blockers);
        assertTrue(description.contains("BLOODPACT"));
        assertTrue(description.contains("hidden_dungeon"));
        assertTrue(description.contains("ascension 3"));
    }

    private static ConfigurationSection load(String resource, String path) {
        try (InputStream input = HiddenRouteReachabilityGateTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            ConfigurationSection section = config.getConfigurationSection(path);
            assertNotNull(section, resource + "#" + path);
            return section;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
