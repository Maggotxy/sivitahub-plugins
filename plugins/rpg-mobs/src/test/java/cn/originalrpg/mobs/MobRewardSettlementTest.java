package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

import static cn.originalrpg.mobs.MobRewardFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

final class MobRewardSettlementTest {
    @TempDir Path directory;

    @Test
    void neitherXpNorItemsAreSubmittedBeforePersistenceAcknowledgesTheExactIntent() {
        AtomicLong clock = new AtomicLong();
        HoldingStore store = new HoldingStore();
        RecordingAuthority authority = new RecordingAuthority();
        MobRewardSettlement engine = engine(store, authority, clock);
        ready(engine, clock);
        MobRewardIntent intent = reward(UUID.randomUUID(), 100L, 2);
        assertEquals(MobRewardSettlement.Admission.QUEUED_NOT_DURABLE, engine.offer(intent));
        for (int index = 0; index < 20; index++) engine.tick();
        assertEquals(1, store.writes.size());
        assertEquals(0, engine.status().durable());
        assertTrue(authority.xpAttempts.isEmpty());
        assertTrue(authority.mailboxAttempts.isEmpty());
        store.releaseWrites();
        until(engine, clock, () -> engine.status().pending() == 0);
        assertEquals(100L, authority.grantedExperience());
        assertEquals(2, authority.mailbox.size());
    }

    @Test
    void aMissingProfileDecisionMustBeSavedBeforeAnyAwardAndIsNeverRecomputedAfterRestart() throws Exception {
        AtomicLong clock = new AtomicLong();
        RecordingAuthority authority = new RecordingAuthority();
        MobRewardIntent candidate = unknown(reward(UUID.randomUUID(), 80L, 2));
        try (DirectStore disk = new DirectStore(directory)) {
            DecisionBarrier barrier = new DecisionBarrier(disk);
            MobRewardSettlement engine = engine(barrier, authority, clock);
            ready(engine, clock);
            engine.offer(candidate);
            until(engine, clock, () -> barrier.decision != null);
            assertEquals(List.of(candidate), disk.journal.load(), "Only the conditional observation is durable so far");
            assertEquals(1, authority.profileLoads);
            assertTrue(authority.xpAttempts.isEmpty());
            assertTrue(authority.mailboxAttempts.isEmpty());
            barrier.acknowledge();
            engine.tick();
            assertEquals(MobRewardIntent.Eligibility.ELIGIBLE, disk.journal.load().getFirst().eligibility());
            engine.close();
        }
        authority.level = 100; // Would fail the gate if replay wrongly looked up qualification again.
        try (DirectStore disk = new DirectStore(directory)) {
            MobRewardSettlement recovered = engine(disk, authority, clock);
            until(recovered, clock, () -> !recovered.status().recovering() && recovered.status().pending() == 0);
            assertEquals(1, authority.profileLoads);
            assertEquals(80L, authority.grantedExperience());
            assertEquals(2, authority.mailbox.size());
            assertTrue(authority.observed.stream().allMatch(intent -> intent.playerEffectiveLevel() == 10));
        }
    }

    @Test
    void lostCommitRepliesAndFailedCleanupReplayThroughTheOriginalAuthoritiesExactlyOnce() throws Exception {
        for (String fault : List.of("xp-response", "second-mail-response", "cleanup")) {
            Path folder = directory.resolve(fault);
            AtomicLong clock = new AtomicLong();
            RecordingAuthority authority = new RecordingAuthority();
            authority.loseXpResponse = fault.equals("xp-response");
            authority.loseDeliveryResponseAt = fault.equals("second-mail-response") ? 2 : 0;
            MobRewardIntent original = reward(UUID.randomUUID(), 420L, 2);
            try (DirectStore disk = new DirectStore(folder)) {
                disk.failRemoval = fault.equals("cleanup");
                MobRewardSettlement first = engine(disk, authority, clock);
                ready(first, clock);
                first.offer(original);
                until(first, clock, () -> switch (fault) {
                    case "xp-response" -> !authority.xpAttempts.isEmpty();
                    case "second-mail-response" -> authority.mailboxAttempts.size() >= 2;
                    default -> first.status().paused();
                });
                assertEquals(List.of(original), disk.journal.load());
                assertTrue(first.close().isEmpty(), "An acknowledged file is retained, not re-minted by shutdown");
            }
            authority.level = 200;
            try (DirectStore disk = new DirectStore(folder)) {
                MobRewardSettlement recovered = engine(disk, authority, clock);
                until(recovered, clock, () -> !recovered.status().recovering() && recovered.status().pending() == 0);
                assertEquals(420L, authority.grantedExperience(), fault);
                assertEquals(1, authority.xpReceipts.size(), fault);
                assertEquals(2, authority.mailbox.size(), fault);
                assertTrue(authority.xpAttempts.size() >= 2, "Recovery must replay the same receipt rather than trusting memory");
                assertTrue(authority.xpAttempts.stream().allMatch(original.experienceGrantId()::equals));
                for (MobRewardIntent.Delivery delivery : original.deliveries()) assertEquals(delivery, authority.mailbox.get(delivery.deliveryId()));
                assertTrue(authority.observed.stream().allMatch(intent -> intent.equals(original)), "No random reroll, pity rewrite or byte regeneration");
                assertEquals(List.of(), disk.journal.load());
            }
        }
    }

