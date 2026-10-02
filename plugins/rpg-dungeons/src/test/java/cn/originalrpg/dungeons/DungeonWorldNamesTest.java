package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界名生成与 disposable 生命周期必须同一条规则：生成的名字若不被
 * {@link DungeonInstanceStorage} 认得，世界就永远不会被回收。
 */
final class DungeonWorldNamesTest {
    @Test
    void everyGeneratedNameIsAcceptedByTheInstanceLifecycle() {
        for (String id : DungeonRewardRules.ids()) {
            for (long ordinal = 1; ordinal <= 4; ordinal++) {
                String name = DungeonWorldNames.allocate(id, ordinal);
                assertTrue(DungeonInstanceStorage.isInstanceName(name), name);
                assertTrue(name.startsWith(DungeonInstanceStorage.INSTANCE_PREFIX + DungeonWorldNames.sanitize(id) + "_"),
                        name);
            }
        }
    }

    @Test
    void ordinalMakesNamesUniqueWithinOneProcess() {
        Set<String> names = new HashSet<>();
        for (long ordinal = 1; ordinal <= 512; ordinal++) {
            assertTrue(names.add(DungeonWorldNames.allocate("ancient-ruins", ordinal)),
                    "duplicate instance world name at ordinal " + ordinal);
        }
        assertEquals(512, names.size());
    }

    @Test
    void idsWithUnsafeCharactersStillProduceALifecycleSafeName() {
        assertEquals("dungeon_instance_my_id_1", DungeonWorldNames.allocate("My ID", 1));
        // 序号以 base36 编码（第 10 个实例 = a），世界名短且只含 [a-z0-9_]。
        assertEquals("dungeon_instance_a_b_a", DungeonWorldNames.allocate("a/b", 10));
        assertEquals("dungeon_instance_a_b_10", DungeonWorldNames.allocate("a/b", 36));
        assertEquals("dungeon_instance_id_1", DungeonWorldNames.allocate("ID", 1));
    }

    @Test
    void rejectsInputsThatCannotFormAReviewableInstanceDirectory() {
        assertThrows(IllegalArgumentException.class, () -> DungeonWorldNames.allocate("ancient-ruins", 0));
        assertThrows(IllegalArgumentException.class, () -> DungeonWorldNames.allocate("ancient-ruins", -1));
        assertThrows(IllegalArgumentException.class, () -> DungeonWorldNames.allocate(null, 1));
        assertThrows(IllegalArgumentException.class, () -> DungeonWorldNames.allocate("   ", 1));
        // 折叠后以下划线/空开头的小段会让名字退化成「只有前缀」，必须拒绝而不是生成脏目录。
        assertThrows(IllegalArgumentException.class, () -> DungeonWorldNames.allocate("--", 1));
        assertThrows(IllegalArgumentException.class, () -> DungeonWorldNames.allocate("-hidden", 1));
    }

    @Test
    void nameNeverLooksLikeAnAuthoredWorldSuchAsTheRemovedConfigValues() {
        String name = DungeonWorldNames.allocate("ancient-ruins", 1);
        assertFalse(name.equals("dungeon_ancient_ruins"));
        assertFalse(name.equals("dungeon_ancient_ruins_hidden"));
    }
}
