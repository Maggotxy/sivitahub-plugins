package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static cn.originalrpg.mobs.MobRewardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real worker, filesystem and shutdown behavior replaces the former source-text ordering assertions. */
final class MobRewardAsyncLifecycleContractTest {
    @TempDir Path directory;

    @Test
    void blockingDiskDoesNotRunOnTheCallerAndCannotGrowTheWorkerQueuePastItsBudget() throws Exception {
        BlockingStore disk = new BlockingStore(directory);
        MobRewardIoCoordinator io = new MobRewardIoCoordinator(disk, 2, quietLogger());
        MobRewardIntent first = reward(UUID.randomUUID(), 10L, 1);
        MobRewardIntent second = reward(UUID.randomUUID(), 20L, 1);
        try {
            CompletableFuture<MobRewardIntent> a = io.persist(first);
            assertTrue(disk.started.await(2, TimeUnit.SECONDS));
            CompletableFuture<MobRewardIntent> b = io.persist(second);
            CompletableFuture<MobRewardIntent> rejected = io.persist(reward(UUID.randomUUID(), 30L, 1));
            assertFalse(a.isDone()); assertFalse(b.isDone());
            assertTrue(rejected.isCompletedExceptionally());
            assertThrows(CompletionException.class, rejected::join);
            assertNotSame(Thread.currentThread(), disk.worker.get());
            assertTrue(disk.worker.get().isDaemon(), "A failed disk must not keep JVM shutdown alive indefinitely");
            disk.release.countDown();
            assertEquals(first, a.get(2, TimeUnit.SECONDS));
            assertEquals(second, b.get(2, TimeUnit.SECONDS));
            assertFalse(io.close(Duration.ofSeconds(2), List.of()).timedOut());
            assertEquals(1, disk.maximumConcurrent.get());
            try (MobRewardJournal reopened = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
                assertEquals(2, reopened.load().size());
            }
        } finally {
            disk.release.countDown();
            io.close(Duration.ofSeconds(2), List.of());
        }
    }

