package cn.originalrpg.core.persistence;

/**
 * 事务结果 + 其提交版本号。
 *
 * <p>版本号在事务**仍持有 {@code SELECT ... FOR UPDATE} 行锁时**取得
 * （见 {@link JdbcProfileService#nextCommitVersion()}），因此取号顺序 == 锁顺序 == 提交顺序。
 * 读缓存的发布判定用它，而不是「哪个 future 先回到主线程」——后者会让先提交、
 * 后回调的事务把旧快照盖到新快照上，读缓存被回滚。
 *
 * @param value 事务产出
 * @param version 提交版本号
 */
record Committed<T>(T value, long version) {
}
