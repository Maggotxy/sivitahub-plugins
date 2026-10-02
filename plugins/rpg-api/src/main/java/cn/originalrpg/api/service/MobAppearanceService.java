package cn.originalrpg.api.service;

import org.bukkit.entity.LivingEntity;

/**
 * Narrow bridge between gameplay plugins and the optional full-body model engine.
 * Combat, AI, collision, drops and persistence always remain on the real Bukkit entity.
 */
public interface MobAppearanceService {
    /** Applies a reviewed low-part-count blood-moon shell when the family and budget allow it. */
    boolean applyBloodMoonModel(LivingEntity entity, String archetype);

    /** Compatibility entry point for the original zombie-family shell. */
    default boolean applyBloodMoonHusk(LivingEntity entity) {
        return applyBloodMoonModel(entity, "bloodfiend");
    }

    /** Applies the reviewed model assigned to one of the six dungeon bosses. */
    boolean applyDungeonBossModel(LivingEntity entity, String dungeonId);

    /** Applies one reviewed low-part-count shell to a configured dungeon wave archetype. */
    boolean applyDungeonWaveModel(LivingEntity entity, String archetype);

    /** Plays a named one-shot animation when the entity currently has a model. */
    boolean playAnimation(LivingEntity entity, String animation);

    /** Removes a visual shell without changing the underlying entity. */
    void clear(LivingEntity entity);

    /** Returns whether the model engine and reviewed model are currently usable. */
    boolean available();

    /** Number of active visual shells, for diagnostics and capacity checks. */
    int activeCount();
}
