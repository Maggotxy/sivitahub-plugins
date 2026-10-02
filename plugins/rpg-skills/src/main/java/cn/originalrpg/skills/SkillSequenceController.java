package cn.originalrpg.skills;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One shared heartbeat for every bounded skill animation or repeated effect.
 * A cast never creates an unbounded per-target scheduler. Under pressure the
 * controller reduces visual work first while preserving settlement cadence.
 */
final class SkillSequenceController implements AutoCloseable {
    private final Plugin plugin;
    private final Map<SequenceKey, ActiveSequence> active = new LinkedHashMap<>();
    private final BukkitTask heartbeat;
    private final int heartbeatTicks;
    private long logicalTick;
    private int cursor;
    private double recentTps = 20.0;

    SkillSequenceController(Plugin plugin) {
        this.plugin = plugin;
        this.heartbeatTicks = Math.clamp(plugin.getConfig().getInt(
                "sequence-budget.heartbeat-ticks", 2), 1, 4);
        this.heartbeat = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::tick, heartbeatTicks, heartbeatTicks);
    }

    boolean start(
            Player owner, String key, int intervalTicks, int maximumTicks,
            SequenceStep step, Runnable finish
    ) {
        return start(owner, key, intervalTicks, maximumTicks, step, finish, Priority.VISUAL);
    }

    /**
     * Starts a sequence whose damage, healing, movement or control settlement
     * must not disappear just because optional animations have consumed their
     * budget. Gameplay work is still key-bounded per player and protected by
     * a separate hard cap; callers retain a one-shot fallback for that remote
     * overload boundary.
     */
    boolean startGameplay(
            Player owner, String key, int intervalTicks, int maximumTicks,
            SequenceStep step, Runnable finish
    ) {
        return start(owner, key, intervalTicks, maximumTicks, step, finish, Priority.GAMEPLAY);
    }

    boolean startGameplay(
            Player owner, String key, int intervalTicks, int maximumTicks, SequenceStep step
    ) {
        return startGameplay(owner, key, intervalTicks, maximumTicks, step, null);
    }

    private boolean start(
            Player owner, String key, int intervalTicks, int maximumTicks,
            SequenceStep step, Runnable finish, Priority priority
    ) {
        if (!owner.isOnline() || owner.isDead()) return false;
        SequenceKey sequenceKey = new SequenceKey(owner.getUniqueId(), key);
        ActiveSequence replaced = active.remove(sequenceKey);
        if (replaced != null) finish(replaced);

        SkillSequenceRules.LoadProfile profile = loadProfile();
        if (priority == Priority.VISUAL && visualCount() >= activeLimit(profile)) return false;
        if (priority == Priority.GAMEPLAY && gameplayCount() >= gameplayActiveLimit()) return false;
        ActiveSequence sequence = new ActiveSequence(
                owner.getUniqueId(), Math.clamp(intervalTicks, heartbeatTicks, 40),
                Math.clamp(maximumTicks, heartbeatTicks, 20 * 30), logicalTick,
                step, finish == null ? () -> { } : finish, priority);
        active.put(sequenceKey, sequence);
        return true;
    }

    boolean start(Player owner, String key, int intervalTicks, int maximumTicks, SequenceStep step) {
        return start(owner, key, intervalTicks, maximumTicks, step, null);
    }

    void stopOwner(UUID owner) {
        List<SequenceKey> owned = active.keySet().stream()
                .filter(key -> key.owner().equals(owner)).toList();
        owned.forEach(key -> {
            ActiveSequence sequence = active.remove(key);
            if (sequence != null) finish(sequence);
        });
    }

    int activeCount() {
        return active.size();
    }

    int gameplayCount() {
        return (int) active.values().stream()
                .filter(sequence -> sequence.priority == Priority.GAMEPLAY).count();
    }

    int visualCount() {
        return active.size() - gameplayCount();
    }

    private void tick() {
        logicalTick += heartbeatTicks;
        if (logicalTick % 20L < heartbeatTicks) {
            double[] samples = plugin.getServer().getTPS();
            if (samples.length > 0 && Double.isFinite(samples[0])) recentTps = samples[0];
        }
        if (active.isEmpty()) return;

        SkillSequenceRules.LoadProfile profile = loadProfile();
        int visualBudget = workBudget(profile);
        List<SequenceKey> keys = new ArrayList<>(active.keySet());
        if (cursor >= keys.size()) cursor = 0;
        List<SequenceKey> ordered = new ArrayList<>(keys.size());
        for (int index = 0; index < keys.size(); index++) {
            ordered.add(keys.get((cursor + index) % keys.size()));
        }
        // Gameplay settlement is always visited when due. Optional visuals
        // consume the load-dependent budget afterwards.
        for (SequenceKey key : ordered) {
            ActiveSequence sequence = active.get(key);
            if (sequence == null || sequence.priority != Priority.GAMEPLAY
                    || logicalTick < sequence.nextTick) continue;
            runStep(key, sequence, profile);
        }
        int visualWork = 0;
        for (SequenceKey key : ordered) {
            if (visualWork >= visualBudget) break;
            ActiveSequence sequence = active.get(key);
            if (sequence == null || sequence.priority != Priority.VISUAL
                    || logicalTick < sequence.nextTick) continue;
            visualWork++;
            runStep(key, sequence, profile);
        }
        cursor = keys.isEmpty() ? 0 : (cursor + 1) % keys.size();
    }

    private void runStep(
            SequenceKey key, ActiveSequence sequence, SkillSequenceRules.LoadProfile profile
    ) {
        Player owner = plugin.getServer().getPlayer(sequence.owner);
        if (owner == null || !owner.isOnline() || owner.isDead()) {
            remove(key, sequence);
            return;
        }
        sequence.elapsedTicks += sequence.intervalTicks;
        boolean keep;
        try {
            keep = sequence.step.run(new SequenceFrame(
                    sequence.elapsedTicks, quality(profile)));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Stopped skill sequence " + key.name()
                    + " after " + exception.getClass().getSimpleName());
            keep = false;
        }
        if (!keep || sequence.elapsedTicks >= sequence.maximumTicks) {
            remove(key, sequence);
        } else {
            sequence.nextTick = logicalTick + sequence.intervalTicks;
        }
    }

    private SkillSequenceRules.LoadProfile loadProfile() {
        return SkillSequenceRules.profile(recentTps, active.size(),
                plugin.getConfig().getInt("sequence-budget.profile-at.pressure", 32),
                plugin.getConfig().getInt("sequence-budget.profile-at.critical", 56));
    }

    private int activeLimit(SkillSequenceRules.LoadProfile profile) {
        return SkillSequenceRules.activeLimit(profile,
                plugin.getConfig().getInt("sequence-budget.maximum-active.normal", 64),
                plugin.getConfig().getInt("sequence-budget.maximum-active.pressure", 36),
                plugin.getConfig().getInt("sequence-budget.maximum-active.critical", 18));
    }

    private int workBudget(SkillSequenceRules.LoadProfile profile) {
        return SkillSequenceRules.workBudget(profile,
                plugin.getConfig().getInt("sequence-budget.work-per-heartbeat.normal", 32),
                plugin.getConfig().getInt("sequence-budget.work-per-heartbeat.pressure", 16),
                plugin.getConfig().getInt("sequence-budget.work-per-heartbeat.critical", 6));
    }

    private int gameplayActiveLimit() {
        return SkillSequenceRules.gameplayActiveLimit(plugin.getConfig().getInt(
                "sequence-budget.maximum-gameplay-active", 256));
    }

    private static VisualQuality quality(SkillSequenceRules.LoadProfile profile) {
        return switch (profile) {
            case NORMAL -> VisualQuality.FULL;
            case PRESSURE -> VisualQuality.REDUCED;
            case CRITICAL -> VisualQuality.MINIMAL;
        };
    }

    private void remove(SequenceKey key, ActiveSequence expected) {
        if (active.remove(key, expected)) finish(expected);
    }

    private void finish(ActiveSequence sequence) {
        try {
            sequence.finish.run();
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Skill sequence cleanup failed: "
                    + exception.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        heartbeat.cancel();
        List<ActiveSequence> remaining = List.copyOf(active.values());
        active.clear();
        remaining.forEach(this::finish);
    }

    @FunctionalInterface
    interface SequenceStep {
        boolean run(SequenceFrame frame);
    }

    record SequenceFrame(int elapsedTicks, VisualQuality quality) {
    }

    enum VisualQuality {
        FULL,
        REDUCED,
        MINIMAL
    }

    private enum Priority {
        GAMEPLAY,
        VISUAL
    }

    private record SequenceKey(UUID owner, String name) {
    }

    private static final class ActiveSequence {
        private final UUID owner;
        private final int intervalTicks;
        private final int maximumTicks;
        private final SequenceStep step;
        private final Runnable finish;
        private final Priority priority;
        private long nextTick;
        private int elapsedTicks;

        private ActiveSequence(
                UUID owner, int intervalTicks, int maximumTicks, long nextTick,
                SequenceStep step, Runnable finish, Priority priority
        ) {
            this.owner = owner;
            this.intervalTicks = intervalTicks;
            this.maximumTicks = maximumTicks;
            this.nextTick = nextTick;
            this.step = step;
            this.finish = finish;
            this.priority = priority;
        }
    }
}
