package cn.originalrpg.api.service;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

/**
 * Marks server-authored skill damage before it enters Bukkit's normal damage
 * event bus. Implementations retain final authority over PvP/Boss scaling.
 */
public interface SkillDamageService {
    void damage(Player caster, LivingEntity target, double baseDamage, String skillId);

    void tagProjectile(Projectile projectile, String skillId);

    /**
     * Tags an authored projectile together with its bounded content power.
     * Combat remains responsible for applying PvP/Boss normalization after
     * this multiplier. The default preserves compatibility with consumers
     * which do not distinguish equipment-borrowed casts.
     */
    default void tagProjectile(Projectile projectile, String skillId, double powerMultiplier) {
        tagProjectile(projectile, skillId);
    }
}
