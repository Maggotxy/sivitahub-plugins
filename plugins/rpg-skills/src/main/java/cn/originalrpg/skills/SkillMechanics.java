package cn.originalrpg.skills;

import cn.originalrpg.api.service.SkillDamageService;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wolf;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Spatial and time-based skill mechanics which cannot be expressed by potion
 * effects alone. Every loop is receiver/target bounded and expires on its own;
 * no skill owns an idle per-tick world scan.
 */
final class SkillMechanics {
    private static final int MAX_AREA_TARGETS = 16;
    private static final int MAX_VENOM_MARKS = 256;
    private final SkillDamageService damage;
    private final SkillVisuals visuals;
    private final SkillSequenceController sequences;
    private final Map<UUID, VenomMark> venomMarks = new LinkedHashMap<>();
    private final NamespacedKey dungeonBoss = NamespacedKey.fromString("rpgdungeons:boss");

    SkillMechanics(
            SkillDamageService damage, SkillVisuals visuals,
            SkillSequenceController sequences
    ) {
        this.damage = damage;
        this.visuals = visuals;
        this.sequences = sequences;
    }

    void shutdown() {
        venomMarks.clear();
    }

    void stopOwner(UUID owner) {
        sequences.stopOwner(owner);
    }

    boolean shadowStep(Player caster, LivingEntity target, double amount, String skillId) {
        Location origin = caster.getLocation().clone();
        Location destination = behind(target, 1.65);
        if (!safe(destination)) destination = target.getLocation().clone().add(0.0, 0.1, 0.0);
        Vector facing = target.getEyeLocation().toVector().subtract(destination.toVector()).normalize();
        destination.setDirection(facing);
        if (!caster.teleport(destination)) return false;
        caster.setFallDistance(0.0F);
        damage.damage(caster, target, amount, skillId);
        double displacement = isBoss(target) ? 0.10 : 0.32;
        target.setVelocity(target.getVelocity().add(facing.clone().multiply(displacement)
                .setY(isBoss(target) ? 0.04 : 0.12)));
        visuals.path(caster, origin.add(0.0, 1.0, 0.0), destination.clone().add(0.0, 1.0, 0.0),
                Particle.REVERSE_PORTAL, Color.fromRGB(154, 75, 225), 24);
        visuals.impact(caster, target.getLocation().add(0.0, 1.0, 0.0), Particle.SWEEP_ATTACK, 5);
        return true;
    }

