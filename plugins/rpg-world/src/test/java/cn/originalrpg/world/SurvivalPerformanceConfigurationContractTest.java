package cn.originalrpg.world;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SurvivalPerformanceConfigurationContractTest {
    private static final Map<String, Integer> NATURAL_CAPS = Map.of(
            "monsters", 56,
            "animals", 8,
            "water-animals", 4,
            "water-ambient", 16,
            "water-underground-creature", 4,
            "axolotls", 4,
            "ambient", 12);

    @Test
    void naturalSpawningKeepsTheReviewedExperienceAndEntityEnvelope() throws IOException {
        Path root = repositoryRoot();
        Map<?, ?> bukkit = yaml(root.resolve("server/config/bukkit.yml"));
        assertEquals(NATURAL_CAPS, integerMap(map(bukkit, "spawn-limits")));

        Map<?, ?> cadence = map(bukkit, "ticks-per");
        assertEquals(400, integer(cadence, "animal-spawns"));
        for (String category : List.of(
                "monster-spawns", "water-spawns", "water-ambient-spawns",
                "water-underground-creature-spawns", "axolotl-spawns", "ambient-spawns")) {
            assertEquals(1, integer(cadence, category), category);
        }

        Map<?, ?> spigot = yaml(root.resolve("server/config/spigot.yml"));
        assertEquals(13, integer(spigot, "config-version"));
        Map<?, ?> world = map(map(spigot, "world-settings"), "default");
        assertEquals(4, integer(world, "mob-spawn-range"));

        Map<?, ?> paper = yaml(root.resolve("server/config/paper-world-defaults.yml"));
        Map<?, ?> spawning = map(map(paper, "entities"), "spawning");
        assertEquals(Boolean.TRUE, spawning.get("per-player-mob-spawns"));
        Map<?, ?> ranges = map(spawning, "despawn-ranges");
        for (String category : List.of(
                "monster", "creature", "ambient", "axolotls",
                "underground_water_creature", "water_ambient", "water_creature")) {
            Map<?, ?> range = map(ranges, category);
            assertEquals(32, integer(range, "soft"), category + " soft range");
            assertEquals(96, integer(range, "hard"), category + " hard range");
        }

        Map<?, ?> plugin = yaml(root.resolve("plugins/rpg-world/src/main/resources/config.yml"));
        assertEquals(NATURAL_CAPS.get("monsters"),
                map(plugin, "survival-rules").get("monster-spawn-limit"),
                "RpgWorld must not silently override the reviewed Bukkit monster cap");
        String worldPlugin = Files.readString(root.resolve(
                "plugins/rpg-world/src/main/java/cn/originalrpg/world/RpgWorldPlugin.java"),
                StandardCharsets.UTF_8);
        assertTrue(worldPlugin.contains("getInt(\"survival-rules.monster-spawn-limit\", 56)"),
                "a preserved legacy plugin config without the key must use the reviewed cap too");
    }

    @Test
    void chunkLoadingUsesPaperConcurrencyWithoutDiscardingSavedEntities() throws IOException {
        Path root = repositoryRoot();
        Map<?, ?> global = yaml(root.resolve("server/config/paper-global.yml"));
        Map<?, ?> advanced = map(global, "chunk-loading-advanced");
        assertEquals(0, integer(advanced, "player-max-concurrent-chunk-loads"));
        assertEquals(0, integer(advanced, "player-max-concurrent-chunk-generates"));

        Map<?, ?> worldDefaults = yaml(root.resolve("server/config/paper-world-defaults.yml"));
        Map<?, ?> chunks = map(worldDefaults, "chunks");
        assertFalse(chunks.containsKey("entity-per-chunk-save-limit"),
                "a runtime entity overload guard must not silently discard saved pearls, projectiles, or XP");
        assertEquals(Boolean.TRUE, chunks.get("prevent-moving-into-unloaded-chunks"));
    }

    @Test
    void performanceSamplerDoesNotAllocateLoadedChunkArrays() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-performance/src/main/java/cn/originalrpg/performance/RpgPerformancePlugin.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("chunks += world.getChunkCount();"));
        assertFalse(source.contains("world.getLoadedChunks().length"));
    }

    private static Map<?, ?> yaml(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = Files.newInputStream(path)) {
            return (Map<?, ?>) new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    private static Map<?, ?> map(Map<?, ?> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, "Expected map at " + key);
        return (Map<?, ?>) value;
    }

    private static int integer(Map<?, ?> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Number, "Expected integer at " + key);
        return ((Number) value).intValue();
    }

    private static Map<String, Integer> integerMap(Map<?, ?> source) {
        return source.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> String.valueOf(entry.getKey()),
                entry -> ((Number) entry.getValue()).intValue()));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
