package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PlayerSessionLease;

import java.util.Objects;
import java.util.function.Supplier;

/** Explicit, non-inheritable authority copied at the JDBC submission boundary. */
final class SessionWriteContext {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private SessionWriteContext() { }

    static Scope current() {
        return CURRENT.get();
    }

    static <T> Supplier<T> capture(Supplier<T> operation) {
        Scope captured = CURRENT.get();
        return () -> with(captured, operation);
    }

    static <T> T with(Scope scope, Supplier<T> operation) {
        Scope previous = CURRENT.get();
        if (scope == null) CURRENT.remove();
        else CURRENT.set(scope);
        try {
            return operation.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    record Scope(JdbcSessionLease authority, PlayerSessionLease actor) {
        Scope {
            Objects.requireNonNull(authority, "authority");
            Objects.requireNonNull(actor, "actor");
        }
    }
}
