package cn.originalrpg.dungeons;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config.yml 的读取门禁（G-108 的可行子集）。
 *
 * <p>本类只断言当前代码**真的不读**的那一个顶层键段：副本世界名必须在运行时生成，
 * 所以 {@code dungeons.*.world} 既不能出现也不能被读取。其余配置键大量使用
 * {@code getConfigurationSection(prefix) + getKeys(false)} 动态遍历，无法机械判定，
 * 不在本门禁范围内（见 plan/gap-matrix.md §0 的取证边界）。
 */
final class DungeonConfigKeys {
    /** 死配置键名：预留字段，一旦被读取就说明有人把世界名权威挪回了配置。 */
    static final String RESERVED_WORLD_KEY = "world";
    /** 证据路径前缀，用于在报错里指名具体位置。 */
    static final String WORLD_KEY_PREFIX = "dungeons.";

    private DungeonConfigKeys() {
    }

    /** 启动与测试共用：发现未被支持的固定世界名键即拒绝启动，而不是静默忽略。 */
    static void requireNoReservedWorldKeys(Map<String, Object> root) {
        List<String> offenders = reservedWorldKeys(root);
        if (!offenders.isEmpty()) {
            throw new IllegalStateException("Dungeon configuration must not declare instance world names ("
                    + WORLD_KEY_PREFIX + "*.world): instance worlds are created at runtime as "
                    + DungeonInstanceStorage.INSTANCE_PREFIX + "<id>_<token>; found " + offenders);
        }
    }

    /** 返回所有声明了固定世界名的副本路径，顺序与配置顺序一致。 */
    static List<String> reservedWorldKeys(Map<String, Object> root) {
        Object dungeons = root == null ? null : root.get("dungeons");
        if (!(dungeons instanceof Map<?, ?> sections)) return List.of();
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<?, ?> section : sections.entrySet()) {
            if (section.getValue() instanceof Map<?, ?> dungeon
                    && dungeon.get(RESERVED_WORLD_KEY) != null) {
                offenders.add(WORLD_KEY_PREFIX + section.getKey() + "." + RESERVED_WORLD_KEY);
            }
        }
        return List.copyOf(offenders);
    }

    /** 副本 id 集合必须与已发布的六个遭遇一致（避免新增无人读取的键段）。 */
    static Set<String> configuredDungeonIds(Map<String, Object> root) {
        Object dungeons = root == null ? null : root.get("dungeons");
        if (!(dungeons instanceof Map<?, ?> sections)) {
            throw new IllegalStateException("Dungeon configuration must define a dungeons section");
        }
        List<String> ids = new ArrayList<>(sections.keySet().size());
        for (Object id : sections.keySet()) ids.add(String.valueOf(id));
        return Set.copyOf(ids);
    }
}
