package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import cn.originalrpg.api.model.EconomyReport;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Serializes website snapshot file access away from Bukkit and JDBC threads. */
final class AdminSnapshotIo {
    interface Store {
        EconomyExport writeEconomy(EconomyReport report) throws IOException;

        void writeChapter(ChapterState state) throws IOException;
    }

    record EconomyExport(EconomyWeekComparison comparison, IOException historyFailure) {
    }

    private final Store store;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean publishAllowed;
    private final AtomicBoolean economyPending = new AtomicBoolean();
    private final AtomicBoolean chapterPending = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private boolean accepting = true;

    AdminSnapshotIo(Path dataFolder) {
        this(new AtomicBoolean(true), dataFolder);
    }

    private AdminSnapshotIo(AtomicBoolean publishAllowed, Path dataFolder) {
        this(new FileStore(dataFolder, publishAllowed::get), 2, publishAllowed);
    }

    AdminSnapshotIo(Store store, int queueCapacity) {
        this(store, queueCapacity, new AtomicBoolean(true));
    }

    private AdminSnapshotIo(Store store, int queueCapacity, AtomicBoolean publishAllowed) {
        this.store = Objects.requireNonNull(store, "store");
        this.publishAllowed = Objects.requireNonNull(publishAllowed, "publishAllowed");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "rpg-admin-snapshot-io");
            // Snapshots are derived website data, not an authoritative journal;
            // an uncooperative filesystem must never keep the JVM alive.
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), threads, new ThreadPoolExecutor.AbortPolicy());
    }

    CompletableFuture<EconomyExport> economy(EconomyReport report) {
        Objects.requireNonNull(report, "report");
        return submit(economyPending, () -> store.writeEconomy(report));
    }

    CompletableFuture<Void> chapter(ChapterState state) {
        Objects.requireNonNull(state, "state");
        return submit(chapterPending, () -> {
            store.writeChapter(state);
            return null;
        });
    }

    /** Stops admission, drains accepted work for a bounded time, then cancels the remainder. */
    boolean close(Duration maximumWait) {
        Objects.requireNonNull(maximumWait, "maximumWait");
        synchronized (lifecycle) {
            if (accepting) {
                accepting = false;
                executor.shutdown();
            }
        }
        long waitMillis = Math.max(0L, maximumWait.toMillis());
        try {
            if (executor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS)) {
                publishAllowed.set(false);
                return true;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        // Interrupting a thread cannot forcibly abort every filesystem call.
        // Revoke publication first so a late old-generation task can never
        // atomically replace a snapshot written by a later plugin generation.
        publishAllowed.set(false);
        List<Runnable> abandoned = executor.shutdownNow();
        CancellationException cancelled = new CancellationException("snapshot I/O stopped during plugin disable");
        for (Runnable task : abandoned) {
            if (task instanceof IoTask<?> ioTask) ioTask.cancel(cancelled);
        }
        return false;
    }

    private <T> CompletableFuture<T> submit(AtomicBoolean pending, Callable<T> operation) {
        if (!pending.compareAndSet(false, true)) return rejected("snapshot type already pending");
        CompletableFuture<T> completion = new CompletableFuture<>();
        IoTask<T> task = new IoTask<>(operation, completion, pending);
        synchronized (lifecycle) {
            if (!accepting) {
                task.cancel(new CancellationException("snapshot I/O is closed"));
                return completion;
            }
            try {
                executor.execute(task);
            } catch (RejectedExecutionException exception) {
                task.cancel(exception);
            }
        }
        return completion;
    }

    private static <T> CompletableFuture<T> rejected(String reason) {
        return CompletableFuture.failedFuture(new RejectedExecutionException(reason));
    }

    private static final class IoTask<T> implements Runnable {
        private final Callable<T> operation;
        private final CompletableFuture<T> completion;
        private final AtomicBoolean pending;

        private IoTask(Callable<T> operation, CompletableFuture<T> completion, AtomicBoolean pending) {
            this.operation = operation;
            this.completion = completion;
            this.pending = pending;
        }

        @Override
        public void run() {
            try {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("snapshot I/O interrupted");
                completion.complete(operation.call());
            } catch (Throwable exception) {
                completion.completeExceptionally(exception);
            } finally {
                pending.set(false);
            }
        }

        void cancel(Throwable reason) {
            pending.set(false);
            completion.completeExceptionally(reason);
        }
    }

    private static final class FileStore implements Store {
        private final Path dataFolder;
        private final BooleanSupplier publishAllowed;

        private FileStore(Path dataFolder, BooleanSupplier publishAllowed) {
            this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
            this.publishAllowed = Objects.requireNonNull(publishAllowed, "publishAllowed");
        }

        @Override
        public EconomyExport writeEconomy(EconomyReport report) throws IOException {
            EconomyWeekComparison comparison = null;
            IOException historyFailure = null;
            try {
                comparison = EconomyHistoryStore.update(
                        dataFolder, report, Instant.now(), publishAllowed);
            } catch (IOException exception) {
                historyFailure = exception;
            }
            try {
                EconomySnapshotWriter.write(dataFolder, report, comparison, publishAllowed);
            } catch (IOException exception) {
                if (historyFailure != null) exception.addSuppressed(historyFailure);
                throw exception;
            }
            return new EconomyExport(comparison, historyFailure);
        }

        @Override
        public void writeChapter(ChapterState state) throws IOException {
            ChapterSnapshotWriter.write(dataFolder, state, publishAllowed);
        }
    }
}
