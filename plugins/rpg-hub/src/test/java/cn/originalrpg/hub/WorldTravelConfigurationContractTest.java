package cn.originalrpg.hub;

import cn.originalrpg.hub.testing.DeploymentEnvironmentFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界边界与 Wilderness 配置的一致性契约。
 *
 * <p>G-302 说明：本类保留的 YAML 断言读的是**本仓内的配置与纯规则**，并在
 * 本仓内行为化 —— 真正加载 {@code plugins/rpg-hub/.../config.yml} 与
 * {@code plugins/rpg-world/.../config.yml}（本仓存在），再调
 * {@link WildernessTravelRules#neighborhoodInsideBorder} 真跑边界判定。
 * 唯一读「部署产物」的是运维侧维护脚本 {@code server/bin/maintain-world-coverage.sh}
 * （本仓不含 {@code server/}，见 .cnb.yml 的 env 排除口径），因此那个方法保留 env 标签。</p>
 */
final class WorldTravelConfigurationContractTest {
    @Test
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

        // 真跑生产规则：最远随机旅行点及其预生成邻域仍必须落在世界边界内。
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
        // 边界只多出不足一个预生成邻域的余量时必须判不合法（防止把余量吃干净还看着绿）。
        // 数值由真实最远边界的 chunk 覆盖算出：最远边界 chunk + 预生成半径的右开边界是
        // ((center+radius)>>4 + preload + 1) * 16，比它更小的直径必须被拒。
        double tooTightDiameter = ((centerX + maximumRadius) >> 4
                + preloadRadius + 1) * 32.0 - 2.0;
        assertFalse(WildernessTravelRules.neighborhoodInsideBorder(
                (centerX + maximumRadius) >> 4, centerZ >> 4, preloadRadius,
                centerX, centerZ, tooTightDiameter),
                "边界余量不足一个预生成邻域时必须判不合法（tooTightDiameter="
                        + tooTightDiameter + "）");

        assertTrue(maximumRadius + (preloadRadius + 1) * 16 < borderDiameter / 2.0,
                "random travel needs a full generated-neighborhood margin before the border");
    }

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    // G-302 豁免（保留 env 标签）：断言对象是运维侧维护脚本 server/bin/maintain-world-coverage.sh，
    // 本仓按设计不含 server/。这不是「本仓源码字符串断言」，因此无法在本仓行为化。
    void controlledGenerationCoversTheCompleteBorderAndItsChunkMargin() throws IOException {
        Path root = repositoryRoot();
        String maintenance = Files.readString(root.resolve(
                "server/bin/maintain-world-coverage.sh"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(maintenance.contains("run_chunky world 7 0 1808"),
                "controlled generation must cover the complete Overworld border plus its chunk margin");
    }

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    // G-302 豁免（保留 env 标签）：断言对象是运维侧 squaremap / GriefPrevention 配置模板，
    // 本仓按设计不含 server/config-templates/。
    void mapsAndClaimsFailClosedForDynamicWorlds() throws IOException {
        Path root = repositoryRoot();
        Map<?, ?> squaremap = map(yaml(root.resolve(
                "server/config-templates/squaremap/config.yml")), "world-settings");
        assertEquals(Boolean.FALSE, map(map(squaremap, "default"), "map").get("enabled"));
        assertEquals(java.util.Set.of("default", "minecraft:overworld", "minecraft:sivita_hub",
                        "minecraft:the_nether", "minecraft:the_end"),
                squaremap.keySet(), "only reviewed persistent worlds may override the disabled map default");
        for (String world : java.util.Set.of("minecraft:overworld", "minecraft:sivita_hub",
                "minecraft:the_nether", "minecraft:the_end")) {
            assertEquals(Boolean.TRUE, map(map(squaremap, world), "map").get("enabled"), world);
            assertEquals(Boolean.FALSE, map(map(squaremap, world), "player-tracker").get("enabled"), world);
        }

        Map<?, ?> griefPrevention = map(yaml(root.resolve(
                "server/config-templates/GriefPreventionData/config.yml")), "GriefPrevention");
        Map<?, ?> modes = map(map(griefPrevention, "Claims"), "Mode");
        assertEquals(java.util.Set.of("world", "world_nether", "world_the_end"), modes.keySet());
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
