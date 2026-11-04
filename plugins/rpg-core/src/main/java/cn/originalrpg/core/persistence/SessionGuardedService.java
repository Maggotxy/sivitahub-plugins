package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.PlayerSessionLease;
import cn.originalrpg.api.session.SessionActor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Captures an explicitly declared actor before a service queues its SQL work. */
public final class SessionGuardedService {
    private SessionGuardedService() { }

    /** With no enabled lease authority, returns the exact original single-node service. */
    public static <T> T wrap(Class<T> type, T implementation, JdbcSessionLease sessions) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(implementation, "implementation");
        if (sessions == null) return implementation;
        if (!type.isInterface()) throw new IllegalArgumentException("A service interface is required");
        boolean guarded = false;
        for (Method method : type.getMethods()) {
            SessionActor actor = method.getAnnotation(SessionActor.class);
            if (actor == null) continue;
            guarded = true;
            if (actor.argument() < 0 || actor.argument() >= method.getParameterCount()
                    || !CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalArgumentException("Invalid session actor declaration: " + method);
            }
        }
        if (!guarded) return implementation;
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            SessionActor declaration = method.getAnnotation(SessionActor.class);
            if (declaration == null) return invoke(implementation, method, args);
            try {
                UUID actorId = actorId(declaration, args);
                SessionWriteContext.Scope inherited = SessionWriteContext.current();
                PlayerSessionLease actor = inherited == null
                        ? sessions.current(actorId).orElseThrow(() -> new IllegalStateException(
                                "No admitted player session for actor " + actorId))
                        : inherited.actor();
                if (!actor.playerId().equals(actorId)) {
                    throw new IllegalStateException("A service cannot replace its captured actor with another player");
                }
                return sessions.fenced(actor, () -> {
                    @SuppressWarnings("unchecked")
                    CompletionStage<Object> result = (CompletionStage<Object>) invoke(implementation, method, args);
                    return result;
                });
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }));
    }

    private static UUID actorId(SessionActor declaration, Object[] args) {
        Object value = args[declaration.argument()];
        if (!declaration.member().isEmpty()) {
            if (value == null) throw new IllegalArgumentException("Actor request must not be null");
            try {
                value = value.getClass().getMethod(declaration.member()).invoke(value);
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException("Actor accessor must be public, parameterless, and return UUID", error);
            }
        }
        if (!(value instanceof UUID actor)) throw new IllegalArgumentException("Actor must be a UUID");
        return actor;
    }

    private static Object invoke(Object target, Method method, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException("Service invocation failed", error.getCause());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Service method is not accessible", error);
        }
    }
}
