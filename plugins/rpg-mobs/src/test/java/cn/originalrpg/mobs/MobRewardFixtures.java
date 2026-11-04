package cn.originalrpg.mobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Test values are opaque mailbox payloads; Paper item deserialization belongs to its runtime tests. */
final class MobRewardFixtures {
    static final long BYTE_LIMIT = 2L * 1024 * 1024;

    static MobRewardIntent reward(UUID player, long experience, int deliveries) {
        return reward(UUID.randomUUID(), player, experience, deliveries);
    }

    static MobRewardIntent reward(UUID kill, UUID player, long experience, int count) {
        List<MobRewardIntent.Delivery> deliveries = new ArrayList<>();
        if (count > 0) deliveries.add(delivery(kill, "material", "original material bytes", "CORE"));
        if (count > 1) deliveries.add(delivery(kill, "skill-book", "original unique skill book bytes", "SKILL_BOOK"));
        List<MobRewardIntent.PityRoll> rolls = count == 0
                ? List.of(new MobRewardIntent.PityRoll("normal_material_failures", 0, 1, 40, 0.025, 0.9, false, false))
                : count == 1 ? List.of(new MobRewardIntent.PityRoll("elite_material_failures", 3, 0, 4, 0.35, 0.9, true, true))
                : List.of(new MobRewardIntent.PityRoll("elite_material_failures", 3, 0, 4, 0.35, 0.9, true, true),
                        new MobRewardIntent.PityRoll("skill_book_failures", 99, 0, 100, 0.01, 0.9, true, true));
        return new MobRewardIntent(kill, player, 1_000L, 10, experience,
                MobRewardIntent.Eligibility.ELIGIBLE, 10, deliveries, rolls);
    }

    static MobRewardIntent unknown(MobRewardIntent known) {
        return new MobRewardIntent(known.killId(), known.playerId(), known.createdAtMillis(), known.mobLevel(),
                known.experience(), MobRewardIntent.Eligibility.UNKNOWN, 0, known.deliveries(), known.pityRolls());
    }

    static MobRewardIntent withPayload(MobRewardIntent original, int decodedBytes) {
        String payload = Base64.getEncoder().encodeToString(new byte[decodedBytes]);
        return new MobRewardIntent(original.killId(), original.playerId(), original.createdAtMillis(), original.mobLevel(),
                original.experience(), original.eligibility(), original.playerEffectiveLevel(), original.deliveries().stream()
                .map(delivery -> new MobRewardIntent.Delivery(delivery.deliveryId(), delivery.kind(), payload,
                        delivery.visualKind(), delivery.itemMessageKey(), delivery.pityGuaranteed())).toList(), original.pityRolls());
    }

    private static MobRewardIntent.Delivery delivery(UUID kill, String kind, String bytes, String visual) {
        return new MobRewardIntent.Delivery(MobRewardReliabilityRules.deliveryId(kill, kind), kind,
                Base64.getEncoder().encodeToString(bytes.getBytes(StandardCharsets.UTF_8)), visual,
                kind.equals("material") ? "mobs.items.mutation-core" : "mobs.items.common-insight-book", true);
    }

    static MobRewardSettlement.Limits limits() {
        return new MobRewardSettlement.Limits(64, 16, BYTE_LIMIT, 4, 3, 4, 5, 32, 1, 8);
    }

    static MobRewardSettlement engine(MobRewardSettlement.Store store, MobRewardSettlement.Authority authority,
                                      AtomicLong clock) {
        return new MobRewardSettlement(store, authority, new MobRewardSettlement.Observer() { }, limits(), clock::get);
    }

    static void until(MobRewardSettlement engine, AtomicLong clock, BooleanSupplier done) {
        for (int tick = 0; tick < 500; tick++) {
            engine.tick();
            clock.addAndGet(100L);
            if (done.getAsBoolean()) return;
        }
        throw new AssertionError("Reward protocol did not reach expected state: " + engine.status());
    }

    static void ready(MobRewardSettlement engine, AtomicLong clock) {
        until(engine, clock, () -> !engine.status().recovering());
    }

    static final class DirectStore implements MobRewardSettlement.Store, AutoCloseable {
        final MobRewardJournal journal;
        boolean failRemoval;
        DirectStore(Path directory) { journal = new MobRewardJournal(directory, 64, BYTE_LIMIT); }
        @Override public CompletionStage<List<MobRewardIntent>> load() { return call(journal::load); }
        @Override public CompletionStage<MobRewardIntent> persist(MobRewardIntent intent) { return call(() -> journal.persist(intent)); }
        @Override public CompletionStage<Void> remove(MobRewardIntent intent) {
            return call(() -> {
                if (failRemoval) throw new IOException("injected cleanup failure before delete");
                journal.remove(intent); return null;
            });
        }
        @Override public void close() throws IOException { journal.close(); }
    }

    static class RecordingAuthority implements MobRewardSettlement.Authority {
        final Map<UUID, Long> xpReceipts = new HashMap<>();
        final Map<UUID, MobRewardIntent.Delivery> mailbox = new HashMap<>();
        final List<UUID> xpAttempts = new ArrayList<>();
        final List<UUID> mailboxAttempts = new ArrayList<>();
        final List<MobRewardIntent> observed = new ArrayList<>();
        int level = 10;
        int profileLoads;
        int failProfiles;
        boolean loseXpResponse;
        int loseDeliveryResponseAt;

        @Override public CompletionStage<Integer> effectiveLevel(UUID player) {
            profileLoads++;
            if (failProfiles-- > 0) return CompletableFuture.failedFuture(new IOException("profile DB unavailable"));
            return CompletableFuture.completedFuture(level);
        }
        @Override public CompletionStage<Boolean> experience(MobRewardIntent intent) {
            xpAttempts.add(intent.experienceGrantId());
            observed.add(intent);
            Long old = xpReceipts.putIfAbsent(intent.experienceGrantId(), intent.experience());
            if (old != null && old != intent.experience()) throw new AssertionError("XP receipt changed on replay");
            if (loseXpResponse) {
                loseXpResponse = false;
                return CompletableFuture.failedFuture(new IOException("XP COMMITTED; response lost"));
            }
            return CompletableFuture.completedFuture(true);
        }
        @Override public CompletionStage<Boolean> delivery(MobRewardIntent intent, MobRewardIntent.Delivery delivery) {
            mailboxAttempts.add(delivery.deliveryId());
            observed.add(intent);
            MobRewardIntent.Delivery old = mailbox.putIfAbsent(delivery.deliveryId(), delivery);
            if (old != null && !old.equals(delivery)) throw new AssertionError("Mailbox bytes changed on replay");
            if (mailboxAttempts.size() == loseDeliveryResponseAt) {
                return CompletableFuture.failedFuture(new IOException("Mail COMMITTED; response lost"));
            }
            return CompletableFuture.completedFuture(true);
        }
        long grantedExperience() { return xpReceipts.values().stream().mapToLong(Long::longValue).sum(); }
    }

    private static <T> CompletionStage<T> call(IoSupplier<T> operation) {
        try { return CompletableFuture.completedFuture(operation.get()); }
        catch (IOException error) { return CompletableFuture.failedFuture(error); }
    }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
}
