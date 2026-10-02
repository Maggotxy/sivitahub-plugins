package cn.originalrpg.mobs;

import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.service.MobAppearanceService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityHideOption;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerModifier;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Performance-bounded BetterModel adapter. Only packets and visual displays are delegated;
 * the original entity remains the sole combat and loot authority.
 */
final class BetterModelMobAppearanceService implements MobAppearanceService, AutoCloseable {
    static final String MODEL_ID = "blood_moon_resonant_husk";
    private static final Map<String, String> MODELS = Map.of(
            "bloodfiend", MODEL_ID,
            "eclipse-archer", "blood_moon_grave_knight",
            "rift-walker", "blood_moon_rift_stalker"
    );
    private static final Map<String, String> DUNGEON_MODELS = Map.of(
            "ancient-ruins", "boss_ruins_inscription_warden",
            "ancient-ruins-hidden", "boss_ruins_echo_adjudicator",
            "sulfur-depths", "boss_sulfur_crystal_devourer",
            "sulfur-depths-hidden", "boss_cinnabar_reborn_core",
            "storm-vault", "boss_storm_vault_keykeeper",
            "storm-vault-hidden", "boss_silent_thunder_oathguard"
    );
    private static final Map<String, String> DUNGEON_WAVE_MODELS = Map.of(
            "ruins-runebound", "dungeon_ruins_runebound",
            "ruins-echo-sentinel", "dungeon_ruins_echo_sentinel",
            "sulfur-fume-crawler", "dungeon_sulfur_fume_crawler",
            "sulfur-slag-mote", "dungeon_sulfur_slag_mote",
            "storm-conductor", "dungeon_storm_conductor",
            "storm-gust-wisp", "dungeon_storm_gust_wisp"
    );

    private final Plugin plugin;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final boolean enabled;
    private final int normalLimit;
    private final int pressureLimit;
    private final BukkitTask reconcileTask;
    private final Set<String> warnedMissingModels = new java.util.HashSet<>();

