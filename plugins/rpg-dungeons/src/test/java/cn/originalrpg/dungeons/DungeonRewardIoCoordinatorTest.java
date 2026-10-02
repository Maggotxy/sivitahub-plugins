package cn.originalrpg.dungeons;

import cn.originalrpg.api.model.DungeonRewardDelivery;
import cn.originalrpg.api.model.DungeonRewardRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonRewardIoCoordinatorTest {
    @Test
    void serializesWholeBatchesAndCompletesOnlyAfterDurableStoreReturns() throws Exception {
        LinkedBlockingQueue<Runnable> mainCallbacks = new LinkedBlockingQueue<>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<UUID> order = java.util.Collections.synchronizedList(new ArrayList<>());
        DungeonRewardIoCoordinator.Store store = new StubStore() {
            @Override
            public void persist(DungeonRewardBatch batch) throws IOException {
                order.add(batch.runId());
                if (order.size() == 1) {
                    firstEntered.countDown();
                    await(releaseFirst);
                }
            }
        };
        DungeonRewardIoCoordinator coordinator = new DungeonRewardIoCoordinator(
                store, mainCallbacks::add, 4);
        DungeonRewardBatch first = batch("first");
        DungeonRewardBatch second = batch("second");

        CompletableFuture<Void> firstDone = coordinator.persist(first);
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> secondDone = coordinator.persist(second);
        assertFalse(firstDone.isDone());
        assertFalse(secondDone.isDone());
        releaseFirst.countDown();

        runCallback(mainCallbacks);
        runCallback(mainCallbacks);
        assertEquals(List.of(first.runId(), second.runId()), order);
        assertTrue(firstDone.isDone());
        assertTrue(secondDone.isDone());
        assertTrue(coordinator.close(Duration.ofSeconds(2), List.of()));
    }

    @Test
    void persistenceFailurePreventsDependentSettlementAndRemainsExceptional() throws Exception {
        LinkedBlockingQueue<Runnable> mainCallbacks = new LinkedBlockingQueue<>();
        DungeonRewardIoCoordinator coordinator = new DungeonRewardIoCoordinator(new StubStore() {
            @Override
            public void persist(DungeonRewardBatch batch) throws IOException {
                throw new IOException("disk unavailable");
            }
        }, mainCallbacks::add, 2);
        AtomicBoolean settlementStarted = new AtomicBoolean();

        CompletableFuture<Void> persisted = coordinator.persist(batch("failure"));
        persisted.thenRun(() -> settlementStarted.set(true));
        runCallback(mainCallbacks);

        assertTrue(persisted.isCompletedExceptionally());
        assertFalse(settlementStarted.get());
        assertThrows(java.util.concurrent.CompletionException.class, persisted::join);
        assertTrue(coordinator.close(Duration.ofSeconds(2), List.of()));
    }

    @Test
    void boundedAdmissionRejectsWholeBatchWithoutPartialMemberWrites() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        DungeonRewardIoCoordinator coordinator = new DungeonRewardIoCoordinator(new StubStore() {
            @Override
            public void persist(DungeonRewardBatch batch) throws IOException {
                writes.incrementAndGet();
                entered.countDown();
                await(release);
            }
        }, Runnable::run, 1);

        CompletableFuture<Void> accepted = coordinator.persist(batch("accepted"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> rejected = coordinator.persist(batch("rejected"));

        assertTrue(rejected.isCompletedExceptionally());
        assertEquals(1, writes.get(), "a rejected party is never split into per-member writes");
        release.countDown();
        accepted.get(2, TimeUnit.SECONDS);
        assertTrue(coordinator.close(Duration.ofSeconds(2), List.of()));
    }

    @Test
    void closeUsesReservedBarrierToPersistIntentRejectedBySaturatedQueue() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closingIntentWritten = new CountDownLatch(1);
        DungeonRewardBatch active = batch("active");
        DungeonRewardBatch pending = batch("pending-at-close");
        List<UUID> writes = java.util.Collections.synchronizedList(new ArrayList<>());
        DungeonRewardIoCoordinator coordinator = new DungeonRewardIoCoordinator(new StubStore() {
            @Override
            public void persist(DungeonRewardBatch batch) throws IOException {
                writes.add(batch.runId());
                if (batch.runId().equals(active.runId())) {
                    entered.countDown();
                    await(release);
                }
                if (batch.runId().equals(pending.runId())) closingIntentWritten.countDown();
            }
        }, Runnable::run, 1);

        coordinator.persist(active);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(coordinator.persist(pending).isCompletedExceptionally());
        assertFalse(coordinator.close(Duration.ofMillis(20), List.of(pending)));
        release.countDown();

        assertTrue(closingIntentWritten.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(active.runId(), pending.runId()), writes);
    }

    @Test
    void rejectedDisableCallbackNeverRunsBukkitFacingContinuationOnJournalThread() throws Exception {
        CountDownLatch durable = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        DungeonRewardBatch pending = batch("disable-race");
        DungeonRewardIoCoordinator coordinator = new DungeonRewardIoCoordinator(new StubStore() {
            @Override
            public void persist(DungeonRewardBatch batch) {
                writes.incrementAndGet();
                durable.countDown();
            }
        }, ignored -> {
            throw new java.util.concurrent.RejectedExecutionException("plugin disabled");
        }, 2);
        AtomicBoolean continuationRan = new AtomicBoolean();

        CompletableFuture<Void> completion = coordinator.persist(pending);
        completion.whenComplete((ignored, error) -> continuationRan.set(true));
        assertTrue(durable.await(2, TimeUnit.SECONDS));

        assertFalse(completion.isDone(),
                "a rejected main-thread dispatch must not fall back to the journal worker");
        assertFalse(continuationRan.get());
        assertTrue(coordinator.close(Duration.ofSeconds(2), List.of(pending)));
        assertEquals(2, writes.get(),
                "the disable barrier idempotently re-establishes an intent whose callback was not delivered");
    }

    private static void runCallback(LinkedBlockingQueue<Runnable> callbacks) throws Exception {
        Runnable callback = callbacks.poll(2, TimeUnit.SECONDS);
        assertTrue(callback != null, "journal operation did not dispatch its main-thread callback");
        callback.run();
    }

    private static DungeonRewardBatch batch(String source) {
        UUID run = UUID.nameUUIDFromBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<DungeonRewardRequest> requests = List.of(
                request(run, source + "-one"), request(run, source + "-two"));
        return new DungeonRewardBatch(run, requests);
    }

    private static DungeonRewardRequest request(UUID run, String source) {
        UUID player = UUID.nameUUIDFromBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new DungeonRewardRequest(run, "ancient-ruins", player, 100L,
                List.of(new DungeonRewardDelivery(UUID.randomUUID(), source, "source:" + source)),
                Optional.empty());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new IOException("test latch timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", exception);
        }
    }

    private static class StubStore implements DungeonRewardIoCoordinator.Store {
        @Override
        public void persist(DungeonRewardBatch batch) throws IOException {
        }

        @Override
        public List<DungeonRewardBatch> load() throws IOException {
            return List.of();
        }

        @Override
        public void remove(DungeonRewardBatch batch) throws IOException {
        }
    }
}
