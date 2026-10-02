package cn.originalrpg.core.persistence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Submits JDBC work without ever falling back to the caller thread.
 * A saturated bounded executor becomes an exceptional future so command
 * handlers can report a temporary failure instead of blocking Paper's tick.
 */
final class AsyncJdbc {
    private AsyncJdbc() {
    }

    static <T> CompletableFuture<T> supply(Executor executor, Supplier<T> operation) {
        try {
            return CompletableFuture.supplyAsync(operation, executor);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
