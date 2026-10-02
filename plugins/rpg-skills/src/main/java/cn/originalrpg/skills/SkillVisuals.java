package cn.originalrpg.skills;

import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

/** Receiver-bounded skill telegraphs which degrade without changing gameplay. */
final class SkillVisuals {
    private static final Set<String> HEALER = Set.of(
            "heal", "renewal", "sanctuary", "fate-seal",
            "life-thread", "omen-reversal", "soul-resonance");
    private static final Set<String> WARRIOR = Set.of(
            "war-cry", "shield-wall", "fortress", "blood-rage",
            "rallying-standard", "ruin-charge", "unbroken-line");
    private static final Set<String> ASSASSIN = Set.of(
            "shadow-step", "venom-blade", "smoke-bomb", "toxic-burst",
            "void-cut", "plague-bloom", "vanished-horizon");
    private static final Set<String> MAGE = Set.of(
            "fireball", "frost-nova", "meteor", "mana-surge",
            "ash-domain", "time-lock", "axiomatic-break");
    private static final Set<String> RANGER = Set.of(
            "power-shot", "volley", "piercing-shot", "beast-call",
            "gale-step", "pack-hunt", "horizon-arrow");
    private static final Set<String> FREEFORM = Set.of(
            "improvisation", "second-wind", "battle-trance", "arcane-edge",
            "adaptive-arsenal", "rune-reversal", "open-form");
    private final Plugin plugin;
    private final SkillSequenceController sequences;

    SkillVisuals(Plugin plugin, SkillSequenceController sequences) {
        this.plugin = plugin;
        this.sequences = sequences;
    }

