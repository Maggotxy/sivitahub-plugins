package cn.originalrpg.core;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Executes one fail-stop, preferring the server primary thread. */
final class FailStopCoordinator {
    private final AtomicBoolean requested = new AtomicBoolean();

    boolean request(boolean primaryThread, Runnable shutdown, Consumer<Runnable> primaryScheduler) {
        Objects.requireNonNull(shutdown, "shutdown");
        Objects.requireNonNull(primaryScheduler, "primaryScheduler");
        if (!requested.compareAndSet(false, true)) return false;
        if (primaryThread) {
            shutdown.run();
            return true;
        }
        try {
            primaryScheduler.accept(shutdown);
        } catch (RuntimeException schedulingFailure) {
            shutdown.run();
        }
        return true;
    }
}
