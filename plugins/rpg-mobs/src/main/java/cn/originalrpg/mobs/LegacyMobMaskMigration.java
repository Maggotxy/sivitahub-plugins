package cn.originalrpg.mobs;

import cn.originalrpg.api.service.MobAppearanceService;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Set;

/**
 * Removes the six retired head-only mob masks from already persisted hostile
 * entities and restores the reviewed full-body shell where the entity's real
 * RPG marker still calls for it. Players and unmarked equipment are never
 * touched. Startup work is spread across ticks instead of scanning everything
 * in one hot tick.
 */
final class LegacyMobMaskMigration implements Listener, AutoCloseable {
    private static final int PER_TICK = 96;
    private static final Set<NamespacedKey> LEGACY_MODELS = Set.of(
            model("elite_swift_mask"),
            model("elite_berserk_mask"),
            model("elite_armored_mask"),
            model("elite_shadow_mask"),
            model("ancient_boss_mask"),
            model("hidden_boss_mask")
    );

    private final Plugin plugin;
    private final MobAppearanceService appearances;
    private final NamespacedKey eliteAffix;
    private final NamespacedKey bloodMoonRare;
    private final NamespacedKey bloodMoonArchetype;
    private final ArrayDeque<LivingEntity> pending = new ArrayDeque<>();
    private BukkitTask task;
    private int removed;

    LegacyMobMaskMigration(Plugin plugin, MobAppearanceService appearances) {
        this.plugin = plugin;
        this.appearances = appearances;
        this.eliteAffix = new NamespacedKey(plugin, "elite_affix");
        this.bloodMoonRare = Objects.requireNonNull(NamespacedKey.fromString("rpgworld:blood_moon_rare"));
        this.bloodMoonArchetype = Objects.requireNonNull(
                NamespacedKey.fromString("rpgworld:blood_moon_archetype"));
    }

    void start() {
        plugin.getServer().getWorlds().forEach(world -> pending.addAll(world.getLivingEntities()));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) {
            if (entity instanceof LivingEntity living) migrate(living);
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (var entity : event.getEntities()) {
            if (entity instanceof LivingEntity living) appearances.clear(living);
        }
    }

    private void drain() {
        for (int index = 0; index < PER_TICK; index++) {
            LivingEntity entity = pending.pollFirst();
            if (entity == null) {
                task.cancel();
                task = null;
                plugin.getLogger().info("Legacy head-only mob masks removed=" + removed);
                return;
            }
            migrate(entity);
        }
    }

    private void migrate(LivingEntity entity) {
        if (!(entity instanceof Enemy) || !entity.isValid() || entity.isDead()) return;
        EntityEquipment equipment = entity.getEquipment();
        ItemStack helmet = equipment == null ? null : equipment.getHelmet();
        if (helmet != null && isLegacyMask(helmet)) {
            equipment.setHelmet(null, true);
            removed++;
        }
        String affix = entity.getPersistentDataContainer().get(eliteAffix, PersistentDataType.STRING);
        String rareArchetype = entity.getPersistentDataContainer().has(bloodMoonRare, PersistentDataType.STRING)
                ? entity.getPersistentDataContainer().get(
                bloodMoonArchetype, PersistentDataType.STRING)
                : null;
        if (rareArchetype != null && !rareArchetype.isBlank()) {
            appearances.applyBloodMoonModel(entity, rareArchetype);
        } else if ("berserk".equals(affix)) {
            appearances.applyBloodMoonHusk(entity);
        }
    }

    private static boolean isLegacyMask(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.hasItemModel() && LEGACY_MODELS.contains(meta.getItemModel());
    }

    private static NamespacedKey model(String value) {
        return new NamespacedKey("originalrpg", value);
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
        pending.clear();
    }
}
