package cn.originalrpg.core.persistence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.function.LongSupplier;

/**
 * 一个事务的连接包装：只有业务代码在 {@code SELECT ... FOR UPDATE} 之后调用
 * {@link #markLocked(Connection)}，才在这里取提交版本号。
 *
 * <p>这样「取号顺序 == 加锁顺序 == 提交顺序」：先拿到行锁的事务先取号，
 * 后拿到行锁的事务只能取到更大的号。{@code publish()} 按号取舍就不会让旧快照
 * 回滚读缓存。取号在 {@code commit()} 之前，行锁仍然持有。
 *
 * <p>纯读路径不发布缓存（例如 {@code eligibility}、管理员测试模式的 {@code status}），
 * 传 {@link #NO_STAMP} 即可；写路径没有在档案行上 {@code FOR UPDATE} 时
 * （例如管理员测试模式的 {@code restore}，它锁的是快照表），必须显式调用
 * {@link JdbcProfileService#loadSnapshot} 在写行之前取号，见各调用点注释。
 */
final class LockedTransaction implements InvocationHandler {
    /** 不发布缓存时用的占位版本号（从不被读）。 */
    static final long[] NO_STAMP = new long[1];

    private final Connection delegate;
    private final LongSupplier nextVersion;
    private long version;

    private LockedTransaction(Connection delegate, LongSupplier nextVersion) {
        this.delegate = delegate;
        this.nextVersion = nextVersion;
    }

    /**
     * 把连接包成动态代理（对外仍是 {@link Connection}）。已经是本类型（嵌套发布点，
     * 例如副本结算/事故补偿内部再开 profile 服务）就直接复用，不重复取号。
     */
    static Connection wrap(Connection connection, LongSupplier nextVersion) {
        if (handlerOf(connection) != null) return connection;
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                new LockedTransaction(connection, nextVersion));
    }

    private static LockedTransaction handlerOf(Connection connection) {
        return Proxy.isProxyClass(connection.getClass())
                && Proxy.getInvocationHandler(connection) instanceof LockedTransaction locked
                ? locked : null;
    }

    /** 行锁到手：登记一次提交版本号（重复调用只生效一次，号必须最早那个）。 */
    private void markLocked() {
        if (version == 0L) version = nextVersion.getAsLong();
    }

    long version() {
        return version;
    }

    /** 取出包装承载的版本号（提交成功后由调用方读出）。 */
    static long versionOf(Connection connection) {
        return handlerOf(connection).version;
    }

    /** 连接包了本类型就在**已持锁**时登记版本号；否则什么也不做。 */
    static void markLocked(Connection connection) {
        LockedTransaction locked = handlerOf(connection);
        if (locked != null) locked.markLocked();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
