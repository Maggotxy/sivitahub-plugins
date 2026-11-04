package cn.originalrpg.mobs;

import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.MarketService;
import cn.originalrpg.api.service.ProfileService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Server-thread adapter for the durable, per-player FIFO natural-mob reward protocol.
 *
 * <p>All custom rewards, including cache-hit kills, enter the same journal. Bukkit
 * objects are used only for original serialization and optional feedback. The
 * filesystem worker receives immutable values; database completions return to the
 * settlement heartbeat through its bounded completion queue. Recovery may run
 * offline and does not reuse a departed player's session as an authorization token.</p>
 */
public final class DelayedMobRewardQueue {
    private final Plugin plugin;
    private final Feedback feedback;
    private final ExperienceWriteBuffer experience;
    private final MobRewardJournal journal;
    private final MobRewardIoCoordinator io;
    private final MobRewardSettlement settlement;
    private final Map<UUID, Player> feedbackSessions = new HashMap<>();
    private final long shutdownTimeoutMillis;
    private BukkitTask pumpTask;
    private boolean closed;
    private long lastRejectionLog;
    private long rejectedSinceLog;

    public DelayedMobRewardQueue(Plugin plugin, ProfileService profiles, MarketService market,
                                 ExperienceWriteBuffer experience, Feedback feedback) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(market, "market");
        this.experience = Objects.requireNonNull(experience, "experience");
        int maximumPending = Math.clamp(plugin.getConfig().getInt("delayed-rewards.maximum-pending", 512), 32, 4096);
        int maximumPerPlayer = Math.clamp(plugin.getConfig().getInt("delayed-rewards.maximum-per-player", 32), 4, 128);
        long maximumBytes = Math.clamp(plugin.getConfig().getLong(
                "delayed-rewards.maximum-payload-bytes", 16L * 1024 * 1024), 1024L * 1024, 256L * 1024 * 1024);
        int maximumIo = Math.clamp(plugin.getConfig().getInt("delayed-rewards.maximum-journal-operations", 16), 1, 64);
        int maximumProfiles = Math.clamp(plugin.getConfig().getInt("delayed-rewards.maximum-profile-loads", 16), 1, 64);
        int maximumExperience = Math.clamp(plugin.getConfig().getInt("delayed-rewards.maximum-experience-writes", 16), 1, 64);
        int maximumMailbox = Math.clamp(plugin.getConfig().getInt("delayed-rewards.maximum-mailbox-writes", 32), 1, 128);
        int maximumSteps = Math.clamp(plugin.getConfig().getInt("delayed-rewards.maximum-settlements-per-tick", 16), 1, 64);
        long retryMillis = Math.clamp(plugin.getConfig().getLong("delayed-rewards.retry-ticks", 20L), 5L, 100L) * 50L;
        long retryMaximum = Math.max(retryMillis, Math.clamp(plugin.getConfig().getLong(
                "delayed-rewards.retry-maximum-ticks", 200L), 20L, 1200L) * 50L);
        shutdownTimeoutMillis = Math.clamp(plugin.getConfig().getLong(
                "delayed-rewards.shutdown-timeout-ms", 5000L), 1000L, 30_000L);
        journal = new MobRewardJournal(plugin.getDataFolder().toPath().resolve("mob-reward-journal"),
                maximumPending, maximumBytes);
        io = new MobRewardIoCoordinator(journal, maximumIo, plugin.getLogger());
        long startedNanos = System.nanoTime();
        settlement = new MobRewardSettlement(io, new MobRewardSettlement.Authority() {
            @Override public CompletionStage<Integer> effectiveLevel(UUID playerId) {
                return profiles.get(playerId).thenApply(profile -> authoritativeLevel(playerId, profile));
            }
            @Override public CompletionStage<Boolean> experience(MobRewardIntent intent) {
                return experience.apply(intent, feedbackSessions.get(intent.killId()));
            }
            @Override public CompletionStage<Boolean> delivery(MobRewardIntent intent, MobRewardIntent.Delivery delivery) {
                return market.enqueueDelivery(delivery.deliveryId(), intent.playerId(), delivery.itemPayload(),
                        delivery.source(intent.killId()));
            }
        }, new MobRewardSettlement.Observer() {
            @Override public void recovered(int count) {
                plugin.getLogger().info("Mob reward journal ready; recovered " + count + " immutable intent(s)");
                if (journal.orphanedStagingFiles() > 0) plugin.getLogger().warning(
                        "Retained " + journal.orphanedStagingFiles() + " unaccepted mob reward staging file(s); "
                                + "they are NOT replayed. Inspect RECOVERY.md before maintenance.");
            }
            @Override public void ineligible(MobRewardIntent intent) {
                safelyNotify(() -> feedback.levelGap(context(intent)));
            }
            @Override public void settled(MobRewardIntent intent) {
                safelyNotify(() -> feedback.delivered(context(intent)));
            }
            @Override public void removed(MobRewardIntent intent) {
                feedbackSessions.remove(intent.killId());
            }
            @Override public void failure(MobRewardIntent intent, String operation, Throwable error, int attempts) {
                if (attempts == 1 || (attempts & (attempts - 1)) == 0) {
                    plugin.getLogger().warning("Mob reward " + operation + " failed; retained for retry #" + attempts
                            + (intent == null ? " (recovery; new custom rewards paused)" : " kill=" + intent.killId())
                            + " cause=" + error);
                }
                if (intent != null && (operation.equals("persist") || operation.equals("decision"))) {
                    safelyNotify(() -> feedback.saveDelayed(context(intent)));
                }
            }
        }, new MobRewardSettlement.Limits(maximumPending, maximumPerPlayer, maximumBytes,
                maximumIo, maximumProfiles, maximumExperience, maximumMailbox, maximumSteps, retryMillis, retryMaximum),
                () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static int authoritativeLevel(UUID playerId, PlayerProfile profile) {
        if (profile == null || !playerId.equals(profile.playerId())) {
            throw new IllegalStateException("Profile lookup did not return the requested player");
        }
        return profile.effectiveLevel();
    }

    public void start() {
        requirePrimaryThread();
        if (pumpTask != null || closed) throw new IllegalStateException("Mob reward queue already started or closed");
        settlement.tick(); // Starts asynchronous recovery before accepting any new observation.
        pumpTask = Bukkit.getScheduler().runTaskTimer(plugin, settlement::tick, 1L, 1L);
    }

    public boolean hasCapacity(UUID playerId) {
        requirePrimaryThread();
        return settlement.hasCapacity(playerId);
    }

    /** Releases only the departed session's UI references; its accepted rewards still settle offline. */
    public void forgetSession(Player session) {
        requirePrimaryThread();
        feedbackSessions.values().removeIf(expected -> expected == session);
        experience.forgetSession(session);
    }

    /**
     * Reserves bounded volatile space. QUEUED_NOT_DURABLE is deliberately not a
     * promise of recovery: hard exit before the journal acknowledgement can lose
     * this observation. No core XP or mailbox operation is issued before that ack.
     */
    public Admission offer(MobRewardIntent intent, Player session) {
        requirePrimaryThread();
        Objects.requireNonNull(session, "session");
        if (!intent.playerId().equals(session.getUniqueId())) throw new IllegalArgumentException("Wrong reward session");
        MobRewardSettlement.Admission result = settlement.offer(intent);
        if (result == MobRewardSettlement.Admission.QUEUED_NOT_DURABLE) feedbackSessions.put(intent.killId(), session);
        if (result != MobRewardSettlement.Admission.QUEUED_NOT_DURABLE
                && result != MobRewardSettlement.Admission.ALREADY_PENDING) reportRejected();
        return Admission.valueOf(result.name());
    }

    /** Rate-limited operator evidence for pre-acceptance backpressure. No reward is silently promised. */
    public void reportRejected() {
        requirePrimaryThread();
        rejectedSinceLog++;
        long now = System.currentTimeMillis();
        if (now - lastRejectionLog >= 10_000L) {
            plugin.getLogger().warning("Custom mob reward observations rejected BEFORE durable acceptance: "
                    + rejectedSinceLog + "; status=" + settlement.status() + "; vanilla drops are unaffected");
            rejectedSinceLog = 0;
            lastRejectionLog = now;
        }
    }

    /**
     * Stops admission, revokes async continuations, and waits only for a bounded
     * final journal pass. Accepted intents remain replayable without a DB drain.
     */
    public void close() {
        requirePrimaryThread();
        if (closed) return;
        closed = true;
        if (pumpTask != null) pumpTask.cancel();
        MobRewardSettlement.Status before = settlement.status();
        List<MobRewardIntent> unconfirmed = settlement.close();
        MobRewardIoCoordinator.ShutdownReport result = io.close(Duration.ofMillis(shutdownTimeoutMillis), unconfirmed);
        feedbackSessions.clear();
        plugin.getLogger().info("Mob reward settlement stopped; known journal-owned pending=" + before.durable()
                + ", startupRecoveryIncomplete=" + before.recovering()
                + ", final-pass unconfirmed=" + result.unconfirmed().size() + ", timedOut=" + result.timedOut());
        if (rejectedSinceLog > 0) plugin.getLogger().warning("Additional mob rewards rejected before acceptance: " + rejectedSinceLog);
    }

    private RewardFeedback context(MobRewardIntent intent) {
        return new RewardFeedback(intent, feedbackSessions.get(intent.killId()));
    }

    private void safelyNotify(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { plugin.getLogger().warning("Mob reward feedback failed: " + error); }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Mob reward queue must run on the server thread");
    }

    public enum Admission { QUEUED_NOT_DURABLE, ALREADY_PENDING, RECOVERING, PAUSED, CAPACITY, CLOSED, CONFLICT }

    /** UI hooks run on the server thread and cannot affect the reward's authority. */
    public interface Feedback {
        void levelGap(RewardFeedback reward);
        void delivered(RewardFeedback reward);
        default void saveDelayed(RewardFeedback reward) { }
    }

    /** A login session is only a short-lived presentation token, never journal data. */
    public record RewardFeedback(MobRewardIntent intent, Player session) {
        public Player activeSession() {
            if (session == null || !session.isOnline()) return null;
            Player current = Bukkit.getPlayer(intent.playerId());
            return ExperienceWriteBuffer.sameSession(session, current, current != null && current.isOnline()) ? current : null;
        }
    }
}
