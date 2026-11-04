package cn.originalrpg.core.persistence;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Enforces captured actor authority in the very connection used by the ledger.
 * Unscoped work is trusted server work, such as a durable reward replay or an
 * offline recipient credit. It is not classified by arbitrary UUID parameters.
 */
public final class SessionFencedDataSource implements DataSource {
    private final DataSource delegate;

    public SessionFencedDataSource(DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public Connection getConnection() throws SQLException {
        return guard(delegate.getConnection(), SessionWriteContext.current());
    }

    @Override public Connection getConnection(String user, String password) throws SQLException {
        return guard(delegate.getConnection(user, password), SessionWriteContext.current());
    }

    private Connection guard(Connection raw, SessionWriteContext.Scope scope) throws SQLException {
        if (scope == null) return raw;
        try {
            raw.setAutoCommit(false);
            scope.authority().requireOwned(raw, scope.actor());
        } catch (SQLException | RuntimeException error) {
            try { raw.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            try { raw.close(); } catch (SQLException close) { error.addSuppressed(close); }
            throw error;
        }
        boolean[] fenced = {true};
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("unwrap")) {
                        if (((Class<?>) args[0]).isInstance(proxy)) return proxy;
                        throw new SQLException("A fenced transaction cannot expose its raw connection");
                    }
                    if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
                    if (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                        throw new SQLException("Actor-authorized writes require an explicit transaction");
                    }
                    if ((name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement"))
                            && !fenced[0]) {
                        scope.authority().requireOwned(raw, scope.actor());
                        fenced[0] = true;
                    }
                    if (name.equals("commit")) {
                        // A transaction that outlives its lease must roll back even
                        // though the row lock prevented a replacement taking over.
                        scope.authority().requireOwned(raw, scope.actor());
                    }
                    try {
                        Object result = method.invoke(raw, args);
                        if (name.equals("commit") || (name.equals("rollback") && (args == null || args.length == 0))) {
                            fenced[0] = false;
                        }
                        return result;
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter writer) throws SQLException { delegate.setLogWriter(writer); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) return type.cast(this);
        throw new SQLException("The fenced data source does not expose an unguarded delegate");
    }
    @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
}
