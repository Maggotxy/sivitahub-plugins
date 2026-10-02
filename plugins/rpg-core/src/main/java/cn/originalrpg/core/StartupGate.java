package cn.originalrpg.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded wait used only while the authoritative ledger is starting. */
final class StartupGate {
    static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    static final long MINIMUM_TIMEOUT_MILLIS = 5_000L;
    static final long MAXIMUM_TIMEOUT_MILLIS = 120_000L;

    private StartupGate() {
    }

    static long boundedTimeoutMillis(long configured) {
        return Math.clamp(configured, MINIMUM_TIMEOUT_MILLIS, MAXIMUM_TIMEOUT_MILLIS);
    }

    static <T> T await(CompletionStage<T> stage, Duration timeout, String operation) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(operation, "operation");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("startup timeout must be positive");
        }

        var future = stage.toCompletableFuture();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new IllegalStateException(operation + " timed out after " + timeout.toMillis() + " ms", exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(operation + " failed", exception.getCause());
        }
    }
}
