package cn.originalrpg.tools.dbmigration;

/**
 * 迁移用的 PostgreSQL advisory lock 定义，必须与在线节点保持完全一致。
 *
 * <p>唯一权威实现在
 * {@code plugins/rpg-core/src/main/java/cn/originalrpg/core/persistence/Database.java}。
 * 工具与插件是两个独立构建单元，无法共享编译期常量，因此这里显式复刻同样的两个
 * 有符号 32 位 key，并由 {@code MigrationAdvisoryLockTest} 双向断言（硬编码期望值
 * ＋直接读取 RpgCore 源码核对），任何一侧被单方面修改都会失败关闭。</p>
 *
 * <p>PostgreSQL 把双参数 {@code pg_advisory_xact_lock(int,int)} 的合成 key 定义为
 * {@code (int4 << 32) | int4}（见 PostgreSQL 源码 AdvisoryLockTagToInt8）；
 * 只传一个 bigint 的 {@code pg_advisory_xact_lock(bigint)} 用的是另一把锁，
 * 两把锁互不阻塞。</p>
 */
final class MigrationAdvisoryLock {
    /** "SIVI"，与 RpgCore {@code Database.MIGRATION_LOCK_NAMESPACE} 相同。 */
    static final int NAMESPACE = 0x53495649;
    /** "RPG1"，与 RpgCore {@code Database.MIGRATION_LOCK_RESOURCE} 相同。 */
    static final int RESOURCE = 0x52504731;
    /** 合法等待上限，与 RpgCore {@code database.migration-lock-timeout-ms} 的取值范围一致。 */
    static final long MIN_TIMEOUT_MS = 1_000L;
    static final long MAX_TIMEOUT_MS = 300_000L;
    static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private MigrationAdvisoryLock() {
    }

    /** 双参数形式在数据库内核里最终合成的有符号 64 位锁 key。 */
    static long composedKey() {
        return signedComposedKey(NAMESPACE, RESOURCE);
    }

    /** 合成规则与 PostgreSQL AdvisoryLockTagToInt8 相同，供契约测试核对。 */
    static long signedComposedKey(int namespace, int resource) {
        return ((long) namespace << Integer.SIZE) | (resource & 0xFFFFFFFFL);
    }
}
