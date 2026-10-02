package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-108 门禁：副本 config.yml 不得声明固定世界名，且不得新增无人读取的副本段。
 * 直接执行启动期校验函数，而不是对源码做字符串比对。
 */
final class DungeonConfigKeysTest {
    @Test
    void shippedConfigDeclaresNoFixedInstanceWorldName() throws IOException {
        Map<String, Object> root = loadConfig();

        assertEquals(Map.of(), sectionsDeclaringWorldKey(root));
        DungeonConfigKeys.requireNoReservedWorldKeys(root);
        assertEquals(DungeonRewardRules.ids(), DungeonConfigKeys.configuredDungeonIds(root));
    }

    @Test
    void startupRejectsAReintroducedWorldKeyInsteadOfSilentlyIgnoringIt() throws IOException {
        Map<String, Object> root = loadConfig();
        @SuppressWarnings("unchecked")
        Map<String, Object> dungeons = new LinkedHashMap<>((Map<String, Object>) root.get("dungeons"));
        Map<String, Object> tampered = new LinkedHashMap<>(root);
        tampered.put("dungeons", dungeons);
        dungeons.put("ancient-ruins", withWorldKey(dungeons, "ancient-ruins", "dungeon_ancient_ruins"));

        assertEquals(Map.of("ancient-ruins", "dungeon_ancient_ruins"), sectionsDeclaringWorldKey(tampered));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DungeonConfigKeys.requireNoReservedWorldKeys(tampered));
        assertTrue(error.getMessage().contains("dungeons.ancient-ruins.world"), error.getMessage());
        assertTrue(error.getMessage().contains(DungeonInstanceStorage.INSTANCE_PREFIX), error.getMessage());
    }

    @Test
    void gateFailsClosedOnRootsWithoutADungeonsSection() {
        assertThrows(IllegalStateException.class, () -> DungeonConfigKeys.configuredDungeonIds(Map.of()));
        DungeonConfigKeys.requireNoReservedWorldKeys(Map.of());
        DungeonConfigKeys.requireNoReservedWorldKeys(null);
    }

    /** 与门禁独立的取证路径：直接扫描 YAML 树，而不是复用被测代码的遍历逻辑。 */
    private static Map<String, String> sectionsDeclaringWorldKey(Map<String, Object> root) {
        Object dungeons = root.get("dungeons");
        assertTrue(dungeons instanceof Map<?, ?>, "dungeons section");
        Map<String, String> found = new HashMap<>();
        for (Map.Entry<?, ?> section : ((Map<?, ?>) dungeons).entrySet()) {
            if (section.getValue() instanceof Map<?, ?> dungeon) {
                Object world = dungeon.get(DungeonConfigKeys.RESERVED_WORLD_KEY);
                if (world != null) found.put(String.valueOf(section.getKey()), String.valueOf(world));
            }
        }
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withWorldKey(Map<String, Object> dungeons, String id, String world) {
        Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) dungeons.get(id));
        copy.put(DungeonConfigKeys.RESERVED_WORLD_KEY, world);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig() throws IOException {
        try (InputStream input = DungeonConfigKeysTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input, "config.yml");
            return (Map<String, Object>) new Yaml().load(input);
        }
    }
}