    void freeze(Player caster, List<? extends LivingEntity> input, ToIntFunction<LivingEntity> durationTicks) {
        List<? extends LivingEntity> targets = input.stream().filter(LivingEntity::isValid)
                .limit(MAX_AREA_TARGETS).toList();
        Map<LivingEntity, Integer> locks = new LinkedHashMap<>();
        for (LivingEntity target : targets) {
            int ticks = Math.clamp(durationTicks.applyAsInt(target), 10, 120);
            locks.put(target, ticks);
            target.setFreezeTicks(Math.min(target.getMaxFreezeTicks(),
                    Math.max(target.getFreezeTicks(), ticks)));
            target.setVelocity(new Vector());
            if (target instanceof org.bukkit.entity.Mob mob) mob.setTarget(null);
        }
        int maximum = locks.values().stream().mapToInt(Integer::intValue).max().orElse(10);
        boolean started = sequences.startGameplay(caster, "freeze", 2, maximum, frame -> {
            boolean remaining = false;
            for (Map.Entry<LivingEntity, Integer> lock : locks.entrySet()) {
                LivingEntity target = lock.getKey();
                int ticks = lock.getValue();
                if (!target.isValid() || target.isDead() || frame.elapsedTicks() >= ticks) continue;
                remaining = true;
                target.setVelocity(new Vector());
                if (target instanceof org.bukkit.entity.Mob mob) mob.setTarget(null);
                target.setFreezeTicks(Math.min(target.getMaxFreezeTicks(),
                        Math.max(target.getFreezeTicks(), ticks - frame.elapsedTicks())));
                if (frame.quality() != SkillSequenceController.VisualQuality.MINIMAL
                        && frame.elapsedTicks() % 8 == 0) {
                    target.setVelocity(new Vector());
                    visuals.impact(caster,
                            target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0),
                            Particle.SNOWFLAKE,
                            frame.quality() == SkillSequenceController.VisualQuality.FULL ? 4 : 2);
                }
            }
            return remaining;
        });
        if (!started) {
            // This path is only reached at the independent gameplay hard cap.
            // Preserve a bounded control result without creating another task
            // or leaving AI disabled with no cleanup path.
            locks.forEach((target, ticks) -> {
                if (!target.isValid() || target.isDead()) return;
                int amplifier = isBoss(target) || target instanceof Player ? 4 : 10;
                target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                        org.bukkit.potion.PotionEffectType.SLOWNESS, ticks, amplifier,
                        false, false, true));
                target.setFreezeTicks(Math.min(target.getMaxFreezeTicks(),
                        Math.max(target.getFreezeTicks(), ticks)));
                target.setVelocity(new Vector());
            });
        }
    }

    void meteor(Player caster, double amount, String skillId) {
        Location center = aimedGround(caster, 32.0);
        visuals.ringAt(caster, center, 4.5, Particle.FLAME,
                Color.fromRGB(255, 78, 24), 32);
        caster.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                SoundCategory.PLAYERS, 0.8F, 0.62F);
        drawMeteorDescent(caster, center, 0);
        boolean started = sequences.startGameplay(caster, "meteor", 4, 20, frame -> {
            if (frame.elapsedTicks() >= 20) {
                meteorImpact(caster, center, amount, skillId);
                return false;
            }
            if (frame.quality() != SkillSequenceController.VisualQuality.MINIMAL) {
                drawMeteorDescent(caster, center, frame.elapsedTicks() / 4);
            }
            return true;
        });
        // The gameplay queue hard cap is deliberately remote. If it is ever
        // reached, settle once rather than accepting mana and losing damage.
        if (!started) meteorImpact(caster, center, amount, skillId);
    }

    private void drawMeteorDescent(Player caster, Location center, int pulse) {
        Location falling = center.clone().add(0.0, 12.0 - pulse * 2.5, 0.0);
        visuals.path(caster, falling.clone().add(0.0, 2.5, 0.0), falling,
                Particle.FLAME, Color.fromRGB(255, 120, 38), 12);
        visuals.ringAt(caster, center, Math.max(0.8, 4.5 - pulse * 0.65),
                Particle.SMALL_FLAME, Color.fromRGB(255, 70, 25), 20);
    }

    private void meteorImpact(Player caster, Location center, double amount, String skillId) {
        for (Monster target : nearby(caster, center, 5.0, MAX_AREA_TARGETS)) {
            damage.damage(caster, target, amount, skillId);
            Vector away = target.getLocation().toVector().subtract(center.toVector());
            if (away.lengthSquared() < 0.01) away = new Vector(0.0, 0.0, 1.0);
            double force = isBoss(target) ? 0.28 : 0.75;
            target.setVelocity(away.normalize().multiply(force).setY(isBoss(target) ? 0.14 : 0.5));
            target.setFireTicks(Math.max(target.getFireTicks(), 100));
        }
        visuals.impact(caster, center.clone().add(0.0, 0.8, 0.0), Particle.EXPLOSION, 3);
        caster.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE,
                SoundCategory.PLAYERS, 1.0F, 0.72F);
    }

    void charge(Player caster, double amount, String skillId, Color color) {
        Vector direction = caster.getEyeLocation().getDirection().setY(0.0);
        if (direction.lengthSquared() < 0.01) direction = new Vector(0.0, 0.0, 1.0);
        final Vector travelDirection = direction.normalize();
        Vector velocity = travelDirection.clone().multiply(1.35).setY(0.18);
        Set<UUID> hit = new HashSet<>();
        Location[] previous = {caster.getLocation().clone().add(0.0, 0.8, 0.0)};
        boolean started = sequences.startGameplay(caster, "charge", 1, 8, frame -> {
            caster.setVelocity(velocity);
            Location current = caster.getLocation().clone().add(0.0, 0.8, 0.0);
            if (frame.quality() != SkillSequenceController.VisualQuality.MINIMAL) {
                visuals.path(caster, previous[0], current, Particle.CRIT, color, 8);
            }
            previous[0] = current;
            strikeChargeTargets(caster, travelDirection, amount, skillId, hit);
            return frame.elapsedTicks() < 8;
        });
        if (!started) {
            caster.setVelocity(velocity.multiply(0.7));
            strikeChargeTargets(caster, travelDirection, amount, skillId, hit);
        }
    }

    void blinkChain(Player caster, List<? extends LivingEntity> input, double amount, String skillId) {
        List<? extends LivingEntity> targets = input.stream().filter(LivingEntity::isValid).limit(3).toList();
        if (targets.isEmpty()) return;
        int[] index = {0};
        shadowStep(caster, targets.getFirst(), amount, skillId);
        index[0] = 1;
        boolean started = sequences.startGameplay(
                caster, "blink-chain", 4, Math.max(4, targets.size() * 4), frame -> {
            while (index[0] < targets.size()) {
                LivingEntity target = targets.get(index[0]++);
                if (!target.isValid() || target.isDead()) continue;
                shadowStep(caster, target, amount, skillId);
                break;
            }
            return index[0] < targets.size();
        });
        if (!started) {
            while (index[0] < targets.size()) {
                LivingEntity target = targets.get(index[0]++);
                if (target.isValid() && !target.isDead()) shadowStep(caster, target, amount, skillId);
            }
        }
    }

    void smokeBomb(Player caster, double radius) {
        Location center = caster.getLocation();
        for (Monster monster : nearby(caster, center, radius, MAX_AREA_TARGETS)) {
            if (!isBoss(monster)) monster.setTarget(null);
            Vector away = monster.getLocation().toVector().subtract(center.toVector());
            if (away.lengthSquared() > 0.01) {
                double force = isBoss(monster) ? 0.14 : 0.38;
                monster.setVelocity(away.normalize().multiply(force).setY(isBoss(monster) ? 0.06 : 0.15));
            }
        }
        visuals.ringAt(caster, center, 1.5, Particle.LARGE_SMOKE,
                Color.fromRGB(82, 62, 112), 24);
        int[] pulse = {1};
        sequences.start(caster, "smoke-visual", 3, 12, frame -> {
            visuals.ringAt(caster, center, 1.5 + pulse[0] * 1.4, Particle.LARGE_SMOKE,
                    Color.fromRGB(82, 62, 112), 24);
            return ++pulse[0] < 4;
        });
    }

    void frontalWall(Player caster, double radius, double force) {
        Vector facing = caster.getEyeLocation().getDirection().setY(0.0);
        if (facing.lengthSquared() < 0.01) facing = new Vector(0.0, 0.0, 1.0);
        facing.normalize();
        for (Monster monster : nearby(caster, caster.getLocation(), radius, MAX_AREA_TARGETS)) {
            Vector delta = monster.getLocation().toVector().subtract(caster.getLocation().toVector()).setY(0.0);
            if (delta.lengthSquared() < 0.01 || delta.normalize().dot(facing) < 0.2) continue;
            double applied = isBoss(monster) ? force * 0.30 : force;
            monster.setVelocity(facing.clone().multiply(applied).setY(isBoss(monster) ? 0.09 : 0.25));
            monster.setTarget(caster);
        }
        Location left = caster.getLocation().add(facing.clone().rotateAroundY(Math.PI / 2).multiply(2.8))
                .add(0.0, 1.0, 0.0);
        Location right = caster.getLocation().add(facing.clone().rotateAroundY(-Math.PI / 2).multiply(2.8))
                .add(0.0, 1.0, 0.0);
        visuals.path(caster, left, right, Particle.WAX_ON, Color.fromRGB(220, 154, 74), 24);
    }

    void shockwave(Player caster, double radius, double force, double amount,
                   String skillId, Particle accent, Color color) {
        Location center = caster.getLocation();
        for (Monster monster : nearby(caster, center, radius, MAX_AREA_TARGETS)) {
            if (amount > 0.0) damage.damage(caster, monster, amount, skillId);
            Vector away = monster.getLocation().toVector().subtract(center.toVector());
            if (away.lengthSquared() < 0.01) away = new Vector(0.0, 0.0, 1.0);
            double applied = isBoss(monster) ? force * 0.35 : force;
            monster.setVelocity(away.normalize().multiply(applied).setY(isBoss(monster)
                    ? Math.min(0.16, 0.05 + applied * 0.20)
                    : Math.min(0.65, 0.2 + applied * 0.25)));
        }
        visuals.ringAt(caster, center, radius / 3.0, accent, color, 28);
        int[] pulse = {1};
        sequences.start(caster, "shockwave-visual", 2, 6, frame -> {
            visuals.ringAt(caster, center, radius * (pulse[0] + 1.0) / 3.0,
                    accent, color, 28);
            return ++pulse[0] < 3;
        });
    }

    void pullAndTaunt(Player caster, double radius) {
        for (Monster monster : nearby(caster, caster.getLocation(), radius, MAX_AREA_TARGETS)) {
            monster.setTarget(caster);
            Vector toward = caster.getLocation().toVector().subtract(monster.getLocation().toVector());
            if (toward.lengthSquared() > 0.01) {
                double force = isBoss(monster) ? 0.16 : 0.42;
                monster.setVelocity(toward.normalize().multiply(force).setY(isBoss(monster) ? 0.05 : 0.12));
            }
        }
        visuals.ringAt(caster, caster.getLocation(), radius, Particle.WAX_ON,
                Color.fromRGB(196, 126, 70), 32);
    }

    void piercingRay(Player caster, double range, double radius, int maximumTargets,
                     double amount, String skillId, Color color) {
        Location from = caster.getEyeLocation();
        Vector direction = from.getDirection().normalize();
        Location to = from.clone().add(direction.clone().multiply(range));
        Block hit = caster.getTargetBlockExact((int) Math.ceil(range), FluidCollisionMode.NEVER);
        if (hit != null) to = hit.getLocation().add(0.5, 0.5, 0.5);
        final Location end = to;
        Vector segment = end.toVector().subtract(from.toVector());
        double lengthSquared = segment.lengthSquared();
        List<Monster> targets = caster.getWorld().getNearbyEntitiesByType(
                        Monster.class, from.clone().add(segment.clone().multiply(0.5)), range * 0.55 + 1.0).stream()
                .filter(monster -> squaredDistanceToSegment(monster.getEyeLocation().toVector(),
                        from.toVector(), end.toVector(), lengthSquared) <= radius * radius)
                .sorted(Comparator.comparingDouble(monster -> monster.getLocation().distanceSquared(from)))
                .limit(Math.clamp(maximumTargets, 1, 12)).toList();
        targets.forEach(target -> damage.damage(caster, target, amount, skillId));
        visuals.path(caster, from, end, Particle.END_ROD, color, 32);
    }

    void volleyRain(Player caster, double amountMultiplier, String skillId) {
        Location center = aimedGround(caster, 28.0).add(0.0, 0.8, 0.0);
        visuals.ringAt(caster, center, 4.0, Particle.CRIT,
                Color.fromRGB(78, 214, 191), 28);
        int[] index = {0};
        launchVolleyArrow(caster, center, index[0]++, amountMultiplier, skillId, true);
        boolean started = sequences.startGameplay(caster, "volley", 2, 14, frame -> {
            if (index[0] >= 7) return false;
            launchVolleyArrow(caster, center, index[0]++, amountMultiplier, skillId,
                    frame.quality() != SkillSequenceController.VisualQuality.MINIMAL);
            return index[0] < 7;
        });
        if (!started) {
            while (index[0] < 7) {
                launchVolleyArrow(caster, center, index[0]++, amountMultiplier, skillId, false);
            }
        }
    }

    void beastSpirits(
            Player caster, double durationSeconds, double amount, String skillId, int spiritCount
    ) {
        Monster target = nearby(caster, caster.getLocation(), 16.0, 1).stream().findFirst().orElse(null);
        int count = Math.clamp(spiritCount, 1, 3);
        List<Wolf> wolves = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2.0 * index / count;
            Location spawn = caster.getLocation().add(Math.cos(angle) * 1.2, 0.1, Math.sin(angle) * 1.2);
            Wolf wolf = caster.getWorld().spawn(spawn, Wolf.class, spirit -> {
                spirit.setTamed(true);
                spirit.setOwner(caster);
                spirit.setPersistent(false);
                spirit.setRemoveWhenFarAway(true);
                spirit.setInvulnerable(true);
                spirit.setSilent(true);
                spirit.customName(net.kyori.adventure.text.Component.text("兽灵"));
                spirit.setCustomNameVisible(false);
                var attack = spirit.getAttribute(Attribute.ATTACK_DAMAGE);
                if (attack != null) attack.setBaseValue(0.0);
                if (target != null) spirit.setTarget(target);
            });
            wolves.add(wolf);
            visuals.impact(caster, spawn.add(0.0, 0.8, 0.0), Particle.SOUL, 8);
        }
        int maximumTicks = Math.clamp((int) Math.round(durationSeconds * 20.0), 40, 400);
        Runnable finish = () -> wolves.forEach(wolf -> {
            if (!wolf.isValid()) return;
            visuals.impact(caster, wolf.getLocation().add(0.0, 0.8, 0.0), Particle.SOUL, 5);
            wolf.remove();
        });
        boolean started = sequences.startGameplay(caster, "beast-spirits", 10, maximumTicks, frame -> {
            if (!caster.isOnline() || caster.isDead()) return false;
            List<Monster> prey = nearby(caster, caster.getLocation(), 18.0, 8);
            Set<UUID> struck = new HashSet<>();
            for (int index = 0; index < wolves.size(); index++) {
                Wolf wolf = wolves.get(index);
                if (!wolf.isValid()) continue;
                if (!wolf.getWorld().equals(caster.getWorld())
                        || wolf.getLocation().distanceSquared(caster.getLocation()) > 24.0 * 24.0) {
                    wolf.teleport(caster.getLocation().add((index - 1) * 1.1, 0.1, 0.0));
                }
                Monster preyTarget = prey.isEmpty() ? null : prey.get(index % prey.size());
                if (preyTarget == null || !preyTarget.isValid()) continue;
                wolf.setTarget(preyTarget);
                if (wolf.getLocation().distanceSquared(preyTarget.getLocation()) > 3.4 * 3.4
                        || !struck.add(preyTarget.getUniqueId())) continue;
                damage.damage(caster, preyTarget, amount, skillId);
                Vector lunge = preyTarget.getLocation().toVector().subtract(wolf.getLocation().toVector());
                if (lunge.lengthSquared() > 0.01) wolf.setVelocity(lunge.normalize().multiply(0.42).setY(0.16));
                if (frame.quality() != SkillSequenceController.VisualQuality.MINIMAL) {
                    visuals.path(caster, wolf.getEyeLocation(), preyTarget.getEyeLocation(),
                            Particle.SOUL, Color.fromRGB(92, 214, 167),
                            frame.quality() == SkillSequenceController.VisualQuality.FULL ? 12 : 6);
                    visuals.impact(caster, preyTarget.getLocation().add(0.0, 0.8, 0.0),
                            Particle.SWEEP_ATTACK, 2);
                }
            }
            return wolves.stream().anyMatch(Wolf::isValid);
        }, finish);
        if (!started) {
            List<Monster> fallbackTargets = nearby(caster, caster.getLocation(), 18.0, count);
            fallbackTargets.forEach(prey -> damage.damage(caster, prey, amount, skillId));
            finish.run();
        }
    }

    void damageZone(Player caster, Location center, double radius, int pulses, long intervalTicks,
                    double damagePerPulse, String skillId, Particle accent, Color color, boolean ignite) {
        int boundedPulses = Math.clamp(pulses, 1, 10);
        int interval = (int) Math.clamp(intervalTicks, 4L, 40L);
        if (boundedPulses == 1) {
            damagePulse(caster, center, radius, damagePerPulse, skillId, accent, color, ignite, true);
            return;
        }
        int[] completed = {1};
        boolean started = sequences.startGameplay(
                caster, "zone-" + skillId, interval, boundedPulses * interval, frame -> {
            boolean fullVisual = frame.quality() == SkillSequenceController.VisualQuality.FULL;
            damagePulse(caster, center, radius, damagePerPulse, skillId,
                    accent, color, ignite, fullVisual);
            return ++completed[0] < boundedPulses;
        });
        if (started) {
            damagePulse(caster, center, radius, damagePerPulse, skillId, accent, color, ignite, true);
        } else {
            damagePulse(caster, center, radius, damagePerPulse * boundedPulses,
                    skillId, accent, color, ignite, true);
        }
    }

    void healingPulses(Player caster, Supplier<Set<Player>> allies, double radius,
                       int pulses, long intervalTicks, double amount, Color color) {
        Location anchor = caster.getLocation().clone();
        int boundedPulses = Math.clamp(pulses, 1, 10);
        int interval = (int) Math.clamp(intervalTicks, 5L, 40L);
        if (boundedPulses == 1) {
            healingPulse(caster, allies, anchor, radius, amount, color, true);
            return;
        }
        int[] completed = {1};
        boolean started = sequences.startGameplay(
                caster, "healing-pulses", interval, boundedPulses * interval, frame -> {
            if (!caster.getWorld().equals(anchor.getWorld())) return false;
            healingPulse(caster, allies, anchor, radius, amount, color,
                    frame.quality() == SkillSequenceController.VisualQuality.FULL);
            return ++completed[0] < boundedPulses;
        });
        healingPulse(caster, allies, anchor, radius,
                started ? amount : amount * boundedPulses, color, true);
    }

    void rallyingStandard(Player caster, Supplier<Set<Player>> allies, double radius, int pulses) {
        Location standard = caster.getLocation().clone();
        int bounded = Math.clamp(pulses, 2, 8);
        int[] completed = {0};
        Runnable pulse = () -> {
            if (!caster.getWorld().equals(standard.getWorld())) return;
            visuals.ringAt(caster, standard, radius, Particle.WAX_ON,
                    Color.fromRGB(232, 166, 68), 28);
            allies.get().stream().filter(ally -> validAt(standard, ally, radius)).limit(8)
                    .forEach(ally -> ally.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.ABSORPTION, 45, 0)));
            for (Monster monster : nearby(caster, standard, radius, MAX_AREA_TARGETS)) {
                monster.setTarget(caster);
            }
            completed[0]++;
        };
        pulse.run();
        boolean started = sequences.startGameplay(caster, "rallying-standard", 20, bounded * 20, frame -> {
            pulse.run();
            return completed[0] < bounded;
        });
        if (!started) {
            allies.get().stream().filter(ally -> validAt(standard, ally, radius)).limit(8)
                    .forEach(ally -> ally.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.ABSORPTION, bounded * 20 + 5, 0)));
        }
    }

    void reverseProjectiles(Player caster, double radius) {
        for (Projectile projectile : caster.getWorld().getNearbyEntitiesByType(
                Projectile.class, caster.getLocation(), radius)) {
            if (projectile.getShooter() == caster || projectile.isDead()) continue;
            projectile.setVelocity(projectile.getVelocity().multiply(-1.15));
            projectile.setShooter(caster);
            visuals.path(caster, projectile.getLocation(), caster.getEyeLocation(),
                    Particle.REVERSE_PORTAL, Color.fromRGB(94, 220, 220), 12);
        }
    }

    void trailProjectile(Player caster, Projectile projectile, Particle accent,
                         Color color, int maximumTicks) {
        Location[] previous = {projectile.getLocation().clone()};
        sequences.start(caster, "trail-" + projectile.getUniqueId(), 2,
                Math.clamp(maximumTicks, 6, 80), frame -> {
            if (!projectile.isValid() || projectile.isDead()) return false;
            Location current = projectile.getLocation().clone();
            if (frame.quality() != SkillSequenceController.VisualQuality.MINIMAL) {
                visuals.path(caster, previous[0], current, accent, color, 8);
            }
            previous[0] = current;
            return true;
        });
    }

    void timeLock(Player caster, List<? extends LivingEntity> targets, ToIntFunction<LivingEntity> durationTicks,
                  double radius) {
        freeze(caster, targets, durationTicks);
        Map<Projectile, Vector> velocities = new LinkedHashMap<>();
        for (Projectile projectile : caster.getWorld().getNearbyEntitiesByType(
                Projectile.class, caster.getLocation(), radius)) {
            if (projectile.isDead()) continue;
            velocities.put(projectile, projectile.getVelocity().clone());
            projectile.setVelocity(new Vector());
        }
        int lockTicks = targets.stream().mapToInt(durationTicks).max().orElse(40);
        if (!velocities.isEmpty()) {
            Runnable restore = () -> velocities.forEach((projectile, velocity) -> {
                if (projectile.isValid() && !projectile.isDead()) projectile.setVelocity(velocity);
            });
            boolean started = sequences.startGameplay(
                    caster, "time-projectiles", 2, lockTicks, frame -> {
                        velocities.keySet().stream().filter(Projectile::isValid)
                                .forEach(projectile -> projectile.setVelocity(new Vector()));
                        return frame.elapsedTicks() < lockTicks;
                    }, restore);
            if (!started) restore.run();
        }
        visuals.ringAt(caster, caster.getLocation(), radius, Particle.REVERSE_PORTAL,
                Color.fromRGB(105, 205, 255), 36);
    }

    /** A mobile seed follows the most wounded ally and renews nearby allies around them. */
    void renewalBeacon(Player caster, Supplier<Set<Player>> allies, double amount) {
        Player[] bearer = {mostWounded(allies.get(), caster)};
        int[] pulses = {0};
        Runnable pulse = () -> {
            Set<Player> candidates = allies.get();
            if (!validAlly(caster, bearer[0], 10.0)) bearer[0] = mostWounded(candidates, caster);
            Player target = bearer[0];
            if (target == null) return;
            heal(target, amount);
            candidates.stream().filter(ally -> validAlly(target, ally, 3.5))
                    .filter(ally -> !ally.equals(target)).limit(3)
                    .forEach(ally -> heal(ally, amount * 0.45));
            visuals.path(caster, caster.getEyeLocation(), target.getEyeLocation(),
                    Particle.HAPPY_VILLAGER, Color.fromRGB(90, 235, 165), 14);
            visuals.ringAt(caster, target.getLocation(), 3.5, Particle.HEART,
                    Color.fromRGB(90, 235, 165), 20);
        };
        pulse.run();
        pulses[0] = 1;
        boolean started = sequences.startGameplay(caster, "renewal-beacon", 20, 80, frame -> {
            pulse.run();
            return ++pulses[0] < 4;
        });
        if (!started) {
            Set<Player> candidates = allies.get();
            Player target = mostWounded(candidates, caster);
            if (target != null) {
                heal(target, amount * 3.0);
                candidates.stream().filter(ally -> validAlly(target, ally, 3.5))
                        .filter(ally -> !ally.equals(target)).limit(3)
                        .forEach(ally -> heal(ally, amount * 1.35));
            }
        }
    }

    /** Fixed holy ground heals allies and repeatedly bends hostile shots around its edge. */
    void sanctuary(Player caster, Supplier<Set<Player>> allies, double amount) {
        Location anchor = caster.getLocation().clone();
        int[] pulses = {0};
        Runnable pulse = () -> {
            if (!caster.getWorld().equals(anchor.getWorld())) return;
            Set<Player> party = allies.get();
            party.stream().filter(ally -> validAt(anchor, ally, 10.0)).limit(8)
                    .forEach(ally -> heal(ally, amount));
            for (Projectile projectile : anchor.getWorld().getNearbyEntitiesByType(
                    Projectile.class, anchor, 10.0)) {
                if (!projectile.isValid() || projectile.getShooter() == caster
                        || projectile.getShooter() instanceof Player shooter && party.contains(shooter)) continue;
                Vector away = projectile.getLocation().toVector().subtract(anchor.toVector());
                if (away.lengthSquared() < 0.01) away = new Vector(0.0, 0.35, 1.0);
                projectile.setVelocity(away.normalize().multiply(
                        Math.max(0.8, projectile.getVelocity().length())));
                projectile.setShooter(caster);
            }
            for (Monster monster : nearby(caster, anchor, 10.0, MAX_AREA_TARGETS)) {
                double distance = monster.getLocation().distanceSquared(anchor);
                if (distance < 7.0 * 7.0) continue;
                Vector away = monster.getLocation().toVector().subtract(anchor.toVector());
                if (away.lengthSquared() > 0.01) {
                    double force = isBoss(monster) ? 0.13 : 0.38;
                    monster.setVelocity(away.normalize().multiply(force)
                            .setY(isBoss(monster) ? 0.04 : 0.12));
                }
            }
            visuals.ringAt(caster, anchor, 10.0, Particle.END_ROD,
                    Color.fromRGB(110, 250, 190), 32);
        };
        pulse.run();
        pulses[0] = 1;
        boolean started = sequences.startGameplay(caster, "sanctuary", 20, 120, frame -> {
            pulse.run();
            return ++pulses[0] < 6;
        });
        if (!started) {
            allies.get().stream().filter(ally -> validAt(anchor, ally, 10.0)).limit(8)
                    .forEach(ally -> heal(ally, amount * 5.0));
        }
    }

    /** A persistent tether converts a bounded part of the caster's health into rescue healing. */
    void lifeThread(Player caster, Supplier<Set<Player>> allies, double amount) {
        Player[] linked = {mostWounded(allies.get(), caster)};
        int[] pulses = {0};
        Runnable pulse = () -> {
            if (!validAlly(caster, linked[0], 12.0)) linked[0] = mostWounded(allies.get(), caster);
            Player target = linked[0];
            if (target == null) return;
            double transfer = 0.0;
            if (!target.equals(caster)) {
                double maximum = maximumHealth(target);
                transfer = Math.min(1.25, Math.min(Math.max(0.0, caster.getHealth() - 1.0),
                        Math.max(0.0, maximum - target.getHealth())));
                if (transfer > 0.0) caster.setHealth(caster.getHealth() - transfer);
            }
            heal(target, amount + transfer);
            heal(caster, amount * 0.28);
            visuals.path(caster, caster.getEyeLocation(), target.getEyeLocation(),
                    Particle.TOTEM_OF_UNDYING, Color.fromRGB(94, 244, 180), 20);
        };
        pulse.run();
        pulses[0] = 1;
        boolean started = sequences.startGameplay(caster, "life-thread", 16, 80, frame -> {
            pulse.run();
            return ++pulses[0] < 5;
        });
        if (!started) {
            Player target = mostWounded(allies.get(), caster);
            if (target != null) {
                double transfer = target.equals(caster) ? 0.0 : Math.min(5.0,
                        Math.min(Math.max(0.0, caster.getHealth() - 1.0),
                                Math.max(0.0, maximumHealth(target) - target.getHealth())));
                if (transfer > 0.0) caster.setHealth(caster.getHealth() - transfer);
                heal(target, amount * 4.0 + transfer);
                heal(caster, amount * 1.12);
            }
        }
    }

    /** Healing echoes from allies into nearby enemies, creating an offensive support cadence. */
    void soulResonance(
            Player caster, Supplier<Set<Player>> allies, double healing,
            double echoDamage, String skillId
    ) {
        int[] pulses = {0};
        Runnable pulse = () -> {
            Set<UUID> struck = new HashSet<>();
            for (Player ally : allies.get().stream().filter(
                    candidate -> validAlly(caster, candidate, 14.0)).limit(8).toList()) {
                heal(ally, healing);
                Monster echo = ally.getWorld().getNearbyEntitiesByType(
                                Monster.class, ally.getLocation(), 4.5).stream()
                        .filter(monster -> struck.add(monster.getUniqueId()))
                        .min(Comparator.comparingDouble(monster -> monster.getLocation()
                                .distanceSquared(ally.getLocation()))).orElse(null);
                if (echo == null) continue;
                damage.damage(caster, echo, echoDamage, skillId);
                visuals.path(caster, ally.getEyeLocation(), echo.getEyeLocation(),
                        Particle.SOUL, Color.fromRGB(122, 238, 220), 14);
            }
            visuals.ringAt(caster, caster.getLocation(), 14.0, Particle.TOTEM_OF_UNDYING,
                    Color.fromRGB(122, 238, 220), 28);
        };
        pulse.run();
        pulses[0] = 1;
        boolean started = sequences.startGameplay(caster, "soul-resonance", 14, 84, frame -> {
            pulse.run();
            return ++pulses[0] < 6;
        });
        if (!started) {
            Set<UUID> struck = new HashSet<>();
            for (Player ally : allies.get().stream().filter(
                    candidate -> validAlly(caster, candidate, 14.0)).limit(8).toList()) {
                heal(ally, healing * 5.0);
                Monster echo = ally.getWorld().getNearbyEntitiesByType(
                                Monster.class, ally.getLocation(), 4.5).stream()
                        .filter(monster -> struck.add(monster.getUniqueId()))
                        .min(Comparator.comparingDouble(monster -> monster.getLocation()
                                .distanceSquared(ally.getLocation()))).orElse(null);
                if (echo != null) damage.damage(caster, echo, echoDamage * 5.0, skillId);
            }
        }
    }

    int venomBlade(Player caster, LivingEntity target, double amount, String skillId) {
        cleanupVenomMarks();
        int previous = venomMarks.getOrDefault(target.getUniqueId(), new VenomMark(0, 0L)).stacks();
        int maximum = isBoss(target) ? 3 : 5;
        int stacks = Math.min(maximum, previous + 1);
        rememberVenom(target, stacks);
        shadowStep(caster, target, amount + previous * 1.25, skillId);
        visuals.ringAt(caster, target.getLocation(), 0.9 + stacks * 0.12,
                Particle.ITEM_SLIME, Color.fromRGB(112, 225, 62), 10 + stacks * 2);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_SPIDER_HURT,
                SoundCategory.PLAYERS, 0.45F, 0.8F + stacks * 0.08F);
        return stacks;
    }

    void toxicBurst(Player caster, double radius, double amount, String skillId) {
        cleanupVenomMarks();
        List<Monster> targets = nearby(caster, caster.getLocation(), radius, MAX_AREA_TARGETS);
        Set<Monster> spread = new HashSet<>();
        for (Monster target : targets) {
            VenomMark mark = venomMarks.remove(target.getUniqueId());
            int stacks = mark == null ? 0 : mark.stacks();
            damage.damage(caster, target, amount + stacks * 2.25, skillId);
            if (stacks > 0) target.getWorld().getNearbyEntitiesByType(
                            Monster.class, target.getLocation(), 3.5).stream()
                    .filter(other -> !other.equals(target)).limit(Math.min(3, stacks))
                    .forEach(spread::add);
            visuals.impact(caster, target.getLocation().add(0.0, target.getHeight() * 0.5, 0.0),
                    Particle.ITEM_SLIME, 4 + stacks * 2);
        }
        spread.forEach(target -> rememberVenom(target, 1));
        visuals.ringAt(caster, caster.getLocation(), radius, Particle.WITCH,
                Color.fromRGB(105, 215, 66), 32);
    }

    void plagueBloom(
            Player caster, Location center, double radius, int pulses,
            double amount, String skillId
    ) {
        int bounded = Math.clamp(pulses, 2, 8);
        int[] completed = {0};
        Runnable pulse = () -> {
            cleanupVenomMarks();
            boolean finalPulse = completed[0] == bounded - 1;
            for (Monster target : nearby(caster, center, radius, MAX_AREA_TARGETS)) {
                VenomMark current = venomMarks.get(target.getUniqueId());
                int stacks = Math.min(isBoss(target) ? 3 : 5,
                        (current == null ? 0 : current.stacks()) + 1);
                if (finalPulse) {
                    venomMarks.remove(target.getUniqueId());
                    damage.damage(caster, target, amount + stacks * 1.6, skillId);
                    visuals.impact(caster, target.getLocation().add(0.0, 0.8, 0.0),
                            Particle.SPORE_BLOSSOM_AIR, 5 + stacks);
                } else {
                    rememberVenom(target, stacks);
                    damage.damage(caster, target, amount, skillId);
                }
            }
            visuals.ringAt(caster, center, radius, Particle.SPORE_BLOSSOM_AIR,
                    Color.fromRGB(122, 192, 58), 28);
            completed[0]++;
        };
        pulse.run();
        boolean started = sequences.startGameplay(caster, "plague-bloom", 12, bounded * 12, frame -> {
            pulse.run();
            return completed[0] < bounded;
        });
        if (!started) {
            cleanupVenomMarks();
            for (Monster target : nearby(caster, center, radius, MAX_AREA_TARGETS)) {
                VenomMark current = venomMarks.remove(target.getUniqueId());
                int maximum = isBoss(target) ? 3 : 5;
                int finalStacks = Math.min(maximum,
                        (current == null ? 0 : current.stacks()) + bounded - 1);
                double remainingDamage = amount * (bounded - 1) + finalStacks * 1.6;
                damage.damage(caster, target, remainingDamage, skillId);
                visuals.impact(caster, target.getLocation().add(0.0, 0.8, 0.0),
                        Particle.SPORE_BLOSSOM_AIR, 5 + finalStacks);
            }
        }
    }

    private void strikeChargeTargets(
            Player caster, Vector travelDirection, double amount, String skillId, Set<UUID> hit
    ) {
        for (Monster target : nearby(caster, caster.getLocation(), 2.2, 8)) {
            if (!hit.add(target.getUniqueId())) continue;
            damage.damage(caster, target, amount, skillId);
            double force = isBoss(target) ? 0.35 : 1.0;
            target.setVelocity(travelDirection.clone().multiply(force).setY(isBoss(target) ? 0.12 : 0.48));
        }
    }

    private void launchVolleyArrow(
            Player caster, Location center, int index, double amountMultiplier,
            String skillId, boolean drawTrail
    ) {
        double angle = index * 2.399963;
        double radius = 0.7 + (index % 3) * 1.15;
        Location spawn = center.clone().add(Math.cos(angle) * radius, 8.0 + index * 0.25,
                Math.sin(angle) * radius);
        caster.getWorld().spawn(spawn, Arrow.class, projectile -> {
            projectile.setShooter(caster);
            projectile.setVelocity(new Vector(0.0, -2.7, 0.0));
            projectile.setDamage(projectile.getDamage() * amountMultiplier);
            projectile.setCritical(true);
            damage.tagProjectile(projectile, skillId, 1.0);
        });
        if (drawTrail) visuals.path(caster, spawn, center, Particle.CRIT,
                Color.fromRGB(92, 225, 205), 14);
    }

    private void damagePulse(
            Player caster, Location center, double radius, double amount, String skillId,
            Particle accent, Color color, boolean ignite, boolean visual
    ) {
        if (visual) visuals.ringAt(caster, center, radius, accent, color, 28);
        for (Monster target : nearby(caster, center, radius, MAX_AREA_TARGETS)) {
            damage.damage(caster, target, amount, skillId);
            if (ignite) target.setFireTicks(Math.max(target.getFireTicks(), 50));
        }
    }

    private void healingPulse(
            Player caster, Supplier<Set<Player>> allies, Location anchor, double radius,
            double amount, Color color, boolean visual
    ) {
        if (!caster.isOnline() || !caster.getWorld().equals(anchor.getWorld())) return;
        if (visual) visuals.ringAt(caster, anchor, radius, Particle.HEART, color, 28);
        for (Player ally : allies.get()) {
            if (!validAt(anchor, ally, radius)) continue;
            heal(ally, amount);
            if (visual) visuals.path(caster, anchor.clone().add(0.0, 0.4, 0.0),
                    ally.getLocation().add(0.0, 1.0, 0.0), Particle.HEART, color, 14);
        }
    }

    private Player mostWounded(Set<Player> candidates, Player fallback) {
        return candidates.stream().filter(Player::isOnline).filter(player -> !player.isDead())
                .min(Comparator.comparingDouble(player -> player.getHealth()
                        / Math.max(1.0, maximumHealth(player)))).orElse(fallback);
    }

    private boolean validAlly(Player anchor, Player ally, double radius) {
        return ally != null && ally.isOnline() && !ally.isDead()
                && ally.getWorld().equals(anchor.getWorld())
                && ally.getLocation().distanceSquared(anchor.getLocation()) <= radius * radius;
    }

    private boolean validAt(Location anchor, Player ally, double radius) {
        return ally != null && ally.isOnline() && !ally.isDead()
                && ally.getWorld().equals(anchor.getWorld())
                && ally.getLocation().distanceSquared(anchor) <= radius * radius;
    }

    private void cleanupVenomMarks() {
        long now = System.currentTimeMillis();
        venomMarks.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private void rememberVenom(LivingEntity target, int stacks) {
        if (!venomMarks.containsKey(target.getUniqueId()) && venomMarks.size() >= MAX_VENOM_MARKS) {
            var iterator = venomMarks.keySet().iterator();
            if (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
        venomMarks.put(target.getUniqueId(), new VenomMark(stacks,
                System.currentTimeMillis() + 12_000L));
    }

    private boolean isBoss(LivingEntity target) {
        return target instanceof org.bukkit.entity.Boss
                || dungeonBoss != null && target.getPersistentDataContainer()
                        .has(dungeonBoss, PersistentDataType.BYTE);
    }

    private static double maximumHealth(Player player) {
        var maximum = player.getAttribute(Attribute.MAX_HEALTH);
        return maximum == null ? 20.0 : maximum.getValue();
    }

    private Location aimedGround(Player caster, double range) {
        Block target = caster.getTargetBlockExact((int) Math.ceil(range), FluidCollisionMode.NEVER);
        if (target != null) return target.getLocation().add(0.5, 1.0, 0.5);
        return caster.getEyeLocation().add(caster.getEyeLocation().getDirection().normalize().multiply(range * 0.65));
    }

    private List<Monster> nearby(Player caster, Location center, double radius, int maximum) {
        return center.getWorld().getNearbyEntitiesByType(Monster.class, center, radius).stream()
                .filter(monster -> monster.isValid() && !monster.isDead())
                .sorted(Comparator.comparingDouble(monster -> monster.getLocation().distanceSquared(center)))
                .limit(Math.clamp(maximum, 1, MAX_AREA_TARGETS)).toList();
    }

    private Location behind(LivingEntity target, double distance) {
        Vector facing = target.getLocation().getDirection().setY(0.0);
        if (facing.lengthSquared() < 0.01) facing = new Vector(0.0, 0.0, 1.0);
        return target.getLocation().clone().subtract(facing.normalize().multiply(distance)).add(0.0, 0.1, 0.0);
    }

    private boolean safe(Location location) {
        return location.getBlock().isPassable()
                && location.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()
                && location.clone().subtract(0.0, 1.0, 0.0).getBlock().getType().isSolid();
    }

    private static double squaredDistanceToSegment(
            Vector point, Vector start, Vector end, double segmentLengthSquared
    ) {
        if (segmentLengthSquared <= 1.0E-9) return point.distanceSquared(start);
        Vector segment = end.clone().subtract(start);
        double t = Math.clamp(point.clone().subtract(start).dot(segment) / segmentLengthSquared, 0.0, 1.0);
        Vector projection = start.clone().add(segment.multiply(t));
        return point.distanceSquared(projection);
    }

    private static void heal(Player target, double amount) {
        org.bukkit.attribute.AttributeInstance maximum = target.getAttribute(
                org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (maximum == null || target.isDead() || amount <= 0.0) return;
        target.setHealth(Math.min(maximum.getValue(), target.getHealth() + amount));
    }

    private record VenomMark(int stacks, long expiresAt) {
    }
}
