package cn.originalrpg.mobs;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Bounded single-worker filesystem adapter; no Bukkit state is touched here.
 *
 * <p>Futures complete on the worker. The settlement owner must enqueue immutable
 * completion messages and consume them on its own thread, never execute a Bukkit
 * continuation on this executor. Submission is not a durability acknowledgement.</p>
 */
final class MobRewardIoCoordinator implements MobRewardSettlement.Store {
    interface Store extends AutoCloseable {
        List<MobRewardIntent> load() throws IOException;
        MobRewardIntent persist(MobRewardIntent intent) throws IOException;
        void remove(MobRewardIntent intent) throws IOException;
        @Override default void close() throws IOException { }
    }

    private final Store store;
    private final Logger logger;
    private final ThreadPoolExecutor executor;
    private final int capacity;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object lifecycle = new Object();
    private boolean accepting = true;
    private CompletableFuture<ShutdownReport> shutdown;

    MobRewardIoCoordinator(Store store, int capacity, Logger logger) {
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
        if (capacity < 1) throw new IllegalArgumentException("Journal queue capacity must be positive");
        this.capacity = capacity;
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "rpg-mob-reward-journal");
                    // Already acknowledged files survive independently. A failed disk
                    // must not trap normal JVM shutdown in an infinite retry loop.
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public CompletableFuture<List<MobRewardIntent>> load() {
        return submit(() -> List.copyOf(store.load()));
    }

    @Override public CompletableFuture<MobRewardIntent> persist(MobRewardIntent intent) {
        return submit(() -> store.persist(intent));
    }

    @Override public CompletableFuture<Void> remove(MobRewardIntent intent) {
        return submit(() -> { store.remove(intent); return null; });
    }

    private <T> CompletableFuture<T> submit(IoTask<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (!accepting || inFlight.get() >= capacity) {
                result.completeExceptionally(new RejectedExecutionException("Mob reward journal is full or closing"));
                return result;
            }
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> {
                    try { result.complete(action.run()); }
                    catch (Throwable error) { result.completeExceptionally(error); }
                    finally { inFlight.decrementAndGet(); }
                });
            } catch (RejectedExecutionException error) {
                inFlight.decrementAndGet();
                result.completeExceptionally(error);
            }
        }
        return result;
    }

    /**
     * Stops admission and makes one bounded best-effort final persistence pass.
     * The reserved executor slot is never used by normal submissions. Accepted
     * files need no database drain; uncertain database work replays by stable ID.
     * A timeout or an unconfirmed ID is reported explicitly, never called durable.
     */
    ShutdownReport close(Duration maximumWait, List<MobRewardIntent> notYetDurable) {
        if (maximumWait.isNegative()) throw new IllegalArgumentException("Negative shutdown timeout");
        List<MobRewardIntent> finalPass = List.copyOf(notYetDurable);
        long deadline = System.nanoTime() + maximumWait.toNanos();
        synchronized (lifecycle) {
            if (accepting) {
                accepting = false;
                shutdown = new CompletableFuture<>();
                executor.execute(() -> {
                    List<UUID> unconfirmed = new ArrayList<>();
                    String closeError = null;
                    for (MobRewardIntent intent : finalPass) {
                        if (System.nanoTime() >= deadline) {
                            unconfirmed.add(intent.killId());
                            continue;
                        }
                        try { store.persist(intent); }
                        catch (Exception error) { unconfirmed.add(intent.killId()); }
                    }
                    try { store.close(); }
                    catch (Exception error) { closeError = error.toString(); }
                    ShutdownReport report = new ShutdownReport(false, unconfirmed, closeError);
                    if (!unconfirmed.isEmpty()) logger.severe("Mob reward durability UNCONFIRMED at shutdown: count="
                            + unconfirmed.size() + " killIds=" + unconfirmed.stream().limit(16).toList());
                    if (closeError != null) logger.severe("Mob reward journal close failed: " + closeError);
                    shutdown.complete(report);
                });
                executor.shutdown();
            }
        }
        try {
            long remaining = Math.max(0L, deadline - System.nanoTime());
            if (executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return shutdown.join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        logger.severe("Mob reward journal shutdown wait expired; queued writes are NOT an acceptance guarantee. "
                + "Already fsynced intents remain recoverable; unconfirmed candidate count=" + finalPass.size());
        return new ShutdownReport(true, finalPass.stream().map(MobRewardIntent::killId).toList(), null);
    }

    record ShutdownReport(boolean timedOut, List<UUID> unconfirmed, String closeError) {
        ShutdownReport { unconfirmed = List.copyOf(unconfirmed); }
    }

    @FunctionalInterface private interface IoTask<T> { T run() throws IOException; }
}
