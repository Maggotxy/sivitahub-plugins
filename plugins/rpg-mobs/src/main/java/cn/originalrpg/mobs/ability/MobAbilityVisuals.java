package cn.originalrpg.mobs.ability;

import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.List;

/** Bounded client-side feedback. It never owns combat state or scans a world. */
final class MobAbilityVisuals {
    private final Plugin plugin;
    private final double receiverRadius;
    private final int maximumReceivers;

    MobAbilityVisuals(Plugin plugin) {
        this.plugin = plugin;
        receiverRadius = Math.clamp(plugin.getConfig().getDouble(
                "normal-behaviors.visuals.receiver-radius", 32.0), 8.0, 48.0);
        maximumReceivers = Math.clamp(plugin.getConfig().getInt(
                "normal-behaviors.visuals.maximum-receivers", 8), 1, 16);
    }

    ServerLoadState load() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        return performance == null ? ServerLoadState.NORMAL : performance.state();
    }

    void telegraph(LivingEntity monster, Location target, MobBehavior behavior) {
        Location start = monster.getEyeLocation();
        Vector delta = target.toVector().subtract(start.toVector());
        double distance = delta.length();
        if (distance < 0.1) return;
        ServerLoadState load = load();
        int points = switch (load) {
            case NORMAL -> Math.clamp((int) Math.ceil(distance * 1.4), 5, 18);
            case PRESSURE -> Math.clamp((int) Math.ceil(distance * 0.7), 3, 8);
            case CRITICAL -> 0;
        };
        Particle.DustOptions dust = new Particle.DustOptions(color(behavior), 0.85F);
        if (points > 0) {
            Vector step = delta.multiply(1.0 / points);
            for (Player receiver : receivers(start)) {
                int visible = VisualMode.forPlayer(receiver).particles(points);
                if (visible <= 0) continue;
                int stride = Math.max(1, (int) Math.ceil((double) points / visible));
                for (int point = 1; point <= points; point += stride) {
                    receiver.spawnParticle(Particle.DUST,
                            start.clone().add(step.clone().multiply(point)), 1,
                            0.0, 0.0, 0.0, 0.0, dust);
                }
            }
        }
    }

    void cue(LivingEntity monster, MobBehavior behavior) {
        Location center = monster.getLocation().add(0.0, Math.max(0.5, monster.getHeight() * 0.55), 0.0);
        ServerLoadState load = load();
        int configured = switch (load) {
            case NORMAL -> 8;
            case PRESSURE -> 3;
            case CRITICAL -> 0;
        };
        Particle.DustOptions dust = new Particle.DustOptions(color(behavior), 1.0F);
        for (Player receiver : receivers(center)) {
            int count = VisualMode.forPlayer(receiver).particles(configured);
            if (count > 0) receiver.spawnParticle(Particle.DUST, center, count,
                    0.38, 0.45, 0.38, 0.01, dust);
            play(receiver, center, cueSound(behavior), 0.36F, cuePitch(behavior), load);
        }
    }

    void trail(Location location, MobBehavior behavior) {
        ServerLoadState load = load();
        int configured = load == ServerLoadState.NORMAL ? 2 : load == ServerLoadState.PRESSURE ? 1 : 0;
        if (configured <= 0) return;
        Particle particle = switch (behavior) {
            case CHARGER -> Particle.CLOUD;
            case SPITTER -> Particle.ITEM_SLIME;
            case BOOMERANG -> Particle.ENCHANTED_HIT;
            default -> Particle.END_ROD;
        };
        for (Player receiver : receivers(location)) {
            int count = VisualMode.forPlayer(receiver).particles(configured);
            if (count > 0) receiver.spawnParticle(particle, location, count,
                    0.08, 0.08, 0.08, 0.01);
        }
    }

    void impact(Location location, MobBehavior behavior) {
        ServerLoadState load = load();
        int configured = switch (load) {
            case NORMAL -> 12;
            case PRESSURE -> 5;
            case CRITICAL -> 1;
        };
        Particle particle = switch (behavior) {
            case SPITTER -> Particle.ITEM_SLIME;
            case CONDUCTOR -> Particle.ELECTRIC_SPARK;
            case WARDEN -> Particle.ENCHANTED_HIT;
            case BURROWER -> Particle.REVERSE_PORTAL;
            case TRAPPER -> Particle.WITCH;
            case ORBITER -> Particle.END_ROD;
            default -> Particle.CRIT;
        };
        for (Player receiver : receivers(location)) {
            int count = VisualMode.forPlayer(receiver).particles(configured);
            if (count > 0) receiver.spawnParticle(particle, location, count,
                    0.45, 0.5, 0.45, 0.04);
            play(receiver, location, impactSound(behavior), 0.42F, impactPitch(behavior), load);
        }
    }

    void pool(Location center, double radius) {
        ServerLoadState load = load();
        int points = switch (load) {
            case NORMAL -> 14;
            case PRESSURE -> 6;
            case CRITICAL -> 0;
        };
        if (points <= 0) return;
        for (Player receiver : receivers(center)) {
            int visible = VisualMode.forPlayer(receiver).particles(points);
            for (int i = 0; i < visible; i++) {
                double angle = Math.PI * 2.0 * i / Math.max(1, visible);
                Location point = center.clone().add(Math.cos(angle) * radius, 0.12,
                        Math.sin(angle) * radius);
                receiver.spawnParticle(Particle.ITEM_SLIME, point, 1,
                        0.05, 0.02, 0.05, 0.0);
            }
        }
    }

    void ring(Location center, double radius, MobBehavior behavior) {
        ServerLoadState load = load();
        int points = switch (load) {
            case NORMAL -> 18;
            case PRESSURE -> 8;
            case CRITICAL -> 0;
        };
        if (points <= 0) return;
        Particle.DustOptions dust = new Particle.DustOptions(color(behavior), 0.9F);
        for (Player receiver : receivers(center)) {
            int visible = VisualMode.forPlayer(receiver).particles(points);
            for (int i = 0; i < visible; i++) {
                double angle = Math.PI * 2.0 * i / Math.max(1, visible);
                Location point = center.clone().add(Math.cos(angle) * radius, 0.1,
                        Math.sin(angle) * radius);
                receiver.spawnParticle(Particle.DUST, point, 1,
                        0.0, 0.0, 0.0, 0.0, dust);
            }
        }
    }

    void chain(Location from, Location to, int segment) {
        Vector delta = to.toVector().subtract(from.toVector());
        ServerLoadState load = load();
        int points = switch (load) {
            case NORMAL -> 12;
            case PRESSURE -> 5;
            case CRITICAL -> 0;
        };
        if (points <= 0) return;
        Vector step = delta.multiply(1.0 / points);
        for (Player receiver : receivers(from)) {
            int visible = VisualMode.forPlayer(receiver).particles(points);
            int stride = Math.max(1, (int) Math.ceil((double) points / Math.max(1, visible)));
            for (int i = 1; i <= points; i += stride) {
                Vector jitter = i == points ? new Vector() : new Vector(
                        Math.sin(i * 2.7 + segment) * 0.08,
                        Math.cos(i * 1.9 + segment) * 0.08,
                        Math.sin(i * 1.3 - segment) * 0.08);
                receiver.spawnParticle(Particle.ELECTRIC_SPARK,
                        from.clone().add(step.clone().multiply(i)).add(jitter), 1,
                        0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private List<Player> receivers(Location center) {
        return center.getWorld().getNearbyPlayers(center, receiverRadius).stream()
                .limit(maximumReceivers).toList();
    }

    private void play(
            Player receiver, Location location, Sound sound, float volume, float pitch,
            ServerLoadState load
    ) {
        if (load == ServerLoadState.CRITICAL) volume *= 0.7F;
        float adjusted = VisualMode.forPlayer(receiver).volume(volume);
        if (adjusted > 0.0F) receiver.playSound(location, sound, SoundCategory.HOSTILE, adjusted, pitch);
    }

    private Color color(MobBehavior behavior) {
        return switch (behavior) {
            case SKIRMISHER -> Color.fromRGB(105, 220, 226);
            case CHARGER -> Color.fromRGB(236, 92, 70);
            case SPITTER -> Color.fromRGB(112, 220, 105);
            case WARDEN -> Color.fromRGB(224, 184, 76);
            case CONDUCTOR -> Color.fromRGB(126, 162, 255);
            case BOOMERANG -> Color.fromRGB(190, 112, 238);
            case BURROWER -> Color.fromRGB(124, 86, 70);
            case TRAPPER -> Color.fromRGB(238, 92, 162);
            case ORBITER -> Color.fromRGB(114, 226, 240);
        };
    }

    private Sound cueSound(MobBehavior behavior) {
        return switch (behavior) {
            case SKIRMISHER -> Sound.ENTITY_ARROW_SHOOT;
            case CHARGER -> Sound.ENTITY_RAVAGER_ROAR;
            case SPITTER -> Sound.ENTITY_SPIDER_AMBIENT;
            case WARDEN -> Sound.ITEM_SHIELD_BLOCK;
            case CONDUCTOR -> Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR;
            case BOOMERANG -> Sound.ENTITY_PLAYER_ATTACK_SWEEP;
            case BURROWER -> Sound.ENTITY_ENDERMAN_TELEPORT;
            case TRAPPER -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE;
            case ORBITER -> Sound.ENTITY_ILLUSIONER_CAST_SPELL;
        };
    }

    private Sound impactSound(MobBehavior behavior) {
        return switch (behavior) {
            case SKIRMISHER -> Sound.ENTITY_ARROW_SHOOT;
            case CHARGER -> Sound.ENTITY_RAVAGER_ATTACK;
            case SPITTER -> Sound.ENTITY_SPLASH_POTION_BREAK;
            case WARDEN -> Sound.ITEM_SHIELD_BLOCK;
            case CONDUCTOR -> Sound.ENTITY_WARDEN_SONIC_BOOM;
            case BOOMERANG -> Sound.ENTITY_PLAYER_ATTACK_STRONG;
            case BURROWER -> Sound.ENTITY_ENDERMAN_TELEPORT;
            case TRAPPER -> Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE;
            case ORBITER -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE;
        };
    }

    private float cuePitch(MobBehavior behavior) {
        return behavior == MobBehavior.CHARGER ? 0.72F : behavior == MobBehavior.SPITTER ? 1.25F : 1.45F;
    }

    private float impactPitch(MobBehavior behavior) {
        return behavior == MobBehavior.CONDUCTOR ? 1.65F : behavior == MobBehavior.WARDEN ? 0.8F : 1.15F;
    }
}
