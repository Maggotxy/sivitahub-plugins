package cn.originalrpg.world;

import cn.originalrpg.world.testing.DeploymentEnvironmentFixtures;
import org.junit.jupiter.api.Tag;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生存世界的生成 / 分块加载配置契约。
 *
 * <p>G-302 说明：本类保留的断言读的是**本仓内的插件配置 + 纯规则**，并已行为化 ——
 * 真加载 {@code plugins/rpg-world/src/main/resources/config.yml}，再跑
 * {@link SurvivalSpawnLimits} 与生产插件同一口径的解析规则（缺键 / 配了 / 越界三种输入）。
 * 只有读运维侧 {@code server/config/**} 的两个方法保留 {@code @Tag(env)}：本仓按设计
 * 不含 {@code server/}，那不是「本仓源码字符串断言」。</p>
 */
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
    void pluginConfigKeepsTheReviewedMonsterCapAndTheSameClampContract() throws Exception {
        Path root = repositoryRoot();
        Map<?, ?> plugin = yaml(root.resolve("plugins/rpg-world/src/main/resources/config.yml"));
        Map<?, ?> survivalRules = map(plugin, "survival-rules");

        assertEquals(NATURAL_CAPS.get("monsters"), survivalRules.get("monster-spawn-limit"),
                "RpgWorld must not silently override the reviewed Bukkit monster cap");
        // 行为化（G-302）：原断言读 RpgWorldPlugin 源码找
        // getInt("survival-rules.monster-spawn-limit", 56)。真正的判据是「缺键用哪个
        // 默认值 + 越界收敛到哪」，这里把配置**真的喂进生产同一口径的解析**（下面
        // monsterSpawnLimit 复刻 RpgWorldPlugin 里那一行的 Math.clamp 形状）并断言结果。
        assertEquals(NATURAL_CAPS.get("monsters"),
                monsterSpawnLimit((Number) survivalRules.get("monster-spawn-limit")),
                "配置里的上限经生产解析口径后必须仍是评审过的 56");
        assertEquals(56, monsterSpawnLimit(null),
                "保留下来的旧配置缺该键时必须用评审过的上限");
        assertEquals(70, monsterSpawnLimit(200),
                "超过生产 clamp 上界必须收敛到 70，而不是把服务端上限交出去");
        assertEquals(16, monsterSpawnLimit(1),
                "低于生产 clamp 下界必须收敛到 16");

        // 生产插件里那一行的形状（默认值字面量 + clamp 边界 + 真的写进 SpawnCategory.MONSTER）
        // 只能对源码结构做静态保证（门禁执行）。
        assertSourceContractHolds("legacyPluginConfigWithoutTheKeyUsesTheReviewedCap");
    }

    @Test
    void performanceSamplerDoesNotAllocateLoadedChunkArrays() throws Exception {
        // G-302：这条只能对源码结构保证（「不得出现 world.getLoadedChunks().length」），
        // 迁进声明式规格并由本方法真实执行门禁。
        assertSourceContractHolds("performanceSamplerDoesNotAllocateLoadedChunkArrays");
    }

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    // G-302 豁免（保留 env 标签）：断言对象是运维侧 server/config/**，本仓按设计不含。
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
    }

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    // G-302 豁免（保留 env 标签）：断言对象是运维侧 server/config/**，本仓按设计不含。
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

    /**
     * 生产插件 {@code RpgWorldPlugin#applySurvivalRules} 的怪物上限解析口径，逐字复刻：
     * {@code Math.clamp(getConfig().getInt("survival-rules.monster-spawn-limit", 56), 16, 70)}。
     */
    private static int monsterSpawnLimit(Number configured) {
        return Math.clamp(configured == null ? 56 : configured.intValue(), 16, 70);
    }

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertSourceContractHolds(String method) throws Exception {
        Path root = repositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(),
                "plugins/rpg-world/src/test/java/cn/originalrpg/world/"
                        + "SurvivalPerformanceConfigurationContractTest.java", method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(),
                "源码契约门禁失败（" + method + "）：\n" + output);
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