    @Test
    void timedOutShutdownReportsUnconfirmedAcceptanceAndLeavesARecoverableLateWrite() throws Exception {
        BlockingStore disk = new BlockingStore(directory);
        MobRewardIoCoordinator io = new MobRewardIoCoordinator(disk, 1, quietLogger());
        MobRewardIntent original = reward(UUID.randomUUID(), 25L, 2);
        CompletableFuture<MobRewardIntent> write = io.persist(original);
        assertTrue(disk.started.await(2, TimeUnit.SECONDS));
        try {
            long started = System.nanoTime();
            MobRewardIoCoordinator.ShutdownReport report = io.close(Duration.ofMillis(25), List.of(original));
            assertTrue(report.timedOut());
            assertEquals(List.of(original.killId()), report.unconfirmed());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "Shutdown must not wait indefinitely for disk I/O");
            assertTrue(io.persist(original).isCompletedExceptionally());
        } finally { disk.release.countDown(); }
        write.get(2, TimeUnit.SECONDS);
        assertTrue(disk.closed.await(2, TimeUnit.SECONDS));
        try (MobRewardJournal reopened = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            assertEquals(List.of(original), reopened.load());
        }
    }

    @Test
    void shutdownMakesOnlyOneFinalAttemptPerUnconfirmedIntentWhenDiskKeepsFailing() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        MobRewardIoCoordinator io = new MobRewardIoCoordinator(new MobRewardIoCoordinator.Store() {
            @Override public List<MobRewardIntent> load() { return List.of(); }
            @Override public MobRewardIntent persist(MobRewardIntent intent) throws IOException {
                attempts.incrementAndGet(); throw new IOException("disk remains unavailable");
            }
            @Override public void remove(MobRewardIntent intent) { }
            @Override public void close() { closes.incrementAndGet(); }
        }, 2, quietLogger());
        MobRewardIntent a = reward(UUID.randomUUID(), 10L, 1);
        MobRewardIntent b = reward(UUID.randomUUID(), 20L, 0);
        MobRewardIoCoordinator.ShutdownReport result = io.close(Duration.ofSeconds(2), List.of(a, b));
        assertFalse(result.timedOut());
        assertEquals(List.of(a.killId(), b.killId()), result.unconfirmed());
        assertEquals(2, attempts.get()); assertEquals(1, closes.get());
        assertTrue(io.load().isCompletedExceptionally());
    }

    @Test
    void finalPassCannotDowngradeAnAlreadyPersistedEligibilityDecision() throws Exception {
        MobRewardJournal journal = new MobRewardJournal(directory, 8, BYTE_LIMIT);
        MobRewardIoCoordinator io = new MobRewardIoCoordinator(journal, 2, quietLogger());
        MobRewardIntent candidate = unknown(reward(UUID.randomUUID(), 50L, 2));
        MobRewardIntent resolved = candidate.resolve(10);
        io.persist(resolved).get(2, TimeUnit.SECONDS);
        MobRewardIoCoordinator.ShutdownReport report = io.close(Duration.ofSeconds(2), List.of(candidate));
        assertFalse(report.timedOut()); assertTrue(report.unconfirmed().isEmpty());
        try (MobRewardJournal reopened = new MobRewardJournal(directory, 8, BYTE_LIMIT)) {
            assertEquals(List.of(resolved), reopened.load());
        }
    }

    @Test
    void filesystemCompletionsReturnToTheOwnerBeforeInvokingAuthoritiesOrUiObservers() throws Exception {
        Thread owner = Thread.currentThread();
        AtomicInteger wrongThread = new AtomicInteger();
        AtomicInteger acceptedNotifications = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        MobRewardIoCoordinator io = new MobRewardIoCoordinator(new MobRewardJournal(directory, 8, BYTE_LIMIT), 2, quietLogger());
        RecordingAuthority authority = new RecordingAuthority() {
            @Override public java.util.concurrent.CompletionStage<Boolean> experience(MobRewardIntent intent) {
                if (Thread.currentThread() != owner) wrongThread.incrementAndGet();
                return super.experience(intent);
            }
            @Override public java.util.concurrent.CompletionStage<Boolean> delivery(MobRewardIntent intent, MobRewardIntent.Delivery delivery) {
                if (Thread.currentThread() != owner) wrongThread.incrementAndGet();
                return super.delivery(intent, delivery);
            }
        };
        MobRewardSettlement engine = new MobRewardSettlement(io, authority, new MobRewardSettlement.Observer() {
            @Override public void durable(MobRewardIntent intent) {
                acceptedNotifications.incrementAndGet();
                if (Thread.currentThread() != owner) wrongThread.incrementAndGet();
            }
        }, limits(), clock::get);
        boolean offered = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            do {
                engine.tick(); clock.incrementAndGet();
                if (!offered && !engine.status().recovering()) {
                    assertEquals(MobRewardSettlement.Admission.QUEUED_NOT_DURABLE,
                            engine.offer(reward(UUID.randomUUID(), 10L, 1)));
                    offered = true;
                }
                Thread.sleep(2L);
            } while ((!offered || engine.status().pending() > 0) && System.nanoTime() < deadline);
            assertTrue(offered);
            assertEquals(0, engine.status().pending());
            assertEquals(1, acceptedNotifications.get());
            assertEquals(1, authority.xpReceipts.size());
            assertEquals(1, authority.mailbox.size());
            assertEquals(0, wrongThread.get());
        } finally { io.close(Duration.ofSeconds(2), engine.close()); }
    }

    private static Logger quietLogger() {
        Logger logger = Logger.getAnonymousLogger(); logger.setLevel(Level.OFF); return logger;
    }

    private static final class BlockingStore implements MobRewardIoCoordinator.Store {
        final MobRewardJournal journal;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maximumConcurrent = new AtomicInteger();
        BlockingStore(Path directory) { journal = new MobRewardJournal(directory, 8, BYTE_LIMIT); }
        @Override public List<MobRewardIntent> load() throws IOException { return journal.load(); }
        @Override public MobRewardIntent persist(MobRewardIntent intent) throws IOException {
            worker.set(Thread.currentThread());
            maximumConcurrent.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test did not release blocked I/O");
                return journal.persist(intent);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IOException(error);
            } finally { active.decrementAndGet(); }
        }
        @Override public void remove(MobRewardIntent intent) throws IOException { journal.remove(intent); }
        @Override public void close() throws IOException {
            try { journal.close(); } finally { closed.countDown(); }
        }
    }
}
