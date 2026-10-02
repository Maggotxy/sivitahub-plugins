package cn.originalrpg.dungeons;

import java.util.Locale;

/**
 * 一次性副本世界名的唯一来源。
 *
 * <p>世界名必须是 {@link DungeonInstanceStorage} 认得的 disposable 形态，
 * 否则世界既不会被清理也不会被回收，所以生成、校验、回收共用本类一条规则，
 * 而不是让调用方各自拼字符串。
 */
final class DungeonWorldNames {
    private DungeonWorldNames() {
    }

    /**
     * 按副本 id 与进程内递增序号生成世界名。
     *
     * <p>序号从 1 开始单调递增，同一进程内绝不重复；进程重启后残留目录由
     * {@code DungeonManager} 的启动清理流程回收，因此不需要全局计数器。
     */
    static String allocate(String dungeonId, long ordinal) {
        String safeId = sanitize(dungeonId);
        if (ordinal < 1) throw new IllegalArgumentException("Instance ordinal must be positive: " + ordinal);
        return DungeonInstanceStorage.INSTANCE_PREFIX + safeId + "_"
                + Long.toUnsignedString(ordinal, 36);
    }

    /** 非字母数字下划线一律折叠为下划线，保证世界名只含 {@code [a-z0-9_]}。 */
    static String sanitize(String dungeonId) {
        if (dungeonId == null || dungeonId.isBlank()) {
            throw new IllegalArgumentException("Dungeon id must not be blank");
        }
        String sanitized = dungeonId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        // 前缀后必须至少有一小段 id，再加上一个非空序号段。
        if (sanitized.isEmpty() || sanitized.charAt(0) == '_') {
            throw new IllegalArgumentException("Dungeon id cannot form an instance world name: " + dungeonId);
        }
        return sanitized;
    }
}
