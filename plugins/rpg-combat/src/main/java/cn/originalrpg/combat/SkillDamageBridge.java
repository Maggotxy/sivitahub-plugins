package cn.originalrpg.combat;

import cn.originalrpg.api.service.SkillDamageService;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.regex.Pattern;

/** Main-thread scoped provenance; no player metadata or per-hit allocation map. */
final class SkillDamageBridge implements SkillDamageService {
    private static final Pattern SKILL_ID = Pattern.compile("[a-z0-9-]{2,64}");
    private final NamespacedKey projectileSkill;
    private final NamespacedKey projectilePower;
    private final ThreadLocal<String> activeSkill = new ThreadLocal<>();

    SkillDamageBridge(Plugin plugin) {
        projectileSkill = new NamespacedKey(plugin, "skill_projectile");
        projectilePower = new NamespacedKey(plugin, "skill_projectile_power");
    }

    @Override
    public void damage(Player caster, LivingEntity target, double baseDamage, String skillId) {
        requireMainThread();
        Objects.requireNonNull(caster, "caster");
        Objects.requireNonNull(target, "target");
        String validId = validId(skillId);
        if (!Double.isFinite(baseDamage) || baseDamage <= 0.0) {
            throw new IllegalArgumentException("Skill damage must be finite and positive");
        }
        String previous = activeSkill.get();
        activeSkill.set(validId);
        try {
            target.damage(baseDamage, caster);
        } finally {
            if (previous == null) activeSkill.remove();
            else activeSkill.set(previous);
        }
    }

    @Override
    public void tagProjectile(Projectile projectile, String skillId) {
        tagProjectile(projectile, skillId, 1.0);
    }

    @Override
    public void tagProjectile(Projectile projectile, String skillId, double powerMultiplier) {
        requireMainThread();
        if (!Double.isFinite(powerMultiplier)) {
            throw new IllegalArgumentException("Skill projectile power must be finite");
        }
        var data = Objects.requireNonNull(projectile, "projectile").getPersistentDataContainer();
        data.set(projectileSkill, PersistentDataType.STRING, validId(skillId));
        data.set(projectilePower, PersistentDataType.DOUBLE, Math.clamp(powerMultiplier, 0.25, 2.0));
    }

    String activeSkill() {
        return activeSkill.get();
    }

    String projectileSkill(Projectile projectile) {
        return projectile.getPersistentDataContainer().get(projectileSkill, PersistentDataType.STRING);
    }

    double projectilePower(Projectile projectile) {
        return Math.clamp(projectile.getPersistentDataContainer().getOrDefault(
                projectilePower, PersistentDataType.DOUBLE, 1.0), 0.25, 2.0);
    }

    private static String validId(String skillId) {
        if (skillId == null || !SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("Invalid skill damage id");
        }
        return skillId;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Skill damage must enter on the primary thread");
    }
}