    @Test
    void anIneligibleLaterProfileProducesNoCustomAwardDespiteFrozenCandidateDrops() throws Exception {
        AtomicLong clock = new AtomicLong();
        RecordingAuthority authority = new RecordingAuthority();
        authority.level = 100;
        List<MobRewardIntent> rejected = new ArrayList<>();
        MobRewardIntent candidate = unknown(reward(UUID.randomUUID(), 100L, 2));
        try (DirectStore disk = new DirectStore(directory)) {
            MobRewardSettlement engine = new MobRewardSettlement(disk, authority, new MobRewardSettlement.Observer() {
                @Override public void ineligible(MobRewardIntent intent) { rejected.add(intent); }
            }, limits(), clock::get);
            ready(engine, clock);
            engine.offer(candidate);
            until(engine, clock, () -> engine.status().pending() == 0);
            assertTrue(authority.xpReceipts.isEmpty());
            assertTrue(authority.mailbox.isEmpty());
            assertEquals(List.of(candidate.resolve(100)), rejected);
            assertEquals(List.of(), disk.journal.load());
        }
    }

    @Test
    void profileOutageAndLongElapsedTimeDoNotExpireAnAcceptedCandidate() throws Exception {
        AtomicLong clock = new AtomicLong();
        RecordingAuthority authority = new RecordingAuthority();
        authority.failProfiles = 4;
        MobRewardIntent candidate = unknown(reward(UUID.randomUUID(), 70L, 1));
        try (DirectStore disk = new DirectStore(directory)) {
            MobRewardSettlement engine = engine(disk, authority, clock);
            ready(engine, clock); engine.offer(candidate);
            until(engine, clock, () -> authority.profileLoads == 1);
            clock.addAndGet(365L * 24 * 60 * 60 * 1000);
            until(engine, clock, () -> authority.profileLoads == 4);
            assertEquals(1, engine.status().pending());
            assertEquals(List.of(candidate), disk.journal.load());
            until(engine, clock, () -> engine.status().pending() == 0);
            assertEquals(70L, authority.grantedExperience());
            assertEquals(1, authority.mailbox.size());
        }
    }

    @Test
    void globalPlayerAndActualPayloadBudgetsRejectNewObservationsWithoutChangingExistingOnes() {
        AtomicLong clock = new AtomicLong();
        HoldingStore store = new HoldingStore();
        RecordingAuthority authority = new RecordingAuthority();
        MobRewardSettlement engine = new MobRewardSettlement(store, authority, new MobRewardSettlement.Observer() { },
                new MobRewardSettlement.Limits(3, 1, BYTE_LIMIT, 2, 1, 1, 1, 8, 1, 8), clock::get);
        ready(engine, clock);
        UUID player = UUID.randomUUID();
        MobRewardIntent first = reward(player, 10L, 1);
        assertEquals(MobRewardSettlement.Admission.QUEUED_NOT_DURABLE, engine.offer(first));
        assertEquals(MobRewardSettlement.Admission.ALREADY_PENDING, engine.offer(first));
        assertEquals(MobRewardSettlement.Admission.CONFLICT, engine.offer(withPayload(first, 8)));
        assertEquals(MobRewardSettlement.Admission.CAPACITY, engine.offer(reward(player, 10L, 0)));
        assertEquals(MobRewardSettlement.Admission.QUEUED_NOT_DURABLE, engine.offer(reward(UUID.randomUUID(), 10L, 0)));
        assertEquals(MobRewardSettlement.Admission.QUEUED_NOT_DURABLE, engine.offer(reward(UUID.randomUUID(), 10L, 0)));
        assertEquals(MobRewardSettlement.Admission.CAPACITY, engine.offer(reward(UUID.randomUUID(), 10L, 0)));
        assertEquals(3, engine.status().pending());
        assertTrue(authority.xpAttempts.isEmpty());

        MobRewardIntent large = withPayload(reward(UUID.randomUUID(), 10L, 2), 512);
        MobRewardSettlement bytes = new MobRewardSettlement(store, authority, new MobRewardSettlement.Observer() { },
                new MobRewardSettlement.Limits(8, 8, large.budgetBytes(), 1, 1, 1, 1, 8, 1, 8), clock::get);
        ready(bytes, clock);
        assertEquals(MobRewardSettlement.Admission.QUEUED_NOT_DURABLE, bytes.offer(large));
        assertEquals(MobRewardSettlement.Admission.CAPACITY, bytes.offer(reward(UUID.randomUUID(), 10L, 0)));
        assertEquals(large.budgetBytes(), bytes.status().payloadBytes());
    }

