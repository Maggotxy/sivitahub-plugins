package cn.originalrpg.mobs;

import cn.originalrpg.api.service.ChapterService;
import cn.originalrpg.api.service.MobAppearanceService;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Removes persisted natural mobs whose level could only have come from a
 * profile beyond the currently open chapter (normally an OP test profile).
 * Dungeon bosses and explicit test targets never receive the natural-level
 * marker, and the additional boss guard keeps this migration fail-closed.
 */
final class IllegalAdaptiveMobCleanup implements Listener, AutoCloseable {
    private static final int PER_TICK = 96;

    private final Plugin plugin;
    private final ChapterService chapters;
    private final MobAppearanceService appearances;
    private final int maximumPositiveOffset;
    private final NamespacedKey mobLevel;
    private final NamespacedKey dungeonBoss = NamespacedKey.fromString("rpgdungeons:boss");
    private final ArrayDeque<LivingEntity> pending = new ArrayDeque<>();
    private final Set<UUID> queued = new HashSet<>();
    private BukkitTask task;
    private boolean bootstrapping;
    private int removed;

    IllegalAdaptiveMobCleanup(
            Plugin plugin,
            ChapterService chapters,
            MobAppearanceService appearances,
            int maximumPositiveOffset
    ) {
        this.plugin = plugin;
        this.chapters = chapters;
        this.appearances = appearances;
        this.maximumPositiveOffset = maximumPositiveOffset;
        this.mobLevel = new NamespacedKey(plugin, "level");
    }

    void start() {
        bootstrapping = true;
        plugin.getServer().getWorlds().forEach(world -> world.getLivingEntities().forEach(this::enqueue));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (bootstrapping) enqueue(living);
            else inspect(living);
        }
    }

    private void enqueue(LivingEntity entity) {
        if (!(entity instanceof Enemy) || !queued.add(entity.getUniqueId())) return;
        pending.addLast(entity);
    }

    private void drain() {
        for (int index = 0; index < PER_TICK; index++) {
            LivingEntity entity = pending.pollFirst();
            if (entity == null) {
                task.cancel();
                task = null;
                bootstrapping = false;
                queued.clear();
                plugin.getLogger().info("Illegal persisted adaptive mobs removed=" + removed
                        + ", current maximum=" + maximumNaturalLevel());
                return;
            }
            queued.remove(entity.getUniqueId());
            inspect(entity);
        }
    }

    private void inspect(LivingEntity entity) {
        if (!(entity instanceof Enemy) || !entity.isValid() || entity.isDead()) return;
        if (dungeonBoss != null && entity.getPersistentDataContainer().has(
                dungeonBoss, PersistentDataType.BYTE)) return;
        Integer level = entity.getPersistentDataContainer().get(mobLevel, PersistentDataType.INTEGER);
        if (level == null || level <= maximumNaturalLevel()) return;
        appearances.clear(entity);
        entity.remove();
        removed++;
    }

    private int maximumNaturalLevel() {
        return AdaptiveMobSafetyRules.maximumNaturalLevel(
                chapters.current().maximumOpenAscension(), maximumPositiveOffset);
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
        bootstrapping = false;
        pending.clear();
        queued.clear();
    }
}
