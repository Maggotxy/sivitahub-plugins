package cn.originalrpg.dungeons;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serializes fsync-backed reward journal work away from the server thread. */
final class DungeonRewardIoCoordinator {
    interface Store {
        void persist(DungeonRewardBatch batch) throws IOException;

        List<DungeonRewardBatch> load() throws IOException;

        void remove(DungeonRewardBatch batch) throws IOException;
    }

    private final Store store;
    private final Executor callbackExecutor;
    private final ThreadPoolExecutor executor;
    private final int maximumInFlight;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object lifecycle = new Object();
    private boolean accepting = true;

    DungeonRewardIoCoordinator(Store store, Executor callbackExecutor, int queueCapacity) {
        this.store = Objects.requireNonNull(store, "store");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        maximumInFlight = queueCapacity;
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "rpg-dungeon-reward-journal");
            // A graceful JVM stop must not silently discard an accepted durable intent.
            thread.setDaemon(false);
            return thread;
        };
        // Normal admission is capped at maximumInFlight (including the running
        // operation). The physical queue has one reserved slot for the final
        // shutdown persistence barrier, so close can never strand an intent
        // merely because normal work saturated the queue.
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threads, new ThreadPoolExecutor.AbortPolicy());
    }

    CompletableFuture<Void> persist(DungeonRewardBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return submit(() -> {
            store.persist(batch);
            return null;
        });
    }

    CompletableFuture<List<DungeonRewardBatch>> load() {
        return submit(() -> List.copyOf(store.load()));
    }

    CompletableFuture<Void> remove(DungeonRewardBatch batch) {
        Objects.requireNonNull(batch, "batch");
        return submit(() -> {
            store.remove(batch);
            return null;
        });
    }

    /**
     * Stops admission and waits for a bounded time. Timed-out accepted work is deliberately
     * not cancelled: the non-daemon worker keeps draining it so a normal JVM shutdown cannot
     * turn an acknowledged boss kill into a missing recovery intent.
     */
    boolean close(Duration maximumWait, List<DungeonRewardBatch> pendingIntents) {
        Objects.requireNonNull(maximumWait, "maximumWait");
        List<DungeonRewardBatch> finalIntents = List.copyOf(pendingIntents);
        synchronized (lifecycle) {
            if (accepting) {
                accepting = false;
                // This operation bypasses normal admission and occupies the
                // reserved slot. It runs after every already accepted write,
                // scan and delete, then idempotently establishes each intent.
                executor.execute(() -> {
                    for (DungeonRewardBatch batch : finalIntents) {
                        persistUntilDurable(batch);
                    }
                });
                executor.shutdown();
            }
        }
        try {
            return executor.awaitTermination(Math.max(0L, maximumWait.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private <T> CompletableFuture<T> submit(IoOperation<T> operation) {
        CompletableFuture<T> completion = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (!accepting || inFlight.incrementAndGet() > maximumInFlight) {
                if (accepting) inFlight.decrementAndGet();
                dispatch(() -> completion.completeExceptionally(
                        new RejectedExecutionException("Dungeon reward journal queue is full or closing")), completion);
                return completion;
            }
            try {
                executor.execute(() -> {
                    try {
                        T result = operation.run();
                        dispatch(() -> completion.complete(result), completion);
                    } catch (Throwable exception) {
                        dispatch(() -> completion.completeExceptionally(exception), completion);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException exception) {
                inFlight.decrementAndGet();
                dispatch(() -> completion.completeExceptionally(exception), completion);
            }
        }
        return completion;
    }

    private <T> void dispatch(Runnable callback, CompletableFuture<T> completion) {
        try {
            callbackExecutor.execute(callback);
        } catch (Throwable ignored) {
            // Never run a Bukkit-facing continuation on this I/O thread. During
            // plugin disable the final close barrier persists the still-pending
            // immutable batch even if the scheduler no longer accepts callbacks.
        }
    }

    private void persistUntilDurable(DungeonRewardBatch batch) {
        boolean interrupted = false;
        while (true) {
            try {
                store.persist(batch);
                if (interrupted) Thread.currentThread().interrupt();
                return;
            } catch (IOException exception) {
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException interruptedException) {
                    // Data safety wins over interruption during the final
                    // shutdown barrier; restore the flag after durability.
                    interrupted = true;
                }
            }
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