    @Test
    void diskFailurePausesAdmissionButRetainsTheObservationForRetry() {
        AtomicLong clock = new AtomicLong();
        HoldingStore store = new HoldingStore();
        RecordingAuthority authority = new RecordingAuthority();
        MobRewardSettlement engine = engine(store, authority, clock);
        ready(engine, clock);
        MobRewardIntent original = reward(UUID.randomUUID(), 30L, 1);
        engine.offer(original); engine.tick();
        store.writes.removeFirst().completion.completeExceptionally(new IOException("disk full"));
        engine.tick();
        assertTrue(engine.status().paused());
        assertEquals(MobRewardSettlement.Admission.PAUSED, engine.offer(reward(UUID.randomUUID(), 10L, 0)));
        assertEquals(1, engine.status().pending());
        assertTrue(authority.xpAttempts.isEmpty());
        clock.addAndGet(100L); engine.tick();
        store.releaseWrites();
        until(engine, clock, () -> engine.status().pending() == 0);
        assertFalse(engine.status().paused());
        assertEquals(30L, authority.grantedExperience());
    }

    @Test
    void journalPersistenceCanProceedWhileEarlierPerPlayerSettlementWaits() {
        AtomicLong clock = new AtomicLong();
        HoldingStore store = new HoldingStore();
        CompletableFuture<Boolean> firstXp = new CompletableFuture<>();
        List<UUID> xpOrder = new ArrayList<>();
        RecordingAuthority authority = new RecordingAuthority() {
            @Override public CompletionStage<Boolean> experience(MobRewardIntent intent) {
                xpOrder.add(intent.killId());
                return xpOrder.size() == 1 ? firstXp : CompletableFuture.completedFuture(true);
            }
        };
        MobRewardSettlement engine = engine(store, authority, clock);
        ready(engine, clock);
        UUID player = UUID.randomUUID();
        MobRewardIntent first = reward(player, 10L, 0);
        MobRewardIntent second = reward(player, 20L, 0);
        engine.offer(first); engine.offer(second); engine.tick();
        assertEquals(2, store.writes.size(), "Both kills must reach the journal promptly");
        store.releaseWrites(); engine.tick();
        for (int index = 0; index < 20; index++) engine.tick();
        assertEquals(2, engine.status().durable());
        assertEquals(List.of(first.killId()), xpOrder, "Same-player settlement is FIFO even with asynchronous authority calls");
        firstXp.complete(true);
        until(engine, clock, () -> engine.status().pending() == 0);
        assertEquals(List.of(first.killId(), second.killId()), xpOrder);
    }

    @Test
    void loadBurstStaysWithinIoProfileXpAndMailboxConcurrencyBudgets() {
        AtomicLong clock = new AtomicLong();
        HoldingStore store = new HoldingStore();
        HoldingAuthority authority = new HoldingAuthority();
        MobRewardSettlement.Limits limits = new MobRewardSettlement.Limits(40, 1, BYTE_LIMIT, 2, 3, 4, 5, 64, 1, 8);
        MobRewardSettlement engine = new MobRewardSettlement(store, authority, new MobRewardSettlement.Observer() { }, limits, clock::get);
        ready(engine, clock);
        for (int index = 0; index < 40; index++) assertEquals(MobRewardSettlement.Admission.QUEUED_NOT_DURABLE,
                engine.offer(unknown(reward(UUID.randomUUID(), 10L, 2))));
        assertEquals(MobRewardSettlement.Admission.CAPACITY, engine.offer(reward(UUID.randomUUID(), 10L, 0)));
        int maximumObservedIo = 0;
        for (int tick = 0; tick < 500 && engine.status().pending() > 0; tick++) {
            engine.tick(); clock.incrementAndGet();
            MobRewardSettlement.Status status = engine.status();
            maximumObservedIo = Math.max(maximumObservedIo, status.ioInFlight());
            assertTrue(status.ioInFlight() <= 2);
            assertTrue(status.profilesInFlight() <= 3);
            assertTrue(status.experienceInFlight() <= 4);
            assertTrue(status.mailboxInFlight() <= 5);
            assertTrue(status.pending() <= 40);
            assertTrue(status.payloadBytes() <= BYTE_LIMIT);
            store.releaseWrites();
            authority.release();
        }
        assertEquals(2, maximumObservedIo, "A held I/O burst must actually exercise the configured cap");
        assertEquals(0, engine.status().pending());
        assertEquals(40, authority.experienceCalls);
        assertEquals(80, authority.mailCalls, "At most two custom items per kill; no XP mailbox records");
    }

