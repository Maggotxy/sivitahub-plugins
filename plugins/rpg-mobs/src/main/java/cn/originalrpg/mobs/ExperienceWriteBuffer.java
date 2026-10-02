package cn.originalrpg.mobs;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/** Coalesces high-frequency mob XP into one locked profile mutation per player and flush window. */
public final class ExperienceWriteBuffer {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final MessageService messages;
    private final Map<UUID, PendingExperience> pending = new HashMap<>();
    private final Map<UUID, InFlightWrite> inFlight = new HashMap<>();
    private final Map<UUID, FeedbackBatch> pendingFeedback = new HashMap<>();
    private final Map<UUID, Integer> failedAttempts = new HashMap<>();
    private final Map<UUID, Long> retryAtMillis = new HashMap<>();
    private final ArrayDeque<UUID> ready = new ArrayDeque<>();
    private final Set<UUID> queued = new HashSet<>();
    private final Set<UUID> trackedPlayers = new HashSet<>();
    private final ConcurrentLinkedQueue<CompletedWrite> completedWrites = new ConcurrentLinkedQueue<>();
    private final int maxFlushesPerTick;
    private final int maximumPendingPlayers;
    private final int shutdownAttempts;
    private final long retryBaseMillis;
    private final long retryMaximumMillis;
    private final long shutdownTimeoutMillis;
    private BukkitTask flushTask;
    private BukkitTask feedbackTask;
    private boolean shuttingDown;

    ExperienceWriteBuffer(Plugin plugin, ProfileService profiles, MessageService messages) {
        this.plugin = plugin;
        this.profiles = profiles;
        this.messages = messages;
        maxFlushesPerTick = Math.clamp(
                plugin.getConfig().getInt("experience-write-buffer.max-player-flushes-per-tick", 8), 1, 32);
        maximumPendingPlayers = Math.clamp(
                plugin.getConfig().getInt("experience-write-buffer.maximum-pending-players", 1024), 16, 4096);
        shutdownAttempts = Math.clamp(
                plugin.getConfig().getInt("experience-write-buffer.shutdown-attempts", 3), 1, 8);
        retryBaseMillis = Math.clamp(
                plugin.getConfig().getLong("experience-write-buffer.retry-base-ticks", 20L), 1L, 200L) * 50L;
        retryMaximumMillis = Math.max(retryBaseMillis, Math.clamp(
                plugin.getConfig().getLong("experience-write-buffer.retry-maximum-ticks", 200L),
                20L, 1200L) * 50L);
        shutdownTimeoutMillis = Math.clamp(
                plugin.getConfig().getLong("experience-write-buffer.shutdown-timeout-ms", 5000L), 1000L, 30_000L);
    }

