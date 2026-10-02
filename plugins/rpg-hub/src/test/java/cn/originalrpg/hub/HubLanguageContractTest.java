package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HubLanguageContractTest {
    @Test
    void bundledNpcLocalesExposeTheSameLeafKeys() {
        Set<String> chinese = flatten(load("zh_cn"));
        Set<String> english = flatten(load("en_us"));

        assertTrue(!chinese.isEmpty());
        assertEquals(chinese, english);
    }

    @Test
    void everyJourneyStepExplainsCompletionDestinationAndPurpose() {
        Set<String> chinese = flatten(load("zh_cn"));
        Set<String> english = flatten(load("en_us"));
        for (String step : Set.of(
                "hub", "class", "attribute", "explore", "hunt", "forge", "reveal", "dungeon")) {
            for (String field : Set.of("name", "goal", "where", "why")) {
                String key = "npc-dialog.journey.steps." + step + "." + field;
                assertTrue(chinese.contains(key), "Missing Chinese NPC journey field: " + key);
                assertTrue(english.contains(key), "Missing English NPC journey field: " + key);
            }
        }
        assertTrue(chinese.contains("npc-dialog.journey.open-details"));
        assertTrue(english.contains("npc-dialog.journey.open-details"));
    }

    private Map<?, ?> load(String locale) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("lang/" + locale + ".yml")) {
            assertNotNull(input);
            Object value = new Yaml().load(input);
            assertTrue(value instanceof Map<?, ?>);
            return (Map<?, ?>) value;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Set<String> flatten(Map<?, ?> root) {
        HashSet<String> keys = new HashSet<>();
        flatten(root, "", keys);
        return Set.copyOf(keys);
    }

    private void flatten(Map<?, ?> values, String prefix, Set<String> keys) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) flatten(nested, key, keys);
            else keys.add(key);
        }
    }
}