    @Test
    void closeReturnsOnlyUnconfirmedObservationsAndRevokesLateContinuationEffects() {
        AtomicLong clock = new AtomicLong();
        HoldingStore store = new HoldingStore();
        RecordingAuthority authority = new RecordingAuthority();
        MobRewardSettlement engine = engine(store, authority, clock);
        ready(engine, clock);
        MobRewardIntent original = reward(UUID.randomUUID(), 100L, 2);
        engine.offer(original); engine.tick();
        assertEquals(List.of(original), engine.close());
        store.releaseWrites();
        engine.tick();
        assertTrue(authority.xpAttempts.isEmpty());
        assertTrue(authority.mailboxAttempts.isEmpty());
        assertEquals(MobRewardSettlement.Admission.CLOSED, engine.offer(reward(UUID.randomUUID(), 10L, 0)));
    }

    private static final class HoldingStore implements MobRewardSettlement.Store {
        final List<Write> writes = new ArrayList<>();
        @Override public CompletionStage<List<MobRewardIntent>> load() { return CompletableFuture.completedFuture(List.of()); }
        @Override public CompletionStage<MobRewardIntent> persist(MobRewardIntent intent) {
            CompletableFuture<MobRewardIntent> future = new CompletableFuture<>();
            writes.add(new Write(intent, future)); return future;
        }
        @Override public CompletionStage<Void> remove(MobRewardIntent intent) { return CompletableFuture.completedFuture(null); }
        void releaseWrites() { List<Write> pending = List.copyOf(writes); writes.clear(); pending.forEach(write -> write.completion.complete(write.intent)); }
    }
    private record Write(MobRewardIntent intent, CompletableFuture<MobRewardIntent> completion) { }

    private static final class DecisionBarrier implements MobRewardSettlement.Store {
        final DirectStore disk;
        MobRewardIntent decision;
        CompletableFuture<MobRewardIntent> acknowledgement;
        DecisionBarrier(DirectStore disk) { this.disk = disk; }
        @Override public CompletionStage<List<MobRewardIntent>> load() { return disk.load(); }
        @Override public CompletionStage<MobRewardIntent> persist(MobRewardIntent intent) {
            if (intent.eligibility() == MobRewardIntent.Eligibility.UNKNOWN) return disk.persist(intent);
            decision = intent; acknowledgement = new CompletableFuture<>(); return acknowledgement;
        }
        void acknowledge() throws IOException { acknowledgement.complete(disk.journal.persist(decision)); }
        @Override public CompletionStage<Void> remove(MobRewardIntent intent) { return disk.remove(intent); }
    }

    private static final class HoldingAuthority implements MobRewardSettlement.Authority {
        final List<Runnable> pending = new ArrayList<>();
        int experienceCalls;
        int mailCalls;
        @Override public CompletionStage<Integer> effectiveLevel(UUID playerId) {
            CompletableFuture<Integer> future = new CompletableFuture<>(); pending.add(() -> future.complete(10)); return future;
        }
        @Override public CompletionStage<Boolean> experience(MobRewardIntent intent) {
            experienceCalls++;
            CompletableFuture<Boolean> future = new CompletableFuture<>(); pending.add(() -> future.complete(true)); return future;
        }
        @Override public CompletionStage<Boolean> delivery(MobRewardIntent intent, MobRewardIntent.Delivery delivery) {
            mailCalls++;
            CompletableFuture<Boolean> future = new CompletableFuture<>(); pending.add(() -> future.complete(true)); return future;
        }
        void release() { List<Runnable> completions = List.copyOf(pending); pending.clear(); completions.forEach(Runnable::run); }
    }
}