    void start() {
        long ticks = Math.clamp(plugin.getConfig().getLong("experience-write-buffer.flush-ticks", 10L), 1L, 100L);
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushReady, ticks, ticks);
        long feedbackTicks = Math.clamp(
                plugin.getConfig().getLong("experience-write-buffer.feedback-ticks", 60L), 40L, 200L);
        feedbackTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::flushPlayerFeedback, feedbackTicks, feedbackTicks);
    }

    public boolean hasCapacity(UUID playerId) {
        return !shuttingDown && playerId != null
                && (trackedPlayers.contains(playerId) || trackedPlayers.size() < maximumPendingPlayers);
    }

    /**
     * Queues XP and remembers the exact login session that earned it. Returning {@code false}
     * applies fail-closed backpressure; callers must not silently promise an untracked reward.
     */
    public boolean add(Player session, long amount) {
        if (session == null) return false;
        return add(session.getUniqueId(), session, amount);
    }

    /**
     * Accepts XP for a kill that already owns capacity in DelayedMobRewardQueue. The composite
     * system remains bounded by that queue, while avoiding a circular backpressure deadlock.
     */
    boolean addReserved(Player session, long amount) {
        if (session == null || amount <= 0L || shuttingDown) return false;
        UUID playerId = session.getUniqueId();
        trackedPlayers.add(playerId);
        pending.merge(playerId, new PendingExperience(amount, session), PendingExperience::merge);
        markReady(playerId);
        return true;
    }

    /** Compatibility entry point for callers that cannot retain a Paper session token. */
    public boolean add(UUID playerId, long amount) {
        return add(playerId, playerId == null ? null : Bukkit.getPlayer(playerId), amount);
    }

    private boolean add(UUID playerId, Player session, long amount) {
        if (amount <= 0L || !hasCapacity(playerId)) return false;
        trackedPlayers.add(playerId);
        pending.merge(playerId, new PendingExperience(amount, session), PendingExperience::merge);
        markReady(playerId);
        return true;
    }

    void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        if (flushTask != null) flushTask.cancel();
        if (feedbackTask != null) feedbackTask.cancel();
        pendingFeedback.clear();

        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMillis);
        long inFlightDeadlineNanos = startedNanos
                + TimeUnit.MILLISECONDS.toNanos(Math.max(250L, shutdownTimeoutMillis / 3L));
        waitForInFlight(inFlightDeadlineNanos);
        Map<UUID, Long> retry = new HashMap<>();
        pending.forEach((playerId, batch) -> retry.merge(playerId, batch.amount(), ExperienceWriteBuffer::saturatedAdd));
        for (Map.Entry<UUID, InFlightWrite> entry : inFlight.entrySet()) {
            CompletableFuture<PlayerProfile> future = entry.getValue().future();
            if (!future.isDone()) {
                plugin.getLogger().severe("Mob experience write remained in an uncertain state at shutdown player="
                        + entry.getKey() + " amount=" + entry.getValue().batch().amount());
                continue;
            }
            try {
                if (future.join() == null) {
                    retry.merge(entry.getKey(), entry.getValue().batch().amount(),
                            ExperienceWriteBuffer::saturatedAdd);
                }
            } catch (CompletionException exception) {
                retry.merge(entry.getKey(), entry.getValue().batch().amount(),
                        ExperienceWriteBuffer::saturatedAdd);
            }
        }
        drainKnownFailures(retry, deadlineNanos);

        pending.clear();
        inFlight.clear();
        completedWrites.clear();
        ready.clear();
        queued.clear();
        trackedPlayers.clear();
        failedAttempts.clear();
        retryAtMillis.clear();
    }

    private void flushReady() {
        drainCompletedWrites();
        long now = System.currentTimeMillis();
        int attempts = Math.min(ready.size(), maxFlushesPerTick);
        for (int index = 0; index < attempts; index++) {
            UUID playerId = ready.pollFirst();
            if (playerId == null) break;
            queued.remove(playerId);
            if (inFlight.containsKey(playerId) || now < retryAtMillis.getOrDefault(playerId, 0L)) {
                markReady(playerId);
                continue;
            }
            PendingExperience batch = pending.remove(playerId);
            if (batch == null || batch.amount() <= 0L) {
                releaseIfIdle(playerId);
                continue;
            }
            submit(playerId, batch);
        }
    }

    private void submit(UUID playerId, PendingExperience batch) {
        PlayerProfile before = profiles.cached(playerId).orElse(null);
        CompletableFuture<PlayerProfile> future;
        try {
            future = profiles.grantExperience(playerId, batch.amount()).toCompletableFuture();
        } catch (RuntimeException exception) {
            retry(playerId, batch, exception);
            return;
        }
        InFlightWrite write = new InFlightWrite(batch, before, future);
        inFlight.put(playerId, write);
        future.whenComplete((result, error) -> completedWrites.add(
                new CompletedWrite(playerId, write, result, error)));
    }

    private void drainCompletedWrites() {
        CompletedWrite completion;
        while ((completion = completedWrites.poll()) != null) {
            InFlightWrite current = inFlight.get(completion.playerId());
            if (current != completion.write()) continue;
            inFlight.remove(completion.playerId());
            if (completion.error() != null || completion.result() == null) {
                retry(completion.playerId(), current.batch(), completion.error());
                continue;
            }
            failedAttempts.remove(completion.playerId());
            retryAtMillis.remove(completion.playerId());
            recordPlayerFeedback(completion.playerId(), current.batch(), current.before(), completion.result());
            if (pending.containsKey(completion.playerId())) markReady(completion.playerId());
            releaseIfIdle(completion.playerId());
        }
    }

    private void retry(UUID playerId, PendingExperience batch, Throwable error) {
        pending.merge(playerId, batch, PendingExperience::merge);
        int attempts = failedAttempts.merge(playerId, 1, ExperienceWriteBuffer::saturatedIncrement);
        long delay = MobRewardReliabilityRules.retryDelayMillis(attempts, retryBaseMillis, retryMaximumMillis);
        retryAtMillis.put(playerId, System.currentTimeMillis() + delay);
        markReady(playerId);
        plugin.getLogger().warning("Buffered mob experience write will retry for " + playerId
                + " in " + delay + "ms: " + message(error));
    }

    private void waitForInFlight(long deadlineNanos) {
        if (inFlight.isEmpty()) return;
        CompletableFuture<?>[] waits = inFlight.values().stream()
                .map(write -> write.future().handle((result, error) -> null))
                .toArray(CompletableFuture[]::new);
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0L) return;
        try {
            CompletableFuture.allOf(waits).get(remaining, TimeUnit.NANOSECONDS);
        } catch (Exception exception) {
            plugin.getLogger().warning("Timed out waiting for in-flight mob experience: " + message(exception));
        }
    }

    private void drainKnownFailures(Map<UUID, Long> amounts, long deadlineNanos) {
        Map<UUID, Long> remaining = new HashMap<>(amounts);
        for (int attempt = 1; attempt <= shutdownAttempts && !remaining.isEmpty(); attempt++) {
            Map<UUID, CompletableFuture<PlayerProfile>> writes = new HashMap<>();
            Map<UUID, Long> couldNotStart = new HashMap<>();
            remaining.forEach((playerId, amount) -> {
                try {
                    writes.put(playerId, profiles.grantExperience(playerId, amount).toCompletableFuture());
                } catch (RuntimeException exception) {
                    couldNotStart.put(playerId, amount);
                }
            });
            long waitNanos = deadlineNanos - System.nanoTime();
            if (waitNanos > 0L && !writes.isEmpty()) {
                CompletableFuture<?>[] waits = writes.values().stream()
                        .map(write -> write.handle((result, error) -> null))
                        .toArray(CompletableFuture[]::new);
                try {
                    CompletableFuture.allOf(waits).get(waitNanos, TimeUnit.NANOSECONDS);
                } catch (Exception ignored) {
                    // Individual futures below distinguish known failure from uncertain timeout.
                }
            }
            Map<UUID, Long> retry = new HashMap<>(couldNotStart);
            for (Map.Entry<UUID, CompletableFuture<PlayerProfile>> entry : writes.entrySet()) {
                UUID playerId = entry.getKey();
                CompletableFuture<PlayerProfile> write = entry.getValue();
                if (!write.isDone()) {
                    plugin.getLogger().severe("Mob experience shutdown write is uncertain player=" + playerId
                            + " amount=" + remaining.get(playerId));
                    continue;
                }
                try {
                    if (write.join() == null) retry.put(playerId, remaining.get(playerId));
                } catch (CompletionException exception) {
                    retry.put(playerId, remaining.get(playerId));
                }
            }
            remaining = retry;
            if (System.nanoTime() >= deadlineNanos) break;
        }
        remaining.forEach((playerId, amount) -> plugin.getLogger().severe(
                "Mob experience could not be drained during normal shutdown player="
                        + playerId + " amount=" + amount));
    }

    private void markReady(UUID playerId) {
        if (queued.add(playerId)) ready.addLast(playerId);
    }

    private void releaseIfIdle(UUID playerId) {
        if (!pending.containsKey(playerId) && !inFlight.containsKey(playerId)) trackedPlayers.remove(playerId);
    }

    private void recordPlayerFeedback(
            UUID playerId, PendingExperience batch, PlayerProfile before, PlayerProfile updated
    ) {
        Player player = activeSession(playerId, batch.session());
        if (player == null) return;
        if (before != null && (updated.ascension() > before.ascension() || updated.level() > before.level())) {
            pendingFeedback.remove(playerId);
            player.sendMessage(Component.text(messages.text(player, "mobs.experience.level-up-chat", Map.of(
                    "amount", batch.amount(),
                    "ascension", updated.ascension(),
                    "level", updated.level())), NamedTextColor.GREEN));
            player.showTitle(Title.title(
                    Component.text(messages.text(player, "mobs.experience.level-up-title"), NamedTextColor.GOLD),
                    Component.text(messages.text(player, "mobs.experience.level-up-subtitle", Map.of(
                            "ascension", updated.ascension(),
                            "level", updated.level())), NamedTextColor.AQUA),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(450))));
            float volume = VisualMode.forPlayer(player).volume(0.75);
            if (volume > 0.0F) player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP,
                    SoundCategory.PLAYERS, volume, 1.15F);
            return;
        }
        pendingFeedback.merge(playerId, new FeedbackBatch(batch.amount(), updated, batch.session()),
                FeedbackBatch::merge);
    }

    private void flushPlayerFeedback() {
        drainCompletedWrites();
        if (pendingFeedback.isEmpty()) return;
        Map<UUID, FeedbackBatch> batches = Map.copyOf(pendingFeedback);
        pendingFeedback.clear();
        for (Map.Entry<UUID, FeedbackBatch> entry : batches.entrySet()) {
            FeedbackBatch batch = entry.getValue();
            Player player = activeSession(entry.getKey(), batch.session());
            if (player == null) continue;
            PlayerProfile profile = batch.profile();
            String key;
            Map<String, ?> placeholders;
            if (profile.level() >= ProgressionRules.levelCap(profile.ascension())) {
                key = "mobs.experience.gained-cap";
                placeholders = Map.of(
                        "amount", batch.amount(),
                        "ascension", profile.ascension(),
                        "level", profile.level());
            } else {
                key = "mobs.experience.gained";
                placeholders = Map.of(
                        "amount", batch.amount(),
                        "ascension", profile.ascension(),
                        "level", profile.level(),
                        "experience", profile.experience(),
                        "needed", ProgressionRules.experienceToNextLevel(profile.ascension(), profile.level()));
            }
            player.sendActionBar(Component.text(messages.text(player, key, placeholders), NamedTextColor.AQUA));
        }
    }

    static boolean sameSession(Player expected, Player current, boolean online) {
        return expected != null && current == expected && online;
    }

    private static Player activeSession(UUID playerId, Player expected) {
        Player current = Bukkit.getPlayer(playerId);
        return sameSession(expected, current, current != null && current.isOnline()) ? current : null;
    }

    private static int saturatedIncrement(int value, int ignored) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static String message(Throwable error) {
        if (error == null) return "invalid result";
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record PendingExperience(long amount, Player session) {
        private PendingExperience {
            if (amount <= 0L) throw new IllegalArgumentException("Experience amount must be positive");
        }

        private static PendingExperience merge(PendingExperience left, PendingExperience right) {
            Player session = left.session == right.session ? left.session : null;
            return new PendingExperience(saturatedAdd(left.amount, right.amount), session);
        }
    }

    private record InFlightWrite(
            PendingExperience batch, PlayerProfile before, CompletableFuture<PlayerProfile> future
    ) {
    }

    private record CompletedWrite(
            UUID playerId, InFlightWrite write, PlayerProfile result, Throwable error
    ) {
    }

    private record FeedbackBatch(long amount, PlayerProfile profile, Player session) {
        private static FeedbackBatch merge(FeedbackBatch left, FeedbackBatch right) {
            Player session = left.session == right.session ? left.session : null;
            return new FeedbackBatch(saturatedAdd(left.amount, right.amount), right.profile, session);
        }
    }
}
