package cn.originalrpg.hub;

import cn.originalrpg.hub.testing.DeploymentEnvironmentFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldTravelConfigurationContractTest {
    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    void wildernessAndAuthoritativeOverworldBoundaryCannotDriftApart() throws IOException {
        Path root = repositoryRoot();
        Map<?, ?> hub = map(yaml(root.resolve(
                "plugins/rpg-hub/src/main/resources/config.yml")), "hub");
        Map<?, ?> wilderness = map(hub, "wilderness");
        Map<?, ?> boundaries = map(yaml(root.resolve(
                "plugins/rpg-world/src/main/resources/config.yml")), "boundaries");
        Map<?, ?> overworld = map(boundaries, "overworld");

        assertEquals(text(overworld, "world"), text(wilderness, "world"));
        assertEquals(number(overworld, "center-x"), number(wilderness, "center-x"));
        assertEquals(number(overworld, "center-z"), number(wilderness, "center-z"));

        int centerX = integer(wilderness, "center-x");
        int centerZ = integer(wilderness, "center-z");
        int maximumRadius = integer(wilderness, "maximum-radius");
        int preloadRadius = integer(wilderness, "preload-radius-chunks");
        double borderDiameter = number(overworld, "diameter");
        assertTrue(maximumRadius > integer(wilderness, "minimum-radius"));

        for (int x : new int[]{centerX - maximumRadius, centerX + maximumRadius}) {
            assertTrue(WildernessTravelRules.neighborhoodInsideBorder(
                    x >> 4, centerZ >> 4, preloadRadius,
                    centerX, centerZ, borderDiameter), "x frontier " + x);
        }
        for (int z : new int[]{centerZ - maximumRadius, centerZ + maximumRadius}) {
            assertTrue(WildernessTravelRules.neighborhoodInsideBorder(
                    centerX >> 4, z >> 4, preloadRadius,
                    centerX, centerZ, borderDiameter), "z frontier " + z);
        }

        String maintenance = Files.readString(root.resolve(
                "server/bin/maintain-world-coverage.sh"), StandardCharsets.UTF_8);
        assertTrue(maintenance.contains("run_chunky world 7 0 1808"),
                "controlled generation must cover the complete Overworld border plus its chunk margin");
        assertTrue(maximumRadius + (preloadRadius + 1) * 16 < borderDiameter / 2.0,
                "random travel needs a full generated-neighborhood margin before the border");
    }

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    void mapsAndClaimsFailClosedForDynamicWorlds() throws IOException {
        Path root = repositoryRoot();
        Map<?, ?> squaremap = map(yaml(root.resolve(
                "server/config-templates/squaremap/config.yml")), "world-settings");
        assertEquals(Boolean.FALSE, map(map(squaremap, "default"), "map").get("enabled"));
        assertEquals(Set.of("default", "minecraft:overworld", "minecraft:sivita_hub",
                        "minecraft:the_nether", "minecraft:the_end"),
                squaremap.keySet(), "only reviewed persistent worlds may override the disabled map default");
        for (String world : Set.of("minecraft:overworld", "minecraft:sivita_hub",
                "minecraft:the_nether", "minecraft:the_end")) {
            assertEquals(Boolean.TRUE, map(map(squaremap, world), "map").get("enabled"), world);
            assertEquals(Boolean.FALSE, map(map(squaremap, world), "player-tracker").get("enabled"), world);
        }

        Map<?, ?> griefPrevention = map(yaml(root.resolve(
                "server/config-templates/GriefPreventionData/config.yml")), "GriefPrevention");
        Map<?, ?> modes = map(map(griefPrevention, "Claims"), "Mode");
        assertEquals(Set.of("world", "world_nether", "world_the_end"), modes.keySet());
        assertEquals("Survival", modes.get("world"));
        assertEquals("Disabled", modes.get("world_nether"));
        assertEquals("Disabled", modes.get("world_the_end"));
        assertFalse(modes.containsKey("sivita_hub"));
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
        return ((Number) parent.get(key)).intValue();
    }

    private static double number(Map<?, ?> parent, String key) {
        return ((Number) parent.get(key)).doubleValue();
    }

    private static String text(Map<?, ?> parent, String key) {
        return String.valueOf(parent.get(key));
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
