package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonConfigurationContractTest {
    @Test
    void allSixEncountersHaveEntryCombatAndRewardContent() throws IOException {
        Map<String, Object> root = loadConfig();
        Map<String, Object> dungeons = map(root, "dungeons");
        assertEquals(DungeonRewardRules.ids(), dungeons.keySet());
        Map<String, Integer> archetypeUses = new HashMap<>();
        Map<String, Integer> skillUses = new HashMap<>();

        for (String id : DungeonRewardRules.ids()) {
            Map<String, Object> dungeon = map(dungeons, id);
            assertEquals(Boolean.TRUE, dungeon.get("enabled"), id);
            assertTrue(integer(dungeon, "minimum-party-size") >= 1, id);
            assertTrue(integer(dungeon, "maximum-party-size") <= 4, id);
            assertTrue(integer(dungeon, "energy-cost") > 0, id);
            assertTrue(integer(dungeon, "minimum-effective-level") > 0, id);
            Map<String, Object> waves = map(dungeon, "waves");
            assertTrue(waves.size() >= 2, id);
            for (Map.Entry<String, Object> entry : waves.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> wave = (Map<String, Object>) entry.getValue();
                String archetype = String.valueOf(wave.get("archetype"));
                String skill = String.valueOf(wave.get("skill"));
                assertTrue(Set.of("ruins-runebound", "ruins-echo-sentinel", "sulfur-fume-crawler",
                        "sulfur-slag-mote", "storm-conductor", "storm-gust-wisp").contains(archetype),
                        id + "/" + entry.getKey());
                assertTrue(WaveSkillRules.keys().contains(skill), id + "/" + entry.getKey());
                assertTrue(number(wave, "skill-damage") > 0.0, id + "/" + entry.getKey());
                archetypeUses.merge(archetype, 1, Integer::sum);
                skillUses.merge(skill, 1, Integer::sum);
            }

            Map<String, Object> boss = map(dungeon, "boss");
            assertNotNull(boss.get("type"), id);
            assertTrue(number(boss, "health") > 20.0, id);
            assertTrue(number(boss, "attack-damage") > 0.0, id);
            assertTrue(number(boss, "rpg-experience") > 0.0, id);
            Map<String, Object> abilities = map(boss, "abilities");
            assertTrue(number(abilities, "interval-ticks") >= 80.0, id);
            // G-209：文档承诺的 0.4–0.8s 低于表现层可读下限，配置必须 >= 该下限（见 BossAttackRules）。
            assertTrue(number(abilities, "telegraph-ticks")
                    >= BossAttackRules.MINIMUM_READABLE_TELEGRAPH_TICKS, id);
            assertTrue(number(abilities, "damage") > 0.0, id);

            Map<String, Object> rewards = map(dungeon, "rewards");
            assertNotNull(map(rewards, "material").get("name-key"), id);
            if (id.endsWith("-hidden")) {
                assertEquals(id, dungeon.get("ticket-id"), id);
                assertNotNull(map(rewards, "skill-book").get("id"), id);
            } else {
                assertTrue(number(dungeon, "hidden-ticket-chance") > 0.0, id);
                assertTrue(integer(dungeon, "hidden-ticket-pity") >= 1, id);
                assertNotNull(map(rewards, "hidden-ticket").get("name-key"), id);
            }
        }
        assertEquals(6, archetypeUses.size());
        assertTrue(archetypeUses.values().stream().allMatch(uses -> uses == 2));
        assertEquals(WaveSkillRules.keys(), skillUses.keySet());
        assertTrue(skillUses.values().stream().allMatch(uses -> uses == 2));
    }

    @Test
    void poolCanWarmEveryEncounterWithoutExhaustingItsWorldBudget() throws IOException {
        Map<String, Object> root = loadConfig();
        int dungeonCount = map(root, "dungeons").size();
        Map<String, Object> pool = map(root, "instance-pool");
        int warm = integer(pool, "warm-per-dungeon");

        assertTrue(warm >= 1);
        assertTrue(integer(pool, "maximum-worlds") >= dungeonCount * warm);
        assertTrue(integer(pool, "maximum-queued-entries") >= 4);
        assertTrue(integer(pool, "queue-timeout-seconds") >= 5);
        assertTrue(integer(pool, "blocks-per-tick") >= 32);
        assertEquals(4, integer(pool, "view-distance"));
        assertEquals(3, integer(pool, "simulation-distance"));
    }

    @Test
    void seasonExpeditionHasAnExplicitStableResetPolicy() throws IOException {
        Map<String, Object> root = loadConfig();
        Map<String, Object> season = map(root, "season-expedition");
        assertEquals(Boolean.TRUE, season.get("enabled"));
        assertEquals("Asia/Shanghai", season.get("timezone"));
        assertEquals("2026-08-03", season.get("rotation-epoch-monday"));
        assertTrue(integer(season, "minimum-chapter-sequence") >= 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadConfig() throws IOException {
        try (InputStream input = DungeonConfigurationContractTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertNotNull(input, "config.yml");
            return (Map<String, Object>) new Yaml().load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, "Expected map at " + key);
        return (Map<String, Object>) value;
    }

    private static int integer(Map<String, Object> parent, String key) {
        return ((Number) parent.get(key)).intValue();
    }

    private static double number(Map<String, Object> parent, String key) {
        return ((Number) parent.get(key)).doubleValue();
    }
}
