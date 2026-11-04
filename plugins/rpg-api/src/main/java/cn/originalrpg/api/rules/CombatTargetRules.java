package cn.originalrpg.api.rules;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Objects;

/**
 * Shared PvE classification for native enemies and explicitly registered dungeon mobs.
 *
 * <p>{@code Monster} is only one branch of Paper's entity hierarchy: slimes,
 * ghasts and phantoms implement {@link Enemy} without being monsters, and a
 * sulfur cube is naturally passive. Only a cube carrying the existing dungeon
 * boss or wave marker becomes a PvE target. Players and unmarked passive mobs
 * remain outside this contract; PvP continues to use its separate party and
 * world rules.
 */
public final class CombatTargetRules {
    private static final NamespacedKey DUNGEON_BOSS =
            Objects.requireNonNull(NamespacedKey.fromString("rpgdungeons:boss"));
    private static final NamespacedKey DUNGEON_WAVE =
            Objects.requireNonNull(NamespacedKey.fromString("rpgdungeons:wave_mob"));

    private CombatTargetRules() {
    }

    /**
     * Returns whether the entity carries an authoritative dungeon combat marker.
     * This check intentionally also works during death and split events, after
     * the entity has ceased to be a valid living target.
     */
    public static boolean isDungeonCombatant(Entity entity) {
        if (!(entity instanceof Mob)) return false;
        PersistentDataContainer data = entity.getPersistentDataContainer();
        return marked(data, DUNGEON_BOSS) || marked(data, DUNGEON_WAVE);
    }

    /** Returns whether a living, present mob is eligible for automatic PvE targeting. */
    public static boolean isPveTarget(LivingEntity entity) {
        return entity instanceof Mob && entity.isValid() && !entity.isDead()
                && (entity instanceof Enemy || isDungeonCombatant(entity));
    }

    /**
     * Returns eligible mobs in Paper's bounded nearby-entity query.
     * Callers retain their existing line-of-sight, range, sorting and target
     * limits; the helper only makes the shared entity classification consistent.
     */
    public static List<Mob> nearbyPveTargets(Location center, double radius) {
        Objects.requireNonNull(center, "center");
        if (center.getWorld() == null || !Double.isFinite(radius) || radius < 0.0) return List.of();
        return center.getWorld().getNearbyEntitiesByType(Mob.class, center, radius).stream()
                .filter(CombatTargetRules::isPveTarget)
                .toList();
    }

    private static boolean marked(PersistentDataContainer data, NamespacedKey key) {
        return Byte.valueOf((byte) 1).equals(data.get(key, PersistentDataType.BYTE));
    }
}
