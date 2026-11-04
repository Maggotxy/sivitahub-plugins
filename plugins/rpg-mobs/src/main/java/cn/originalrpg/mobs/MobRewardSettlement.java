package cn.originalrpg.mobs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Thread-confined, bounded replay protocol for immutable mob reward instructions.
 *
 * <p>The owner calls {@link #tick()} on the server thread. Completion callbacks
 * only enqueue values; no callback changes an entry or accesses Bukkit state.
 * Every authority call is preceded by a successful journal acknowledgement.
 * Partial success is harmless: a restart repeats the same core XP receipt and
 * mailbox IDs, and removes an intent only after all authorities confirm it.</p>
 */
final class MobRewardSettlement {
    interface Store {
        CompletionStage<List<MobRewardIntent>> load();
        CompletionStage<MobRewardIntent> persist(MobRewardIntent intent);
        CompletionStage<Void> remove(MobRewardIntent intent);
    }

    interface Authority {
        CompletionStage<Integer> effectiveLevel(UUID playerId);
        CompletionStage<Boolean> experience(MobRewardIntent intent);
        CompletionStage<Boolean> delivery(MobRewardIntent intent, MobRewardIntent.Delivery delivery);
    }

    interface Observer {
        default void recovered(int count) { }
        default void durable(MobRewardIntent intent) { }
        default void ineligible(MobRewardIntent intent) { }
        default void settled(MobRewardIntent intent) { }
        default void removed(MobRewardIntent intent) { }
        default void failure(MobRewardIntent intent, String operation, Throwable error, int attempts) { }
    }

    record Limits(int maximumPending, int maximumPerPlayer, long maximumPayloadBytes,
                  int maximumIo, int maximumProfiles, int maximumExperience, int maximumMailbox,
                  int maximumStepsPerTick, long retryBaseMillis, long retryMaximumMillis) {
        Limits {
            if (maximumPending < 1 || maximumPerPlayer < 1 || maximumPayloadBytes < 2048
                    || maximumIo < 1 || maximumProfiles < 1 || maximumExperience < 1 || maximumMailbox < 1
                    || maximumStepsPerTick < 1 || retryBaseMillis < 1 || retryMaximumMillis < retryBaseMillis) {
                throw new IllegalArgumentException("Invalid mob reward recovery limits");
            }
        }
    }

    enum Admission { QUEUED_NOT_DURABLE, ALREADY_PENDING, RECOVERING, PAUSED, CAPACITY, CLOSED, CONFLICT }
    record Status(boolean recovering, boolean paused, boolean closed, int pending, int durable,
                  long payloadBytes, int ioInFlight, int profilesInFlight, int experienceInFlight, int mailboxInFlight) { }
    private enum Phase { PERSIST, PROFILE, DECISION, EXPERIENCE, DELIVERY, REMOVE }

    private final Store store;
    private final Authority authority;
    private final Observer observer;
    private final Limits limits;
    private final LongSupplier clock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, ArrayDeque<UUID>> playerQueues = new HashMap<>();
    private final Map<UUID, Integer> firstLoadedLevels = new HashMap<>();
    private final Set<UUID> ioFailures = new HashSet<>();
    private final ArrayDeque<UUID> ready = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private boolean loaded;
    private boolean loading;
    private volatile boolean closed;
    private int loadAttempts;
    private long loadRetryAt;
    private long payloadBytes;
    private int ioInFlight;
    private int profilesInFlight;
    private int experienceInFlight;
    private int mailboxInFlight;

    MobRewardSettlement(Store store, Authority authority, Observer observer, Limits limits, LongSupplier clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean hasCapacity(UUID playerId) {
        ArrayDeque<UUID> player = playerQueues.get(playerId);
        return playerId != null && loaded && !closed && ioFailures.isEmpty()
                && entries.size() < limits.maximumPending()
                && (player == null || player.size() < limits.maximumPerPlayer())
                && payloadBytes + 2048 <= limits.maximumPayloadBytes();
    }

    /** Reserves bounded volatile space only. QUEUED_NOT_DURABLE is deliberately not an acknowledgement. */
    Admission offer(MobRewardIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (closed) return Admission.CLOSED;
        if (!loaded) return Admission.RECOVERING;
        Entry existing = entries.get(intent.killId());
        if (existing != null) return existing.intent.sameObservation(intent) ? Admission.ALREADY_PENDING : Admission.CONFLICT;
        if (!ioFailures.isEmpty()) return Admission.PAUSED;
        if (!hasCapacity(intent.playerId()) || intent.budgetBytes() > limits.maximumPayloadBytes() - payloadBytes) {
            return Admission.CAPACITY;
        }
        add(intent, false);
        return Admission.QUEUED_NOT_DURABLE;
    }

    void tick() {
        if (closed) return;
        drainCompletions();
        if (!loaded) {
            if (!loading && clock.getAsLong() >= loadRetryAt) recover();
            return;
        }
        int visits = ready.size();
        int started = 0;
        long now = clock.getAsLong();
        while (visits-- > 0 && started < limits.maximumStepsPerTick()) {
            UUID id = ready.pollFirst();
            Entry entry = entries.get(id);
            if (entry == null) continue;
            ready.addLast(id);
            if (entry.busy || now < entry.retryAt) continue;
            // Journal all observations promptly, but settle each player's rewards
            // in FIFO order so a slow mailbox cannot reorder an earlier kill.
            if (entry.phase != Phase.PERSIST && !id.equals(playerQueues.get(entry.intent.playerId()).peekFirst())) continue;
            if (advance(entry)) started++;
        }
    }

    private void recover() {
        loading = true;
        ioInFlight++;
        start(store::load, (records, error) -> {
            loading = false;
            ioInFlight--;
            if (error == null) {
                try {
                    Objects.requireNonNull(records, "Recovered records");
                    if (records.size() > limits.maximumPending()) throw new IllegalStateException("Recovered reward count exceeds configured admission budget");
                    long bytes = 0;
                    Set<UUID> ids = new HashSet<>();
                    for (MobRewardIntent intent : records) {
                        if (!ids.add(intent.killId())) throw new IllegalStateException("Duplicate recovered kill ID");
                        bytes = Math.addExact(bytes, intent.budgetBytes());
                    }
                    if (bytes > limits.maximumPayloadBytes()) throw new IllegalStateException("Recovered reward payloads exceed configured budget");
                    for (MobRewardIntent intent : records) add(intent, true);
                    loaded = true;
                    observe(() -> observer.recovered(records.size()));
                    return;
                } catch (RuntimeException invalid) { error = invalid; }
            }
            loadAttempts = increment(loadAttempts);
            loadRetryAt = deadline(clock.getAsLong(), delay(loadAttempts));
            Throwable failure = error;
            observe(() -> observer.failure(null, "recover", failure, loadAttempts));
        });
    }

    private void add(MobRewardIntent intent, boolean durable) {
        Entry entry = new Entry(intent, durable);
        entries.put(intent.killId(), entry);
        ready.addLast(intent.killId());
        playerQueues.computeIfAbsent(intent.playerId(), ignored -> new ArrayDeque<>()).addLast(intent.killId());
        payloadBytes += intent.budgetBytes();
    }

    private boolean advance(Entry entry) {
        return switch (entry.phase) {
            case PERSIST, DECISION -> persist(entry);
            case PROFILE -> profile(entry);
            case EXPERIENCE -> experience(entry);
            case DELIVERY -> delivery(entry);
            case REMOVE -> remove(entry);
        };
    }

    private boolean persist(Entry entry) {
        if (ioInFlight >= limits.maximumIo()) return false;
        Phase phase = entry.phase;
        entry.busy = true; ioInFlight++;
        start(() -> store.persist(entry.intent), (persisted, error) -> {
            ioInFlight--; entry.busy = false;
            if (error == null && (persisted == null || !persisted.sameObservation(entry.intent)
                    || (entry.intent.eligibility() != MobRewardIntent.Eligibility.UNKNOWN && !entry.intent.equals(persisted)))) {
                error = new IllegalStateException("Journal acknowledged different reward content");
            }
            if (error != null) { fail(entry, phase, error, true); return; }
            entry.intent = persisted;
            entry.durable = true;
            succeeded(entry, true);
            entry.phase = nextAfterPersistence(persisted);
            if (phase == Phase.PERSIST) observe(() -> observer.durable(persisted));
        });
        return true;
    }

    private boolean profile(Entry entry) {
        Integer first = firstLoadedLevels.get(entry.intent.playerId());
        if (first != null) {
            entry.intent = entry.intent.resolve(first);
            entry.phase = Phase.DECISION;
            return true;
        }
        if (profilesInFlight >= limits.maximumProfiles()) return false;
        entry.busy = true; profilesInFlight++;
        start(() -> authority.effectiveLevel(entry.intent.playerId()), (level, error) -> {
            profilesInFlight--; entry.busy = false;
            if (error == null && (level == null || level < 1)) error = new IllegalStateException("No authoritative eligibility snapshot");
            if (error != null) { fail(entry, Phase.PROFILE, error, false); return; }
            firstLoadedLevels.putIfAbsent(entry.intent.playerId(), level);
            entry.intent = entry.intent.resolve(firstLoadedLevels.get(entry.intent.playerId()));
            entry.phase = Phase.DECISION;
            succeeded(entry, false);
        });
        return true;
    }

    private boolean experience(Entry entry) {
        if (entry.intent.experience() == 0) { entry.phase = Phase.DELIVERY; return true; }
        if (experienceInFlight >= limits.maximumExperience()) return false;
        entry.busy = true; experienceInFlight++;
        start(() -> authority.experience(entry.intent), (confirmed, error) -> {
            experienceInFlight--; entry.busy = false;
            if (error == null && !Boolean.TRUE.equals(confirmed)) error = new IllegalStateException("XP authority did not confirm the receipt");
            if (error != null) { fail(entry, Phase.EXPERIENCE, error, false); return; }
            succeeded(entry, false);
            entry.phase = Phase.DELIVERY;
        });
        return true;
    }

    private boolean delivery(Entry entry) {
        if (entry.deliveryIndex >= entry.intent.deliveries().size()) {
            entry.phase = Phase.REMOVE;
            notifySettlement(entry);
            return true;
        }
        if (mailboxInFlight >= limits.maximumMailbox()) return false;
        MobRewardIntent.Delivery delivery = entry.intent.deliveries().get(entry.deliveryIndex);
        entry.busy = true; mailboxInFlight++;
        start(() -> authority.delivery(entry.intent, delivery), (confirmed, error) -> {
            mailboxInFlight--; entry.busy = false;
            if (error == null && !Boolean.TRUE.equals(confirmed)) error = new IllegalStateException("Mailbox authority rejected the stable delivery content");
            if (error != null) { fail(entry, Phase.DELIVERY, error, false); return; }
            entry.deliveryIndex++;
            succeeded(entry, false);
        });
        return true;
    }

    private boolean remove(Entry entry) {
        notifySettlement(entry);
        if (ioInFlight >= limits.maximumIo()) return false;
        entry.busy = true; ioInFlight++;
        start(() -> store.remove(entry.intent), (ignored, error) -> {
            ioInFlight--; entry.busy = false;
            if (error != null) { fail(entry, Phase.REMOVE, error, true); return; }
            succeeded(entry, true);
            entries.remove(entry.intent.killId());
            ready.remove(entry.intent.killId());
            payloadBytes -= entry.intent.budgetBytes();
            ArrayDeque<UUID> queue = playerQueues.get(entry.intent.playerId());
            queue.removeFirstOccurrence(entry.intent.killId());
            if (queue.isEmpty()) {
                playerQueues.remove(entry.intent.playerId());
                firstLoadedLevels.remove(entry.intent.playerId());
            }
            observe(() -> observer.removed(entry.intent));
        });
        return true;
    }

    private void notifySettlement(Entry entry) {
        if (entry.notified) return;
        entry.notified = true;
        if (entry.intent.eligibility() == MobRewardIntent.Eligibility.INELIGIBLE) observe(() -> observer.ineligible(entry.intent));
        else observe(() -> observer.settled(entry.intent));
    }

    private void fail(Entry entry, Phase phase, Throwable error, boolean io) {
        entry.phase = phase;
        entry.attempts = increment(entry.attempts);
        entry.retryAt = deadline(clock.getAsLong(), delay(entry.attempts));
        if (io) ioFailures.add(entry.intent.killId());
        observe(() -> observer.failure(entry.intent, phase.name().toLowerCase(java.util.Locale.ROOT), error, entry.attempts));
    }

    private void succeeded(Entry entry, boolean io) {
        entry.attempts = 0; entry.retryAt = 0;
        if (io) ioFailures.remove(entry.intent.killId());
    }

    private <T> void start(Supplier<CompletionStage<T>> action, Completion<T> completion) {
        try {
            Objects.requireNonNull(action.get(), "Authority future").whenComplete((value, error) -> {
                if (!closed) completions.add(() -> completion.accept(value, error));
            });
        } catch (Throwable error) {
            completions.add(() -> completion.accept(null, error));
        }
    }

    private void drainCompletions() {
        int budget = limits.maximumStepsPerTick() * 4 + 1;
        Runnable completion;
        while (budget-- > 0 && (completion = completions.poll()) != null) completion.run();
    }

    private static void observe(Runnable notification) {
        try { notification.run(); }
        catch (RuntimeException error) {
            System.getLogger(MobRewardSettlement.class.getName()).log(System.Logger.Level.ERROR,
                    "Mob reward observer failed; the durable settlement protocol continues", error);
        }
    }

    Status status() {
        return new Status(!loaded, !ioFailures.isEmpty(), closed, entries.size(),
                (int) entries.values().stream().filter(entry -> entry.durable).count(), payloadBytes,
                ioInFlight, profilesInFlight, experienceInFlight, mailboxInFlight);
    }

    /**
     * Revokes callbacks and returns only observations without a durability acknowledgement.
     * The I/O owner may make one final bounded persistence pass. Durable intents
     * remain on disk; shutdown does not invent a new grant or gamble on DB timeouts.
     */
    List<MobRewardIntent> close() {
        if (closed) return List.of();
        drainCompletions();
        closed = true;
        List<MobRewardIntent> unconfirmed = entries.values().stream().filter(entry -> !entry.durable)
                .map(entry -> entry.intent).toList();
        entries.clear(); playerQueues.clear(); firstLoadedLevels.clear(); ready.clear(); ioFailures.clear(); completions.clear();
        payloadBytes = 0;
        return unconfirmed;
    }

    private long delay(int attempts) { return MobRewardReliabilityRules.retryDelayMillis(attempts, limits.retryBaseMillis(), limits.retryMaximumMillis()); }
    private static int increment(int value) { return value == Integer.MAX_VALUE ? value : value + 1; }
    private static long deadline(long now, long delay) { return now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay; }

    private static Phase nextAfterPersistence(MobRewardIntent intent) {
        return switch (intent.eligibility()) {
            case UNKNOWN -> Phase.PROFILE;
            case ELIGIBLE -> Phase.EXPERIENCE;
            case INELIGIBLE -> Phase.REMOVE;
        };
    }

    private static final class Entry {
        private MobRewardIntent intent;
        private Phase phase;
        private boolean durable;
        private boolean busy;
        private boolean notified;
        private int deliveryIndex;
        private int attempts;
        private long retryAt;
        Entry(MobRewardIntent intent, boolean durable) {
            this.intent = intent;
            this.durable = durable;
            phase = durable ? nextAfterPersistence(intent) : Phase.PERSIST;
        }
    }

    @FunctionalInterface private interface Completion<T> { void accept(T value, Throwable error); }
}