    BetterModelMobAppearanceService(Plugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("models.enabled", true);
        this.normalLimit = Math.clamp(plugin.getConfig().getInt("models.max-active.normal", 64), 0, 256);
        this.pressureLimit = Math.clamp(plugin.getConfig().getInt("models.max-active.pressure", 20), 0, normalLimit);
        long interval = Math.clamp(plugin.getConfig().getLong("models.reconcile-ticks", 100L), 40L, 1200L);
        this.reconcileTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reconcile,
                interval, interval);
    }

    @Override
    public boolean applyBloodMoonModel(LivingEntity entity, String archetype) {
        String modelId = MODELS.get(archetype);
        if (!enabled || modelId == null || !supported(entity, archetype)) return false;
        return apply(entity, modelId);
    }

    @Override
    public boolean applyDungeonBossModel(LivingEntity entity, String dungeonId) {
        String modelId = DUNGEON_MODELS.get(dungeonId);
        if (!enabled || modelId == null) return false;
        return apply(entity, modelId);
    }

    @Override
    public boolean applyDungeonWaveModel(LivingEntity entity, String archetype) {
        String modelId = DUNGEON_WAVE_MODELS.get(archetype);
        if (!enabled || modelId == null) return false;
        return apply(entity, modelId);
    }

    @Override
    public boolean playAnimation(LivingEntity entity, String animation) {
        if (animation == null || animation.isBlank()) return false;
        Entry entry = entries.get(entity.getUniqueId());
        if (entry == null || entry.tracker == null || entry.tracker.isClosed()) return false;
        try {
            return entry.tracker.animate(animation, AnimationModifier.DEFAULT_WITH_PLAY_ONCE);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean apply(LivingEntity entity, String modelId) {
        Entry existing = entries.get(entity.getUniqueId());
        if (existing != null && !existing.modelId.equals(modelId)) {
            closeTracker(existing);
            entries.remove(entity.getUniqueId());
        }
        Entry entry = entries.computeIfAbsent(entity.getUniqueId(),
                ignored -> new Entry(entity, modelId, System.nanoTime()));
        if (entry.tracker != null && !entry.tracker.isClosed()) return true;
        if (activeCount() >= limit()) return false;
        return attach(entry);
    }

    @Override
    public void clear(LivingEntity entity) {
        Entry entry = entries.remove(entity.getUniqueId());
        if (entry != null) closeTracker(entry);
    }

    @Override
    public boolean available() {
        return enabled && java.util.stream.Stream.concat(
                        java.util.stream.Stream.concat(MODELS.values().stream(), DUNGEON_MODELS.values().stream()),
                        DUNGEON_WAVE_MODELS.values().stream())
                .anyMatch(id -> BetterModel.model(id).isPresent());
    }

    @Override
    public int activeCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.tracker != null && !entry.tracker.isClosed()) count++;
        }
        return count;
    }

    private boolean attach(Entry entry) {
        if (!entry.entity.isValid() || entry.entity.isDead()) return false;
        try {
            EntityTracker tracker = BetterModel.model(entry.modelId)
                    .map(renderer -> renderer.getOrCreate(BukkitAdapter.adapt(entry.entity),
                            TrackerModifier.builder()
                                    .sightTrace(true)
                                    .damageAnimation(true)
                                    .damageTint(true)
                                    .build()))
                    .orElse(null);
            if (tracker == null) {
                if (warnedMissingModels.add(entry.modelId)) {
                    plugin.getLogger().warning("BetterModel model is unavailable: " + entry.modelId
                            + "; using the intact vanilla entity instead of a head-only mask.");
                }
                return false;
            }
            tracker.hideOption(EntityHideOption.DEFAULT);
            tracker.damageTintValue(0xE65454);
            entry.tracker = tracker;
            tracker.animate("spawn", AnimationModifier.DEFAULT_WITH_PLAY_ONCE);
            return true;
        } catch (RuntimeException exception) {
            if (warnedMissingModels.add(entry.modelId)) {
                plugin.getLogger().warning("Could not attach BetterModel shell " + entry.modelId + ": "
                        + exception.getMessage());
            }
            return false;
        }
    }

    private void reconcile() {
        entries.values().removeIf(entry -> {
            if (entry.entity.isValid() && !entry.entity.isDead()) return false;
            closeTracker(entry);
            return true;
        });
        int limit = limit();
        int active = activeCount();
        if (active > limit) {
            entries.values().stream()
                    .filter(entry -> entry.tracker != null && !entry.tracker.isClosed())
                    .sorted(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed())
                    .limit(active - limit)
                    .forEach(this::closeTracker);
        }
        if (limit <= 0) return;
        int capacity = limit - activeCount();
        if (capacity <= 0) return;
        for (Entry entry : entries.values()) {
            if (capacity <= 0) break;
            if (entry.tracker == null || entry.tracker.isClosed()) {
                if (attach(entry)) capacity--;
            }
        }
    }

    private int limit() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        ServerLoadState state = performance == null ? ServerLoadState.NORMAL : performance.state();
        return switch (state) {
            case NORMAL -> normalLimit;
            case PRESSURE -> pressureLimit;
            case CRITICAL -> 0;
        };
    }

    private boolean supported(LivingEntity entity, String archetype) {
        return switch (archetype) {
            case "bloodfiend" -> entity instanceof Zombie;
            case "eclipse-archer" -> entity instanceof AbstractSkeleton;
            case "rift-walker" -> entity instanceof Enderman;
            default -> false;
        };
    }

    private void closeTracker(Entry entry) {
        EntityTracker tracker = entry.tracker;
        entry.tracker = null;
        if (tracker != null && !tracker.isClosed()) tracker.close();
    }

    @Override
    public void close() {
        reconcileTask.cancel();
        entries.values().forEach(this::closeTracker);
        entries.clear();
    }

    private static final class Entry {
        private final LivingEntity entity;
        private final String modelId;
        private final long createdAt;
        private EntityTracker tracker;

        private Entry(LivingEntity entity, String modelId, long createdAt) {
            this.entity = entity;
            this.modelId = modelId;
            this.createdAt = createdAt;
        }
    }
}
