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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Submits journal-owned XP receipts and coalesces their player feedback.
 *
 * <p>The historical class name is retained, but XP amounts are no longer merged
 * into volatile settlement batches. Each accepted kill has one stable receipt
 * owned by {@link MobRewardIntent}; the durable queue alone decides when to retry.
 * Only action-bar feedback is coalesced. A restart needs no state from this class.</p>
 */
public final class ExperienceWriteBuffer {
    private final Plugin plugin;
    private final ProfileService profiles;
    private final MessageService messages;
    private final Map<UUID, FeedbackBatch> pendingFeedback = new HashMap<>();
    private final Map<UUID, FeedbackSession> feedbackSessions = new HashMap<>();
    private final ConcurrentLinkedQueue<CompletedWrite> completedWrites = new ConcurrentLinkedQueue<>();
    private BukkitTask completionTask;
    private BukkitTask feedbackTask;
    private volatile boolean shuttingDown;

    ExperienceWriteBuffer(Plugin plugin, ProfileService profiles, MessageService messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.messages = messages;
    }

    void start() {
        completionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainFeedback, 1L, 1L);
        long feedbackTicks = Math.clamp(
                plugin.getConfig().getLong("experience-write-buffer.feedback-ticks", 60L), 40L, 200L);
        feedbackTask = Bukkit.getScheduler().runTaskTimer(plugin,
                this::flushPlayerFeedback, feedbackTicks, feedbackTicks);
    }

    /**
     * Applies one durably accepted, eligible kill through the core XP authority.
     * This method does not accept a new reward or retry independently. Callers
     * must retain the journal until this and every item delivery are confirmed.
     * The optional session is a feedback token only; offline recovery is valid.
     */
    CompletionStage<Boolean> apply(MobRewardIntent intent, Player session) {
        Objects.requireNonNull(intent, "intent");
        if (shuttingDown) return CompletableFuture.failedFuture(new IllegalStateException("Mob XP presentation is closing"));
        if (intent.eligibility() != MobRewardIntent.Eligibility.ELIGIBLE) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unresolved/ineligible reward cannot grant XP"));
        }
        if (session != null && !intent.playerId().equals(session.getUniqueId())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Feedback session belongs to another player"));
        }
        if (intent.experience() == 0) return CompletableFuture.completedFuture(true);
        FeedbackSession token = session == null ? null : feedbackSessions.compute(intent.playerId(), (id, current) -> {
            if (current != null && current.player == session) return current;
            if (current != null) current.active = false;
            return new FeedbackSession(session);
        });
        PlayerProfile before = profiles.cached(intent.playerId()).orElse(null);
        // This invocation runs on the queue owner's thread. The continuation
        // below only passes immutable profile values; it never calls Bukkit.
        return profiles.grantExperienceOnce(intent.playerId(), intent.experienceGrantId(), intent.experience())
                .thenApply(updated -> {
                    if (updated == null || !intent.playerId().equals(updated.playerId())) return false;
                    if (!shuttingDown && token != null && token.active) completedWrites.add(new CompletedWrite(
                            intent.playerId(), new FeedbackInput(intent.experience(), token), before, updated));
                    return true;
                });
    }

    /** Stops UI work only. Accepted XP remains owned by the durable reward queue. */
    void shutdown() {
        shuttingDown = true;
        if (completionTask != null) completionTask.cancel();
        if (feedbackTask != null) feedbackTask.cancel();
        pendingFeedback.clear();
        feedbackSessions.values().forEach(token -> token.active = false);
        feedbackSessions.clear();
        completedWrites.clear();
    }

    void forgetSession(Player session) {
        FeedbackSession token = feedbackSessions.get(session.getUniqueId());
        if (token != null && token.player == session) {
            token.active = false;
            feedbackSessions.remove(session.getUniqueId());
            pendingFeedback.remove(session.getUniqueId());
        }
    }

    void drainFeedback() {
        if (shuttingDown) return;
        CompletedWrite completion;
        while ((completion = completedWrites.poll()) != null) {
            try {
                recordPlayerFeedback(completion.playerId(), completion.input(), completion.before(), completion.updated());
            } catch (RuntimeException error) {
                // A cosmetic failure must not turn a committed receipt into a
                // second grant or prevent other players' notifications.
                plugin.getLogger().warning("Mob XP feedback failed for " + completion.playerId() + ": " + error);
            }
        }
    }

    private void recordPlayerFeedback(
            UUID playerId, FeedbackInput batch, PlayerProfile before, PlayerProfile updated
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
        pendingFeedback.merge(playerId, new FeedbackBatch(batch.amount(), updated, batch.token()),
                FeedbackBatch::merge);
    }

    private void flushPlayerFeedback() {
        drainFeedback();
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
        if (expected == null || !expected.isOnline()) return null;
        Player current = Bukkit.getPlayer(playerId);
        return sameSession(expected, current, current != null && current.isOnline()) ? current : null;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class FeedbackSession {
        private final Player player;
        private volatile boolean active = true;
        private FeedbackSession(Player player) { this.player = player; }
    }
    private record FeedbackInput(long amount, FeedbackSession token) {
        Player session() { return token != null && token.active ? token.player : null; }
    }
    private record CompletedWrite(UUID playerId, FeedbackInput input, PlayerProfile before, PlayerProfile updated) { }

    private record FeedbackBatch(long amount, PlayerProfile profile, FeedbackSession token) {
        Player session() { return token != null && token.active ? token.player : null; }
        private static FeedbackBatch merge(FeedbackBatch left, FeedbackBatch right) {
            FeedbackSession token = left.token == right.token ? left.token : null;
            return new FeedbackBatch(saturatedAdd(left.amount, right.amount), right.profile, token);
        }
    }
}
