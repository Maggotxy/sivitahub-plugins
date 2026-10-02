package cn.originalrpg.guide;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTopicTest {
    private static final Set<String> HUB_ROLES = Set.of(
            "guide", "quests", "class", "skills", "forge", "enhancement",
            "dungeon", "market", "economy", "wilderness", "ai");

    @Test
    void everyVisibleTopicHasTrustedActionsAndAHubLocator() {
        assertEquals(18, HelpTopic.ordered().size());
        assertEquals(HelpTopic.ordered().size(),
                new HashSet<>(HelpTopic.ordered().stream().map(HelpTopic::id).toList()).size());

        for (HelpTopic topic : HelpTopic.ordered()) {
            assertTrue(topic.primaryAction().startsWith("open:")
                    || topic.primaryAction().startsWith("command:"));
            assertTrue(topic.secondaryAction().startsWith("open:")
                    || topic.secondaryAction().startsWith("command:"));
            assertTrue(HUB_ROLES.contains(topic.npcRole()));
            String currentRole = "wilderness";
            String effectiveRole = topic == HelpTopic.START || topic == HelpTopic.NPCS
                    ? currentRole : topic.npcRole();
            assertEquals("command:hubfind " + effectiveRole, topic.locatorAction(currentRole));
            assertEquals("command:hubfind " + effectiveRole, topic.visitAction(currentRole));
            assertEquals(topic, HelpTopic.find(topic.id().toUpperCase()).orElseThrow());
        }
    }

    @Test
    void currentJourneyTopicsHideNpcActionsForCombatOnlyGoals() {
        assertNull(HelpTopic.START.locatorAction(null));
        assertNull(HelpTopic.START.visitAction(null));
        assertNull(HelpTopic.NPCS.locatorAction(null));
        assertNull(HelpTopic.NPCS.visitAction(null));
        assertEquals("command:hubfind forge", HelpTopic.EQUIPMENT.locatorAction(null));
    }

    @Test
    void helpModelsOnlyUseExistingSemanticResourcePackAssets() {
        Set<String> supported = Set.of(
                "traveler_compass", "legacy_blueprint", "journey_attribute", "journey_class",
                "forged_chestplate", "hunt_crystal", "dungeon_core", "mutation_core",
                "common_insight_skill_book", "blood_moon_essence", "lucky_charm",
                "sulfur_resonance_imprint");
        assertEquals(supported, HelpTopic.ordered().stream()
                .map(HelpTopic::itemModel)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet()));

        assertEquals(Set.of(HelpTopic.MARKET, HelpTopic.ECONOMY, HelpTopic.CLAIMS,
                        HelpTopic.NPCS, HelpTopic.COMPANIONS, HelpTopic.COMMUNITY),
                HelpTopic.ordered().stream()
                        .filter(topic -> topic.itemModel() == null)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void everyCustomHelpModelIsDeclaredByTheRuntimeResourcePack() throws Exception {
        Path manifest = findFromWorkingTree("resource-pack/runtime-item-models.txt");
        Set<String> declared = Files.readAllLines(manifest).stream()
                .map(line -> line.replaceFirst("\\s*#.*$", "").strip())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toSet());
        Set<String> referenced = HelpTopic.ordered().stream()
                .map(HelpTopic::itemModel)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        assertTrue(declared.containsAll(referenced),
                () -> "help topics reference undeclared item models: "
                        + referenced.stream().filter(model -> !declared.contains(model)).toList());
    }

    @Test
    void everyHelpTopicHasAnExplicitVanillaSafeFallbackIcon() throws Exception {
        try (InputStream input = HelpTopicTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(input, "packaged guide config.yml");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            for (HelpTopic topic : HelpTopic.ordered()) {
                String configured = config.getString("help." + topic.id() + ".icon");
                assertNotNull(configured, () -> "missing vanilla fallback for " + topic.id());
                Material material = Material.matchMaterial(configured);
                assertNotNull(material, () -> "invalid vanilla fallback for " + topic.id() + ": " + configured);
                assertTrue(material != Material.BOOK,
                        () -> "generic BOOK fallback hides the topic identity: " + topic.id());
            }
        }
    }

    private static Path findFromWorkingTree(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        throw new AssertionError("cannot find " + relative + " from the test working directory");
    }
}