    void play(Player caster, String skillId) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        ServerLoadState state = state();
        int configured = switch (state) {
            case NORMAL -> Math.clamp(plugin.getConfig().getInt("visual-effects.particles.normal", 20), 4, 48);
            case PRESSURE -> Math.clamp(plugin.getConfig().getInt("visual-effects.particles.pressure", 8), 2, 20);
            case CRITICAL -> Math.clamp(plugin.getConfig().getInt("visual-effects.particles.critical", 2), 0, 6);
        };
        Visual visual = visual(skillId);
        int pulses = state == ServerLoadState.NORMAL ? 3 : state == ServerLoadState.PRESSURE ? 2 : 1;
        drawCastPulse(caster, visual, configured, pulses, 0, state);
        if (pulses <= 1) return;
        int[] pulse = {1};
        sequences.start(caster, "cast-visual", 3, pulses * 3, frame -> {
            drawCastPulse(caster, visual, configured, pulses, pulse[0], state);
            return ++pulse[0] < pulses;
        });
    }

    private void drawCastPulse(
            Player caster, Visual visual, int configured, int pulses,
            int current, ServerLoadState state
    ) {
        if (!caster.isOnline()) return;
        Location center = caster.getLocation().add(0.0, 1.0, 0.0);
        LinkedHashSet<Player> receivers = new LinkedHashSet<>(
                caster.getWorld().getNearbyPlayers(center, receiverRadius()));
        receivers.add(caster);
        for (Player receiver : receivers) {
            VisualMode mode = VisualMode.forPlayer(receiver);
            int pulseCount = Math.max(1, configured * (pulses - current) / pulses);
            int count = mode.particles(pulseCount);
            if (count > 0) draw(receiver, caster, center, visual, count, state);
            if (current != 0) continue;
            float volume = mode.volume(plugin.getConfig().getDouble(
                    "visual-effects.sound-volume", 0.65));
            if (volume > 0.0F) receiver.playSound(center, visual.sound(), SoundCategory.PLAYERS,
                    volume, visual.pitch());
        }
    }

    /** Draws a bounded semantic path, used for tethers, hitscan cuts and charges. */
    void path(Player caster, Location from, Location to, Particle accent, Color color, int normalPoints) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)
                || !from.getWorld().equals(to.getWorld())) return;
        ServerLoadState state = state();
        int loadPoints = switch (state) {
            case NORMAL -> Math.clamp(normalPoints, 4, 32);
            case PRESSURE -> Math.clamp(normalPoints / 2, 3, 14);
            case CRITICAL -> Math.clamp(normalPoints / 5, 0, 4);
        };
        if (loadPoints <= 0) return;
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        if (length <= 0.01) return;
        int points = Math.min(loadPoints, Math.max(2, (int) Math.ceil(length * 2.0)));
        Location midpoint = from.clone().add(delta.clone().multiply(0.5));
        Vector step = delta.clone().multiply(1.0 / points);
        LinkedHashSet<Player> receivers = receivers(caster, midpoint);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.05F);
        for (Player receiver : receivers) {
            int visible = VisualMode.forPlayer(receiver).particles(points);
            if (visible <= 0) continue;
            int stride = Math.max(1, points / visible);
            for (int index = 0; index <= points; index += stride) {
                Location point = from.clone().add(step.clone().multiply(index));
                receiver.spawnParticle(index % 3 == 0 ? accent : Particle.DUST,
                        point, 1, 0.01, 0.01, 0.01, 0.0,
                        index % 3 == 0 ? null : dust);
            }
        }
    }

    /** Draws a world-space telegraph without creating display entities. */
    void ringAt(Player caster, Location center, double radius, Particle accent, Color color, int normalPoints) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        ServerLoadState state = state();
        int points = switch (state) {
            case NORMAL -> Math.clamp(normalPoints, 8, 36);
            case PRESSURE -> Math.clamp(normalPoints / 2, 6, 16);
            case CRITICAL -> Math.clamp(normalPoints / 5, 0, 5);
        };
        if (points <= 0) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.05F);
        for (Player receiver : receivers(caster, center)) {
            int visible = VisualMode.forPlayer(receiver).particles(points);
            if (visible <= 0) continue;
            for (int index = 0; index < visible; index++) {
                double angle = Math.PI * 2.0 * index / visible;
                Location point = center.clone().add(Math.cos(angle) * radius, 0.08,
                        Math.sin(angle) * radius);
                receiver.spawnParticle(index % 4 == 0 ? accent : Particle.DUST,
                        point, 1, 0.01, 0.02, 0.01, 0.0,
                        index % 4 == 0 ? null : dust);
            }
        }
    }

    /** A bounded, soundless impact cue used by skills whose target is away from the caster. */
    void impact(Player caster, Location impact, Particle particle, int normalCount) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        ServerLoadState state = state();
        int loadCount = switch (state) {
            case NORMAL -> Math.clamp(normalCount, 0, 16);
            case PRESSURE -> Math.clamp(normalCount / 2, 0, 6);
            case CRITICAL -> Math.clamp(normalCount / 4, 0, 2);
        };
        if (loadCount <= 0) return;
        for (Player receiver : receivers(caster, impact)) {
            int count = VisualMode.forPlayer(receiver).particles(loadCount);
            if (count > 0) receiver.spawnParticle(particle, impact, count, 0.35, 0.4, 0.35, 0.02);
        }
    }

    private double receiverRadius() {
        return Math.clamp(plugin.getConfig().getDouble(
                "visual-effects.receiver-radius", 40.0), 8.0, 64.0);
    }

    private LinkedHashSet<Player> receivers(Player caster, Location center) {
        LinkedHashSet<Player> receivers = new LinkedHashSet<>(
                caster.getWorld().getNearbyPlayers(center, receiverRadius()));
        receivers.add(caster);
        return receivers;
    }

    /**
     * Frost Nova gets a real ground wave and per-target ice shell instead of a
     * generic potion puff. It is packet-only, receiver bounded, and owns at
     * most one optional shared-controller sequence per cast.
     */
    void frostNova(Player caster, List<? extends LivingEntity> targets, double radius) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        ServerLoadState state = state();
        if (state == ServerLoadState.CRITICAL) return;
        int targetLimit = state == ServerLoadState.NORMAL ? 8 : 4;
        List<? extends LivingEntity> boundedTargets = targets.stream().limit(targetLimit).toList();
        int pulses = state == ServerLoadState.NORMAL ? 3 : 2;
        drawFrostPulse(caster, boundedTargets, radius, 0, state);
        int[] pulse = {1};
        sequences.start(caster, "frost-visual", 4, pulses * 4, frame -> {
            drawFrostPulse(caster, boundedTargets, radius, pulse[0], state);
            return ++pulse[0] < pulses;
        });
    }

    private void drawFrostPulse(
            Player caster, List<? extends LivingEntity> targets, double radius,
            int pulse, ServerLoadState state
    ) {
        if (!caster.isOnline()) return;
        Location center = caster.getLocation().add(0.0, 0.12, 0.0);
        double receiverRadius = Math.clamp(
                plugin.getConfig().getDouble("visual-effects.receiver-radius", 40.0), 8.0, 64.0);
        LinkedHashSet<Player> receivers = new LinkedHashSet<>(
                caster.getWorld().getNearbyPlayers(center, receiverRadius));
        receivers.add(caster);
        int basePoints = state == ServerLoadState.NORMAL ? 24 : 12;
        int points = Math.max(8, basePoints - pulse * 4);
        double waveRadius = radius * (pulse + 1.0) / 3.0;
        Particle.DustOptions iceDust = new Particle.DustOptions(Color.fromRGB(105, 220, 255), 1.0F);
        for (Player receiver : receivers) {
            int modePoints = VisualMode.forPlayer(receiver).particles(points);
            if (modePoints <= 0) continue;
            for (int index = 0; index < modePoints; index++) {
                double angle = Math.PI * 2.0 * index / modePoints;
                Location point = center.clone().add(
                        Math.cos(angle) * waveRadius, 0.0, Math.sin(angle) * waveRadius);
                receiver.spawnParticle(index % 3 == 0 ? Particle.SNOWFLAKE : Particle.DUST,
                        point, 1, 0.03, 0.08, 0.03, 0.0,
                        index % 3 == 0 ? null : iceDust);
            }
            if (pulse == 1) {
                for (LivingEntity target : targets) {
                    if (!target.isValid() || !target.getWorld().equals(caster.getWorld())) continue;
                    Location shell = target.getLocation().add(0.0, 0.25, 0.0);
                    receiver.spawnParticle(Particle.BLOCK, shell, state == ServerLoadState.NORMAL ? 5 : 2,
                            0.35, 0.7, 0.35, 0.0, Material.PACKED_ICE.createBlockData());
                    for (int height = 0; height < 4; height++) {
                        receiver.spawnParticle(Particle.DUST, shell.clone().add(0.0, height * 0.42, 0.0),
                                1, 0.22, 0.05, 0.22, 0.0, iceDust);
                    }
                }
            }
        }
        if (pulse == 0) caster.getWorld().playSound(center, Sound.BLOCK_GLASS_BREAK,
                SoundCategory.PLAYERS, 0.75F, 1.55F);
    }

    private void draw(
            Player receiver, Player caster, Location center, Visual visual, int count, ServerLoadState state
    ) {
        Particle.DustOptions dust = new Particle.DustOptions(visual.color(),
                state == ServerLoadState.NORMAL ? 1.2F : 1.0F);
        receiver.spawnParticle(Particle.DUST, center, count,
                visual.shape() == Shape.SMOKE ? 0.85 : 0.55, 0.7,
                visual.shape() == Shape.SMOKE ? 0.85 : 0.55, 0.015, dust);
        if (state != ServerLoadState.CRITICAL && visual.accent() != null) {
            receiver.spawnParticle(visual.accent(), center, Math.max(1, count / 4),
                    0.45, 0.55, 0.45, 0.02);
        }
        int points = Math.min(12, Math.max(0, count / 2));
        if (points < 3) return;
        switch (visual.shape()) {
            case AURA -> ring(receiver, center.clone().subtract(0.0, 0.85, 0.0), 1.25, points, dust);
            case WAVE -> ring(receiver, center.clone().subtract(0.0, 0.75, 0.0), 2.0, points, dust);
            case SMOKE -> ring(receiver, center.clone().subtract(0.0, 0.65, 0.0), 2.6, points, dust);
            case ARCANE -> spiral(receiver, center, Math.min(8, points), dust);
            case FORWARD -> forward(receiver, caster, Math.min(6, points), dust);
            case DOME -> dome(receiver, center.clone().subtract(0.0, 0.85, 0.0), points, dust);
            case CROSS -> cross(receiver, center.clone().subtract(0.0, 0.7, 0.0), points, dust);
            case VORTEX -> vortex(receiver, center, points, dust);
            case CONE -> cone(receiver, caster, points, dust);
            case RAIN -> rain(receiver, center, points, dust);
            case TRAIL -> trail(receiver, caster, points, dust);
            case SIGIL -> sigil(receiver, center.clone().subtract(0.0, 0.82, 0.0), points, dust);
            case COLUMN -> column(receiver, center, points, dust);
            case SLASH -> slash(receiver, caster, points, dust);
            case SPIRAL -> spiral(receiver, center, Math.min(10, points), dust);
        }
    }

    private void ring(Player receiver, Location center, double radius, int points, Particle.DustOptions dust) {
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0 * index / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            receiver.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void spiral(Player receiver, Location center, int points, Particle.DustOptions dust) {
        for (int index = 0; index < points; index++) {
            double angle = index * Math.PI * 0.65;
            double radius = 0.25 + index * 0.09;
            Location point = center.clone().add(Math.cos(angle) * radius,
                    -0.55 + index * 0.18, Math.sin(angle) * radius);
            receiver.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void forward(Player receiver, Player caster, int points, Particle.DustOptions dust) {
        Location eye = caster.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        for (int index = 1; index <= points; index++) {
            Location point = eye.clone().add(direction.clone().multiply(index * 0.55));
            receiver.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void dome(Player receiver, Location center, int points, Particle.DustOptions dust) {
        int arcPoints = Math.max(4, points / 2);
        for (int axis = 0; axis < 2; axis++) {
            for (int index = 0; index < arcPoints; index++) {
                double angle = Math.PI * index / Math.max(1, arcPoints - 1);
                double horizontal = Math.cos(angle) * 2.0;
                Location point = center.clone().add(axis == 0 ? horizontal : 0.0,
                        Math.sin(angle) * 2.0, axis == 0 ? 0.0 : horizontal);
                receiver.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
            }
        }
    }

    private void cross(Player receiver, Location center, int points, Particle.DustOptions dust) {
        int linePoints = Math.max(3, points / 2);
        for (int index = -linePoints; index <= linePoints; index++) {
            double offset = index * 1.7 / linePoints;
            receiver.spawnParticle(Particle.DUST, center.clone().add(offset, 0.02, 0),
                    1, 0, 0, 0, 0, dust);
            receiver.spawnParticle(Particle.DUST, center.clone().add(0, 0.02, offset),
                    1, 0, 0, 0, 0, dust);
        }
    }

    private void vortex(Player receiver, Location center, int points, Particle.DustOptions dust) {
        for (int index = 0; index < points; index++) {
            double angle = index * Math.PI * 0.72;
            double radius = 1.65 - index * 0.08;
            Location point = center.clone().add(Math.cos(angle) * radius,
                    -0.75 + index * 0.16, Math.sin(angle) * radius);
            receiver.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }
    }

    private void cone(Player receiver, Player caster, int points, Particle.DustOptions dust) {
        Location eye = caster.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        int rays = Math.max(3, Math.min(5, points / 2));
        for (int ray = 0; ray < rays; ray++) {
            double yaw = (ray - (rays - 1) / 2.0) * 0.13;
            Vector rayDirection = direction.clone().rotateAroundY(yaw);
            for (int step = 1; step <= 3; step++) {
                receiver.spawnParticle(Particle.DUST,
                        eye.clone().add(rayDirection.clone().multiply(step * 0.72)),
                        1, 0, 0, 0, 0, dust);
            }
        }
    }

    private void rain(Player receiver, Location center, int points, Particle.DustOptions dust) {
        for (int index = 0; index < points; index++) {
            double angle = index * 2.399963;
            double radius = 0.5 + (index % 4) * 0.42;
            receiver.spawnParticle(Particle.DUST, center.clone().add(
                    Math.cos(angle) * radius, 1.0 + (index % 3) * 0.55,
                    Math.sin(angle) * radius), 1, 0, 0.12, 0, 0, dust);
        }
    }

    private void trail(Player receiver, Player caster, int points, Particle.DustOptions dust) {
        Location feet = caster.getLocation().add(0.0, 0.15, 0.0);
        Vector backward = caster.getEyeLocation().getDirection().setY(0).normalize().multiply(-1);
        for (int index = 0; index < points; index++) {
            double distance = 0.3 + index * 0.28;
            Location point = feet.clone().add(backward.clone().multiply(distance));
            point.add(((index & 1) == 0 ? 1 : -1) * 0.18, index * 0.035, 0.0);
            receiver.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }
    }

    private void sigil(Player receiver, Location center, int points, Particle.DustOptions dust) {
        ring(receiver, center, 1.55, Math.max(6, points), dust);
        double[][] diamond = {{0, 1.55}, {1.55, 0}, {0, -1.55}, {-1.55, 0}, {0, 1.55}};
        for (int edge = 0; edge < 4; edge++) {
            for (int step = 1; step <= 2; step++) {
                double progress = step / 2.0;
                double x = diamond[edge][0] + (diamond[edge + 1][0] - diamond[edge][0]) * progress;
                double z = diamond[edge][1] + (diamond[edge + 1][1] - diamond[edge][1]) * progress;
                receiver.spawnParticle(Particle.DUST, center.clone().add(x, 0.03, z),
                        1, 0, 0, 0, 0, dust);
            }
        }
    }

    private void column(Player receiver, Location center, int points, Particle.DustOptions dust) {
        int heightPoints = Math.max(4, points);
        for (int index = 0; index < heightPoints; index++) {
            double angle = index * Math.PI * 0.5;
            receiver.spawnParticle(Particle.DUST, center.clone().add(
                    Math.cos(angle) * 0.42, -0.75 + index * 0.25,
                    Math.sin(angle) * 0.42), 1, 0, 0, 0, 0, dust);
        }
    }

    private void slash(Player receiver, Player caster, int points, Particle.DustOptions dust) {
        Location eye = caster.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        int arcPoints = Math.max(5, points);
        for (int index = 0; index < arcPoints; index++) {
            double t = index / (double) Math.max(1, arcPoints - 1);
            double side = (t - 0.5) * 2.3;
            double height = Math.sin(t * Math.PI) * 1.0 - 0.4;
            Location point = eye.clone().add(forward.clone().multiply(1.3))
                    .add(right.clone().multiply(side)).add(0, height, 0);
            receiver.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0, dust);
        }
    }

    private Visual visual(String id) {
        if (id.endsWith("-borrowed")) return new Visual(Color.fromRGB(155, 80, 225),
                Particle.SCULK_SOUL, Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.55F, Shape.ARCANE);
        if (id.endsWith("-mastered")) return new Visual(Color.fromRGB(255, 195, 70),
                Particle.ENCHANT, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.65F, Shape.AURA);
        if (id.equals("heal-inverted")) return new Visual(Color.fromRGB(190, 45, 95),
                Particle.DAMAGE_INDICATOR, Sound.ENTITY_WITHER_HURT, 1.35F, Shape.FORWARD);
        if (id.equals("war-cry-iron-vow")) return new Visual(Color.fromRGB(205, 145, 55),
                Particle.WAX_OFF, Sound.ITEM_SHIELD_BLOCK, 0.72F, Shape.AURA);
        if (id.equals("shadow-step-predator-mark")) return new Visual(Color.fromRGB(210, 45, 105),
                Particle.CRIT, Sound.ENTITY_ENDERMAN_HURT, 1.42F, Shape.FORWARD);
        if (id.equals("fireball-arc-burst")) return new Visual(Color.fromRGB(170, 75, 245),
                Particle.ENCHANT, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.55F, Shape.ARCANE);
        if (id.equals("power-shot-split-shot")) return new Visual(Color.fromRGB(70, 225, 185),
                Particle.ELECTRIC_SPARK, Sound.ENTITY_ARROW_SHOOT, 1.48F, Shape.FORWARD);
        if (id.equals("improvisation-dangerous-gambit")) return new Visual(Color.fromRGB(245, 155, 45),
                Particle.TRIAL_SPAWNER_DETECTION, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.28F, Shape.ARCANE);
        return switch (id) {
            case "heal" -> v(80, 225, 155, Particle.HEART,
                    Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.55F, Shape.CROSS);
            case "renewal" -> v(95, 235, 165, Particle.HAPPY_VILLAGER,
                    Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.7F, Shape.VORTEX);
            case "sanctuary" -> v(105, 245, 185, Particle.END_ROD,
                    Sound.BLOCK_BEACON_ACTIVATE, 1.35F, Shape.DOME);
            case "fate-seal" -> v(105, 220, 210, Particle.ENCHANT,
                    Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.45F, Shape.SIGIL);
            case "life-thread" -> v(85, 235, 175, Particle.HEART,
                    Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.65F, Shape.SPIRAL);
            case "omen-reversal" -> v(80, 210, 195, Particle.REVERSE_PORTAL,
                    Sound.ITEM_TOTEM_USE, 1.4F, Shape.CROSS);
            case "soul-resonance" -> v(125, 255, 205, Particle.TOTEM_OF_UNDYING,
                    Sound.BLOCK_BEACON_POWER_SELECT, 1.25F, Shape.DOME);

            case "war-cry" -> v(225, 75, 55, Particle.CRIT,
                    Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.8F, Shape.WAVE);
            case "shield-wall" -> v(205, 115, 65, Particle.WAX_OFF,
                    Sound.ITEM_SHIELD_BLOCK, 0.85F, Shape.CROSS);
            case "fortress" -> v(190, 120, 70, Particle.WAX_ON,
                    Sound.BLOCK_ANVIL_PLACE, 0.72F, Shape.DOME);
            case "blood-rage" -> v(235, 45, 45, Particle.DAMAGE_INDICATOR,
                    Sound.ENTITY_RAVAGER_ROAR, 0.82F, Shape.VORTEX);
            case "rallying-standard" -> v(235, 145, 55, Particle.WAX_ON,
                    Sound.EVENT_RAID_HORN, 0.9F, Shape.COLUMN);
            case "ruin-charge" -> v(240, 70, 35, Particle.CRIT,
                    Sound.ENTITY_RAVAGER_ATTACK, 0.72F, Shape.TRAIL);
            case "unbroken-line" -> v(225, 155, 75, Particle.WAX_ON,
                    Sound.ITEM_SHIELD_BLOCK, 0.65F, Shape.DOME);

            case "shadow-step" -> v(92, 72, 140, Particle.LARGE_SMOKE,
                    Sound.ENTITY_ENDERMAN_TELEPORT, 1.35F, Shape.TRAIL);
            case "venom-blade" -> v(105, 205, 80, Particle.ITEM_SLIME,
                    Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.25F, Shape.SLASH);
            case "smoke-bomb" -> v(105, 85, 145, Particle.LARGE_SMOKE,
                    Sound.ENTITY_ENDERMAN_TELEPORT, 0.9F, Shape.SMOKE);
            case "toxic-burst" -> v(95, 220, 65, Particle.ITEM_SLIME,
                    Sound.ENTITY_SPLASH_POTION_BREAK, 0.85F, Shape.VORTEX);
            case "void-cut" -> v(68, 45, 120, Particle.PORTAL,
                    Sound.ENTITY_ENDERMAN_TELEPORT, 0.85F, Shape.SLASH);
            case "plague-bloom" -> v(115, 205, 55, Particle.SPORE_BLOSSOM_AIR,
                    Sound.ENTITY_SPLASH_POTION_BREAK, 0.65F, Shape.DOME);
            case "vanished-horizon" -> v(175, 80, 235, Particle.PORTAL,
                    Sound.ENTITY_ENDERMAN_TELEPORT, 0.65F, Shape.VORTEX);

            case "fireball" -> v(255, 105, 35, Particle.FLAME,
                    Sound.ENTITY_BLAZE_SHOOT, 1.1F, Shape.FORWARD);
            case "frost-nova" -> v(100, 210, 255, Particle.SNOWFLAKE,
                    Sound.BLOCK_GLASS_BREAK, 1.45F, Shape.WAVE);
            case "meteor" -> v(255, 80, 30, Particle.FLAME,
                    Sound.ENTITY_BLAZE_SHOOT, 0.65F, Shape.RAIN);
            case "mana-surge" -> v(175, 90, 255, Particle.ENCHANT,
                    Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.4F, Shape.VORTEX);
            case "ash-domain" -> v(235, 85, 45, Particle.ASH,
                    Sound.BLOCK_FIRE_AMBIENT, 0.75F, Shape.DOME);
            case "time-lock" -> v(105, 200, 255, Particle.REVERSE_PORTAL,
                    Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.65F, Shape.SIGIL);
            case "axiomatic-break" -> v(205, 105, 255, Particle.ELECTRIC_SPARK,
                    Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.6F, Shape.CROSS);

            case "power-shot" -> v(75, 210, 220, Particle.ELECTRIC_SPARK,
                    Sound.ENTITY_ARROW_SHOOT, 1.2F, Shape.FORWARD);
            case "volley" -> v(70, 205, 195, Particle.CRIT,
                    Sound.ENTITY_ARROW_SHOOT, 1.35F, Shape.CONE);
            case "piercing-shot" -> v(110, 235, 235, Particle.END_ROD,
                    Sound.ENTITY_ARROW_SHOOT, 0.9F, Shape.FORWARD);
            case "beast-call" -> v(90, 195, 105, Particle.HAPPY_VILLAGER,
                    Sound.ITEM_GOAT_HORN_SOUND_0, 0.85F, Shape.WAVE);
            case "gale-step" -> v(105, 230, 225, Particle.CLOUD,
                    Sound.ENTITY_BREEZE_JUMP, 1.3F, Shape.TRAIL);
            case "pack-hunt" -> v(115, 205, 120, Particle.CRIT,
                    Sound.ITEM_GOAT_HORN_SOUND_1, 0.9F, Shape.SIGIL);
            case "horizon-arrow" -> v(130, 245, 245, Particle.END_ROD,
                    Sound.ENTITY_ARROW_SHOOT, 0.6F, Shape.COLUMN);

            case "improvisation" -> v(245, 195, 80, Particle.ENCHANT,
                    Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 1.28F, Shape.SIGIL);
            case "second-wind" -> v(235, 220, 115, Particle.CLOUD,
                    Sound.ITEM_TOTEM_USE, 1.55F, Shape.AURA);
            case "battle-trance" -> v(245, 155, 65, Particle.CRIT,
                    Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.85F, Shape.VORTEX);
            case "arcane-edge" -> v(185, 105, 245, Particle.ENCHANT,
                    Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.15F, Shape.SLASH);
            case "adaptive-arsenal" -> v(245, 190, 75, Particle.WAX_ON,
                    Sound.BLOCK_SMITHING_TABLE_USE, 1.1F, Shape.SIGIL);
            case "rune-reversal" -> v(105, 215, 220, Particle.REVERSE_PORTAL,
                    Sound.ITEM_TOTEM_USE, 0.9F, Shape.CROSS);
            case "open-form" -> v(255, 215, 95, Particle.TOTEM_OF_UNDYING,
                    Sound.BLOCK_BEACON_ACTIVATE, 1.05F, Shape.DOME);
            default -> throw new IllegalArgumentException("No visual family for concrete skill " + id);
        };
    }

    private Visual v(int red, int green, int blue, Particle accent,
                     Sound sound, float pitch, Shape shape) {
        return new Visual(Color.fromRGB(red, green, blue), accent, sound, pitch, shape);
    }

    private ServerLoadState state() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        return performance == null ? ServerLoadState.NORMAL : performance.state();
    }

    static Set<String> categorizedSkills() {
        java.util.HashSet<String> result = new java.util.HashSet<>();
        result.addAll(HEALER);
        result.addAll(WARRIOR);
        result.addAll(ASSASSIN);
        result.addAll(MAGE);
        result.addAll(RANGER);
        result.addAll(FREEFORM);
        return Set.copyOf(result);
    }

    private enum Shape {
        AURA, WAVE, SMOKE, ARCANE, FORWARD,
        DOME, CROSS, VORTEX, CONE, RAIN, TRAIL, SIGIL, COLUMN, SLASH,
        SPIRAL
    }

    private record Visual(Color color, Particle accent, Sound sound, float pitch, Shape shape) {
    }
}
