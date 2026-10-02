package cn.originalrpg.mobs;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.CombatRules;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.ProfileService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Bounded, per-player FIFO settlement for kills observed before the profile cache is ready.
 *
 * <p>Every method that touches queue state or a Bukkit object runs on the server thread. Database
 * callbacks only schedule an immutable result back onto that thread. Each mailbox retry reuses the
 * delivery ID frozen at death, so {@link MarketService#enqueueDelivery} is the durable idempotency
 * boundary.</p>
 */
public final class DelayedMobRewardQueue {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final MarketService market;
    private final ExperienceWriteBuffer experience;
    private final Feedback feedback;
    private final Map<UUID, ArrayDeque<PendingReward>> queues = new HashMap<>();
    private final Map<UUID, PlayerProfile> loadedProfiles = new HashMap<>();
    private final Map<UUID, Integer> profileAttempts = new HashMap<>();
    private final Map<UUID, Long> nextProfileAttemptAt = new HashMap<>();
    private final Set<UUID> profileLoads = new HashSet<>();
    private final Set<CompletableFuture<?>> activeMailboxWrites = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final int maximumPending;
    private final int maximumPerPlayer;
    private final int maximumProfileLoads;
    private final int maximumMailboxWrites;
    private final int maximumSettlementsPerTick;
    private final long retryMillis;
    private final long retryMaximumMillis;
    private final long shutdownTimeoutMillis;
    private BukkitTask pumpTask;
    private int pendingCount;
    private int mailboxWrites;
    private volatile boolean closed;

    public DelayedMobRewardQueue(
            Plugin plugin,
            ProfileService profiles,
            MarketService market,
            ExperienceWriteBuffer experience,
            Feedback feedback
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.market = Objects.requireNonNull(market, "market");
        this.experience = Objects.requireNonNull(experience, "experience");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        maximumPending = Math.clamp(plugin.getConfig().getInt(
                "delayed-rewards.maximum-pending", 512), 32, 4096);
        maximumPerPlayer = Math.clamp(plugin.getConfig().getInt(
                "delayed-rewards.maximum-per-player", 32), 4, 128);
        maximumProfileLoads = Math.clamp(plugin.getConfig().getInt(
                "delayed-rewards.maximum-profile-loads", 16), 1, 64);
        maximumMailboxWrites = Math.clamp(plugin.getConfig().getInt(
                "delayed-rewards.maximum-mailbox-writes", 32), 1, 128);
        maximumSettlementsPerTick = Math.clamp(plugin.getConfig().getInt(
                "delayed-rewards.maximum-settlements-per-tick", 16), 1, 64);
        retryMillis = Math.clamp(plugin.getConfig().getLong(
                "delayed-rewards.retry-ticks", 20L), 5L, 100L) * 50L;
        retryMaximumMillis = Math.max(retryMillis, Math.clamp(plugin.getConfig().getLong(
                "delayed-rewards.retry-maximum-ticks", 200L), 20L, 1200L) * 50L);
        shutdownTimeoutMillis = Math.clamp(plugin.getConfig().getLong(
                "delayed-rewards.shutdown-timeout-ms", 5000L), 1000L, 30_000L);
    }

    public void start() {
        requirePrimaryThread();
        pumpTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pump, 1L, 1L);
    }

    public boolean hasPending(UUID playerId) {
        requirePrimaryThread();
        ArrayDeque<PendingReward> queue = queues.get(playerId);
        return queue != null && !queue.isEmpty();
    }

    public boolean hasCapacity(UUID playerId) {
        requirePrimaryThread();
        ArrayDeque<PendingReward> queue = queues.get(playerId);
        return !closed && MobRewardReliabilityRules.hasCapacity(
                pendingCount, queue == null ? 0 : queue.size(), maximumPending, maximumPerPlayer);
    }

    public boolean offer(PendingReward reward) {
        requirePrimaryThread();
        Objects.requireNonNull(reward, "reward");
        if (!hasCapacity(reward.playerId())) return false;
        queues.computeIfAbsent(reward.playerId(), ignored -> new ArrayDeque<>()).addLast(reward);
        pendingCount++;
        return true;
    }

    public void close() {
        requirePrimaryThread();
        if (closed) return;
        closed = true;
        if (pumpTask != null) pumpTask.cancel();
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMillis);
        long inFlightDeadlineNanos = startedNanos
                + TimeUnit.MILLISECONDS.toNanos(Math.max(250L, shutdownTimeoutMillis / 5L));
        waitForInFlightMailboxWrites(inFlightDeadlineNanos);
        drainMailboxDeliveries(deadlineNanos);
        if (pendingCount > 0) {
            plugin.getLogger().severe("Stopping with " + pendingCount
                    + " delayed mob reward settlement(s) not yet durable");
        }
        queues.clear();
        loadedProfiles.clear();
        profileAttempts.clear();
        nextProfileAttemptAt.clear();
        profileLoads.clear();
        pendingCount = 0;
    }

    private void pump() {
        requirePrimaryThread();
        if (closed || queues.isEmpty()) return;
        long now = System.currentTimeMillis();
        startProfileLoads(now);
        int remaining = maximumSettlementsPerTick;
        for (UUID playerId : new ArrayList<>(queues.keySet())) {
            if (remaining <= 0) break;
            ArrayDeque<PendingReward> queue = queues.get(playerId);
            PlayerProfile profile = loadedProfiles.get(playerId);
            if (queue == null || queue.isEmpty() || profile == null) continue;
            PendingReward reward = queue.peekFirst();
            if (reward == null || reward.mailboxWriteInFlight() || now < reward.nextAttemptAtMillis()) continue;
            if (!reward.gated()) {
                if (!CombatRules.allowsCustomDrops(reward.mobLevel(), profile.effectiveLevel())) {
                    feedback.levelGap(reward);
                    removeHead(playerId, queue);
                    remaining--;
                    continue;
                }
                if (reward.experience() > 0L && !experience.addReserved(reward.session(), reward.experience())) {
                    plugin.getLogger().severe("Delayed mob experience reservation could not be transferred kill="
                            + reward.killId() + " player=" + reward.playerId());
                    reward.scheduleRetryAt(now + retryMillis);
                    continue;
                }
                reward.markGated();
            }
            FrozenDelivery delivery = reward.nextDelivery();
            if (delivery == null) {
                feedback.delivered(reward);
                removeHead(playerId, queue);
                remaining--;
                continue;
            }
            if (mailboxWrites >= maximumMailboxWrites) continue;
            submitDelivery(reward, delivery);
            remaining--;
        }
    }

    private void startProfileLoads(long now) {
        for (UUID playerId : new ArrayList<>(queues.keySet())) {
            if (profileLoads.size() >= maximumProfileLoads) return;
            if (loadedProfiles.containsKey(playerId) || profileLoads.contains(playerId)
                    || now < nextProfileAttemptAt.getOrDefault(playerId, 0L)) continue;
            int attempts = profileAttempts.getOrDefault(playerId, 0);
            ArrayDeque<PendingReward> queue = queues.get(playerId);
            PendingReward first = queue == null ? null : queue.peekFirst();
            if (first == null) continue;
            int nextAttempt = attempts == Integer.MAX_VALUE ? attempts : attempts + 1;
            profileAttempts.put(playerId, nextAttempt);
            profileLoads.add(playerId);
            CompletionStage<PlayerProfile> load;
            try {
                load = profiles.get(playerId);
            } catch (RuntimeException exception) {
                profileLoads.remove(playerId);
                long delay = MobRewardReliabilityRules.retryDelayMillis(
                        nextAttempt, retryMillis, retryMaximumMillis);
                nextProfileAttemptAt.put(playerId, now + delay);
                plugin.getLogger().warning("Delayed mob reward profile load could not start; retry #"
                        + nextAttempt + " in " + delay + "ms player=" + playerId
                        + " cause=" + exception.getMessage());
                continue;
            }
            load.whenComplete((profile, error) -> runSync(() -> {
                profileLoads.remove(playerId);
                if (closed || !queues.containsKey(playerId)) return;
                if (error != null || profile == null || !playerId.equals(profile.playerId())) {
                    long delay = MobRewardReliabilityRules.retryDelayMillis(
                            nextAttempt, retryMillis, retryMaximumMillis);
                    nextProfileAttemptAt.put(playerId, System.currentTimeMillis() + delay);
                    if (error != null) plugin.getLogger().warning(
                            "Delayed mob reward profile load failed; retry #" + nextAttempt
                                    + " in " + delay + "ms player=" + playerId
                                    + " cause=" + error.getMessage());
                    return;
                }
                profileAttempts.remove(playerId);
                nextProfileAttemptAt.remove(playerId);
                loadedProfiles.put(playerId, profile);
            }));
        }
    }

    private void submitDelivery(PendingReward reward, FrozenDelivery delivery) {
        reward.markMailboxWriteInFlight(true);
        mailboxWrites++;
        CompletableFuture<Boolean> write;
        try {
            write = market.enqueueDelivery(delivery.deliveryId(), reward.playerId(), delivery.itemPayload(),
                    "mob-kill:" + reward.killId() + ':' + delivery.kind()).toCompletableFuture();
        } catch (RuntimeException exception) {
            mailboxWrites--;
            reward.markMailboxWriteInFlight(false);
            handleDeliveryResult(reward, delivery, false, exception);
            return;
        }
        activeMailboxWrites.add(write);
        write.whenComplete((accepted, error) -> runSync(() -> {
            activeMailboxWrites.remove(write);
            mailboxWrites = Math.max(0, mailboxWrites - 1);
            reward.markMailboxWriteInFlight(false);
            handleDeliveryResult(reward, delivery, Boolean.TRUE.equals(accepted), error);
        }));
    }

    private void handleDeliveryResult(
            PendingReward reward, FrozenDelivery delivery, boolean accepted, Throwable error
    ) {
        if (closed || !isHead(reward)) return;
        if (error == null && accepted) {
            reward.advanceDelivery();
            reward.resetDeliveryAttempts();
            reward.scheduleRetryAt(0L);
            return;
        }
        int attempts = reward.incrementDeliveryAttempts();
        long now = System.currentTimeMillis();
        long delay = MobRewardReliabilityRules.retryDelayMillis(
                attempts, retryMillis, retryMaximumMillis);
        reward.scheduleRetryAt(now + delay);
        plugin.getLogger().warning(
                "Delayed mob reward mailbox write failed; idempotent retry #" + attempts
                        + " in " + delay + "ms delivery="
                        + delivery.deliveryId()
                        + (error == null ? "" : " cause=" + error.getMessage()));
    }

    private boolean isHead(PendingReward reward) {
        ArrayDeque<PendingReward> queue = queues.get(reward.playerId());
        return queue != null && queue.peekFirst() == reward;
    }

    private void removeHead(UUID playerId, ArrayDeque<PendingReward> queue) {
        if (queue.pollFirst() != null) pendingCount--;
        if (!queue.isEmpty()) return;
        queues.remove(playerId);
        loadedProfiles.remove(playerId);
        profileAttempts.remove(playerId);
        nextProfileAttemptAt.remove(playerId);
    }

    private void waitForInFlightMailboxWrites(long deadlineNanos) {
        if (!activeMailboxWrites.isEmpty()) {
            CompletableFuture<?>[] waits = activeMailboxWrites.stream()
                    .map(write -> write.handle((result, error) -> null))
                    .toArray(CompletableFuture[]::new);
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining > 0L) {
                try {
                    CompletableFuture.allOf(waits).get(remaining, TimeUnit.NANOSECONDS);
                } catch (Exception exception) {
                    plugin.getLogger().warning("Timed out waiting for delayed mailbox writes: "
                            + exception.getMessage());
                }
            }
        }
        // Every in-flight item keeps its current delivery index. Replaying the same stable ID is
        // safe whether the original transaction committed, failed, or its callback was discarded.
        for (ArrayDeque<PendingReward> queue : queues.values()) {
            for (PendingReward reward : queue) reward.markMailboxWriteInFlight(false);
        }
        mailboxWrites = 0;
        activeMailboxWrites.clear();
    }

    private void drainMailboxDeliveries(long deadlineNanos) {
        for (UUID playerId : new ArrayList<>(queues.keySet())) {
            ArrayDeque<PendingReward> queue = queues.get(playerId);
            if (queue == null) continue;
            while (!queue.isEmpty() && System.nanoTime() < deadlineNanos) {
                PendingReward reward = queue.peekFirst();
                if (reward == null) break;
                if (!reward.gated()) {
                    PlayerProfile profile = shutdownProfile(reward.playerId(), deadlineNanos);
                    if (profile == null) break;
                    if (!CombatRules.allowsCustomDrops(reward.mobLevel(), profile.effectiveLevel())) {
                        removeHead(playerId, queue);
                        continue;
                    }
                    if (reward.experience() > 0L
                            && !experience.addReserved(reward.session(), reward.experience())) {
                        plugin.getLogger().severe("Delayed mob experience could not enter shutdown drain kill="
                                + reward.killId() + " player=" + reward.playerId());
                        break;
                    }
                    reward.markGated();
                }
                FrozenDelivery delivery = reward.nextDelivery();
                if (delivery == null) {
                    // XP has no idempotency key and is drained by ExperienceWriteBuffer. The item
                    // mailbox is the only operation replayed synchronously here.
                    removeHead(playerId, queue);
                    continue;
                }
                CompletableFuture<Boolean> write;
                try {
                    write = market.enqueueDelivery(delivery.deliveryId(), reward.playerId(), delivery.itemPayload(),
                            "mob-kill:" + reward.killId() + ':' + delivery.kind()).toCompletableFuture();
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("Delayed mailbox shutdown write could not start delivery="
                            + delivery.deliveryId() + " cause=" + exception.getMessage());
                    break;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) break;
                try {
                    if (!Boolean.TRUE.equals(write.get(remaining, TimeUnit.NANOSECONDS))) {
                        plugin.getLogger().severe("Delayed mailbox shutdown write was rejected delivery="
                                + delivery.deliveryId());
                        break;
                    }
                    reward.advanceDelivery();
                } catch (Exception exception) {
                    plugin.getLogger().severe("Delayed mailbox shutdown write failed delivery="
                            + delivery.deliveryId() + " cause=" + exception.getMessage());
                    break;
                }
            }
        }
    }

    private PlayerProfile shutdownProfile(UUID playerId, long deadlineNanos) {
        PlayerProfile profile = loadedProfiles.get(playerId);
        if (profile != null) return profile;
        profile = profiles.cached(playerId).orElse(null);
        if (profile != null) return profile;
        CompletableFuture<PlayerProfile> load;
        try {
            load = profiles.get(playerId).toCompletableFuture();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Delayed reward shutdown profile load could not start player="
                    + playerId + " cause=" + exception.getMessage());
            return null;
        }
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) return null;
        try {
            PlayerProfile loaded = load.get(remaining, TimeUnit.NANOSECONDS);
            return loaded != null && playerId.equals(loaded.playerId()) ? loaded : null;
        } catch (Exception exception) {
            plugin.getLogger().severe("Delayed reward shutdown profile load failed player="
                    + playerId + " cause=" + exception.getMessage());
            return null;
        }
    }

    private void runSync(Runnable action) {
        if (closed || !plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!closed && plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // Disable won the race after the async callback observed the queue. The normal close
            // drain replays mailbox writes by their stable IDs and ExperienceWriteBuffer owns XP.
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Delayed mob rewards must be managed on the server thread");
        }
    }

    public interface Feedback {
        void levelGap(PendingReward reward);

        void delivered(PendingReward reward);
    }

    public record FrozenDelivery(
            UUID deliveryId,
            String kind,
            String itemPayload,
            MobVisuals.DropKind visualKind,
            String itemMessageKey,
            boolean pityGuaranteed
    ) {
        public FrozenDelivery {
            Objects.requireNonNull(deliveryId, "deliveryId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(itemPayload, "itemPayload");
            Objects.requireNonNull(visualKind, "visualKind");
            Objects.requireNonNull(itemMessageKey, "itemMessageKey");
            if (kind.isBlank() || itemPayload.isBlank() || itemMessageKey.isBlank()) {
                throw new IllegalArgumentException("Frozen delivery fields must not be blank");
            }
        }
    }

    public static final class PendingReward {
        private final UUID killId;
        private final UUID playerId;
        private final Player session;
        private final int mobLevel;
        private final long experience;
        private final long createdAtMillis;
        private final List<FrozenDelivery> deliveries;
        private int deliveryIndex;
        private int deliveryAttempts;
        private long nextAttemptAtMillis;
        private boolean gated;
        private boolean mailboxWriteInFlight;

        public PendingReward(
                UUID killId, Player session, int mobLevel, long experience,
                long createdAtMillis, List<FrozenDelivery> deliveries
        ) {
            this.killId = Objects.requireNonNull(killId, "killId");
            this.session = Objects.requireNonNull(session, "session");
            playerId = session.getUniqueId();
            if (mobLevel < 1 || experience < 0L || createdAtMillis < 0L) {
                throw new IllegalArgumentException("Invalid delayed mob reward snapshot");
            }
            this.mobLevel = mobLevel;
            this.experience = experience;
            this.createdAtMillis = createdAtMillis;
            this.deliveries = List.copyOf(deliveries);
        }

        public UUID killId() {
            return killId;
        }

        public UUID playerId() {
            return playerId;
        }

        Player session() {
            return session;
        }

        public int mobLevel() {
            return mobLevel;
        }

        public long experience() {
            return experience;
        }

        public long createdAtMillis() {
            return createdAtMillis;
        }

        public List<FrozenDelivery> deliveries() {
            return deliveries;
        }

        public Player activeSession() {
            Player current = Bukkit.getPlayer(playerId);
            return current != null && current == session && current.isOnline() ? current : null;
        }

        private FrozenDelivery nextDelivery() {
            return deliveryIndex >= deliveries.size() ? null : deliveries.get(deliveryIndex);
        }

        private void advanceDelivery() {
            deliveryIndex++;
        }

        private int incrementDeliveryAttempts() {
            if (deliveryAttempts < Integer.MAX_VALUE) deliveryAttempts++;
            return deliveryAttempts;
        }

        private void resetDeliveryAttempts() {
            deliveryAttempts = 0;
        }

        private void scheduleRetryAt(long millis) {
            nextAttemptAtMillis = millis;
        }

        private long nextAttemptAtMillis() {
            return nextAttemptAtMillis;
        }

        private boolean gated() {
            return gated;
        }

        private void markGated() {
            gated = true;
        }

        private boolean mailboxWriteInFlight() {
            return mailboxWriteInFlight;
        }

        private void markMailboxWriteInFlight(boolean value) {
            mailboxWriteInFlight = value;
        }
    }
}
