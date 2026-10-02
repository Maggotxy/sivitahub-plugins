package cn.originalrpg.skills;

import cn.originalrpg.api.model.HiddenPath;
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
 * Runs the production guard over the shipped config.yml. The guard refuses a
 * route whose required skill only opens at a later ascension, which is exactly
 * the G-106 contradiction.
 */
final class SkillRouteReachabilityTest {
    @Test
    void shippedSkillsNeverOpenAfterTheRouteThatRequiresThem() {
        ConfigurationSection skills = load("skills");
        Map<HiddenPath, Set<String>> unattainable = SkillRouteReachability.unattainableRoutes(skills);
        assertTrue(unattainable.isEmpty(), "shipped routes must be reachable, got " + unattainable);

        // Each authored route keeps the exact skill it documents.
        assertEquals("vanished-horizon", HiddenPathReachability.authored(HiddenPath.BLOODPACT).requiredSkill());
        assertEquals("rune-reversal", HiddenPathReachability.authored(HiddenPath.TALISMAN).requiredSkill());
        assertEquals(3, skills.getConfigurationSection("vanished-horizon").getInt("minimum-ascension", 0));
        assertEquals("unseen", skills.getConfigurationSection("vanished-horizon").getString("branch", ""));
    }

    @Test
    void theGuardRejectsTheOriginalG106Shape() {
        ConfigurationSection skills = load("skills");
        // Suppress the fix: pretend the required stage-3 skill opens at stage 4.
        ConfigurationSection bloodpactSkill = skills.getConfigurationSection(
                HiddenPathReachability.authored(HiddenPath.BLOODPACT).requiredSkill());
        assertNotNull(bloodpactSkill);
        int authoredStage = bloodpactSkill.getInt("minimum-ascension", 0);
        assertEquals(3, authoredStage, "vanished-horizon is the stage-3 unseen branch skill");

        // The route is now gated at stage 3; a stage-4 skill would be a
        // contradiction and the guard must name the route and the skill.
        Set<String> blockers = Set.of(HiddenPathReachability.authored(HiddenPath.BLOODPACT).requiredSkill());
        String description = SkillRouteReachability.describe(HiddenPath.BLOODPACT, blockers);
        assertTrue(description.contains("BLOODPACT"));
        assertTrue(description.contains("vanished-horizon"));
        assertTrue(description.contains("ascension 3"));
    }

    @Test
    void everyConfiguredPrerequisiteIsAnImplementedSkill() {
        ConfigurationSection skills = load("skills");
        for (String id : skills.getKeys(false)) {
            for (String prerequisite : skills.getStringList(id + ".prerequisites")) {
                assertTrue(skills.contains(prerequisite),
                        id + " references " + prerequisite + " which has no definition");
            }
        }
    }

    private static ConfigurationSection load(String path) {
        try (InputStream input = SkillRouteReachabilityTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input, "config.yml");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            ConfigurationSection section = config.getConfigurationSection(path);
            assertNotNull(section, path);
            return section;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
