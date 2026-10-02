package cn.originalrpg.skills;

import cn.originalrpg.api.model.SkillVariantCatalog;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.rules.StarterBuildRules;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogContractTest {
    @Test
    void sixClassesExposeSevenConfiguredSkillsAndEveryDefinitionHasAnEffect() {
        Map<?, ?> root = loadConfig();
        Map<?, ?> leveling = (Map<?, ?>) root.get("skill-leveling");
        assertNotNull(leveling);
        assertEquals(java.util.List.of(2, 3, 4, 5), leveling.get("maximum-level-by-ascension"));
        assertEquals(java.util.List.of(2, 4, 6, 8), leveling.get("upgrade-point-costs"));
        assertTrue(((Number) leveling.get("cooldown-reduction-per-level")).doubleValue() > 0.0);
        Map<?, ?> skills = (Map<?, ?>) root.get("skills");
        assertNotNull(skills);
        assertEquals(SkillCatalog.IMPLEMENTED, skills.keySet());
        assertEquals(SkillCatalog.IMPLEMENTED, SkillVisuals.categorizedSkills(),
                "every concrete skill must have an intentional visual family");

        Map<String, Integer> counts = new HashMap<>();
        Map<String, java.util.Set<Integer>> menuSlots = new HashMap<>();
        Map<String, Map<CoreAttribute, Integer>> starters = new HashMap<>();
        for (Map.Entry<?, ?> entry : skills.entrySet()) {
            String id = String.valueOf(entry.getKey());
            Map<?, ?> definition = (Map<?, ?>) entry.getValue();
            String owner = String.valueOf(definition.get("class"));
            counts.merge(owner, 1, Integer::sum);
            menuSlots.computeIfAbsent(owner, ignored -> new java.util.HashSet<>())
                    .add(((Number) definition.get("menu-slot")).intValue());
            assertTrue(((Number) definition.get("mana-cost")).intValue() > 0, id);
            assertTrue(((Number) definition.get("cooldown-seconds")).intValue() > 0, id);
            Object prerequisites = definition.get("prerequisites");
            if (prerequisites instanceof Iterable<?> values) {
                for (Object prerequisite : values) assertTrue(skills.containsKey(prerequisite), id);
            }
            int minimumLevel = definition.containsKey("minimum-level")
                    ? ((Number) definition.get("minimum-level")).intValue() : 1;
            int minimumAscension = definition.containsKey("minimum-ascension")
                    ? ((Number) definition.get("minimum-ascension")).intValue() : 0;
            String branch = definition.containsKey("branch") ? String.valueOf(definition.get("branch")) : "";
            boolean starter = (!(prerequisites instanceof Iterable<?> values)
                    || !values.iterator().hasNext())
                    && minimumLevel <= 1
                    && minimumAscension <= 0
                    && branch.isBlank();
            if (starter) {
                Map<?, ?> requirements = (Map<?, ?>) definition.get("requirements");
                Map<CoreAttribute, Integer> typed = new java.util.EnumMap<>(CoreAttribute.class);
                requirements.forEach((attribute, value) -> typed.put(
                        CoreAttribute.valueOf(String.valueOf(attribute)), ((Number) value).intValue()));
                starters.put(owner, Map.copyOf(typed));
            }
        }
        for (String owner : Set.of("HEALER", "WARRIOR", "ASSASSIN", "MAGE", "RANGER", "FREEFORM")) {
            assertEquals(7, counts.getOrDefault(owner, 0), owner);
            assertEquals(Set.of(13, 22, 28, 29, 31, 33, 34), menuSlots.get(owner),
                    owner + " must render a symmetric, collision-free route tree");
            assertEquals(StarterBuildRules.requirements(Archetype.valueOf(owner)), starters.get(owner),
                    owner + " guide allocation and live first-skill gate must be identical");
        }
        assertTrue(skills.keySet().containsAll(SkillVariantCatalog.implemented().keySet()),
                "every persisted mutation must belong to a concrete configured skill");

        Map<?, ?> books = (Map<?, ?>) root.get("skill-books");
        assertNotNull(books);
        assertEquals(SkillBookCatalog.IMPLEMENTED, books.keySet());
        for (Map.Entry<?, ?> entry : books.entrySet()) {
            Map<?, ?> definition = (Map<?, ?>) entry.getValue();
            assertEquals(SkillBookCatalog.SKILL_POINTS, definition.get("effect"), entry.getKey().toString());
        }
    }

    @Test
    void pluginDeclaresItsOptionalPartyProviderForStableInitialOrdering() {
        Map<?, ?> plugin = loadYaml("plugin.yml");
        Object softDepend = plugin.get("softdepend");
        assertTrue(softDepend instanceof Iterable<?>, "plugin.yml must declare soft dependencies");
        java.util.Set<String> dependencies = new java.util.HashSet<>();
        ((Iterable<?>) softDepend).forEach(value -> dependencies.add(String.valueOf(value)));
        assertTrue(dependencies.contains("RpgDungeons"),
                "RpgDungeons should load first; live calls still resolve PartyService dynamically");
    }

    private static Map<?, ?> loadConfig() {
        return loadYaml("config.yml");
    }

    private static Map<?, ?> loadYaml(String resource) {
        try (InputStream input = SkillCatalogContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
