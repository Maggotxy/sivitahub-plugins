package cn.originalrpg.dungeons;

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

import java.util.Collection;

/** Receiver-bounded encounter telegraphs; presentation degrades without changing combat. */
final class DungeonVisuals {
    private static final Color RUINS_GOLD = Color.fromRGB(238, 178, 64);
    private static final Color HIDDEN_VIOLET = Color.fromRGB(166, 84, 224);
    private static final Color WAVE_RED = Color.fromRGB(218, 70, 62);
    private static final Color SULFUR_ORANGE = Color.fromRGB(239, 116, 39);
    private static final Color STORM_CYAN = Color.fromRGB(78, 202, 235);
    private static final Color MEMORY_PALE = Color.fromRGB(214, 181, 245);
    private static final Color CINNABAR_RED = Color.fromRGB(220, 54, 72);
    private final Plugin plugin;

    DungeonVisuals(Plugin plugin) {
        this.plugin = plugin;
    }

    void waveStart(Location entrance, boolean hidden) {
        if (!enabled()) return;
        ServerLoadState state = state();
        Location center = entrance.clone().add(0.0, 0.12, 11.0);
        for (Player receiver : receivers(center)) {
            ring(receiver, center, 8.0, hidden ? HIDDEN_VIOLET : WAVE_RED,
                    VisualMode.forPlayer(receiver).particles(budget(state)));
            sound(receiver, center, Sound.BLOCK_TRIAL_SPAWNER_SPAWN_MOB, hidden ? 0.72F : 0.95F);
        }
    }

    void waveSkillTelegraph(
            Location from, Location target, WaveSkillRules.Skill skill, boolean hidden, boolean announce
    ) {
        if (!enabled() || from.getWorld() != target.getWorld()) return;
        ServerLoadState state = state();
        Color color = waveSkillColor(skill, hidden);
        Location raisedFrom = from.clone().add(0.0, 0.9, 0.0);
        Location ground = target.clone().add(0.0, 0.08, 0.0);
        for (Player receiver : receivers(target)) {
            int points = Math.min(18, VisualMode.forPlayer(receiver).particles(Math.max(2, budget(state) / 2)));
            switch (skill) {
                case RUIN_LUNGE, STATIC_LINK, GUST_FLANK -> {
                    particleLine(receiver, raisedFrom, ground.clone().add(0.0, 0.55, 0.0), color, points);
                    ring(receiver, ground, skill == WaveSkillRules.Skill.GUST_FLANK ? 2.4 : 1.25,
                            color, Math.max(2, points / 2));
                }
                case ECHO_SEAL -> ring(receiver, ground, 2.75, color, points);
                case FUME_VENT -> ring(receiver, ground, 3.25, color, points);
                case SLAG_MORTAR -> {
                    particleLine(receiver, raisedFrom, ground.clone().add(0.0, 0.35, 0.0), color,
                            Math.max(2, points / 2));
                    ring(receiver, ground, 3.1, color, Math.max(2, points - points / 2));
                }
            }
            if (announce) {
                Sound sound = switch (skill) {
                    case RUIN_LUNGE -> Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM;
                    case ECHO_SEAL -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE;
                    case FUME_VENT, SLAG_MORTAR -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE;
                    case STATIC_LINK -> Sound.BLOCK_COPPER_BULB_TURN_ON;
                    case GUST_FLANK -> Sound.ENTITY_BREEZE_CHARGE;
                };
                sound(receiver, target, sound, hidden ? 0.72F : 0.92F);
            }
        }
    }

    void waveSkillImpact(Location from, Location target, WaveSkillRules.Skill skill, boolean hidden) {
        if (!enabled() || from.getWorld() != target.getWorld()) return;
        ServerLoadState state = state();
        Color color = waveSkillColor(skill, hidden);
        Location raised = target.clone().add(0.0, 0.65, 0.0);
        for (Player receiver : receivers(target)) {
            int count = Math.min(16, VisualMode.forPlayer(receiver).particles(budget(state)));
            if (count > 0) receiver.spawnParticle(Particle.DUST, raised, count,
                    0.75, 0.45, 0.75, 0.035, new Particle.DustOptions(color, 1.15F));
            if (count > 0 && state != ServerLoadState.CRITICAL) {
                Particle accent = switch (skill) {
                    case RUIN_LUNGE -> Particle.CRIT;
                    case ECHO_SEAL -> Particle.REVERSE_PORTAL;
                    case FUME_VENT -> Particle.SMOKE;
                    case SLAG_MORTAR -> Particle.SMALL_FLAME;
                    case STATIC_LINK -> Particle.ELECTRIC_SPARK;
                    case GUST_FLANK -> Particle.GUST;
                };
                receiver.spawnParticle(accent, raised, Math.max(1, count / 3),
                        0.55, 0.35, 0.55, 0.025);
            }
            Sound sound = switch (skill) {
                case RUIN_LUNGE -> Sound.ENTITY_IRON_GOLEM_ATTACK;
                case ECHO_SEAL -> Sound.ENTITY_WARDEN_SONIC_BOOM;
                case FUME_VENT -> Sound.ENTITY_GENERIC_EXTINGUISH_FIRE;
                case SLAG_MORTAR -> Sound.ENTITY_GENERIC_EXPLODE;
                case STATIC_LINK -> Sound.ENTITY_LIGHTNING_BOLT_IMPACT;
                case GUST_FLANK -> Sound.ENTITY_BREEZE_WIND_BURST;
            };
            sound(receiver, target, sound, hidden ? 0.76F : 0.96F);
        }
    }

    void bossWarning(Location spawn, boolean hidden) {
        if (!enabled()) return;
        ServerLoadState state = state();
        Location center = spawn.clone().subtract(0.0, 0.85, 0.0);
        for (Player receiver : receivers(spawn)) {
            ring(receiver, center, 4.5, hidden ? HIDDEN_VIOLET : RUINS_GOLD,
                    VisualMode.forPlayer(receiver).particles(budget(state)));
            sound(receiver, spawn, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, hidden ? 0.62F : 0.88F);
        }
    }

    void bossArrival(LivingEntity boss, boolean hidden) {
        if (!enabled()) return;
        ServerLoadState state = state();
        int configured = state == ServerLoadState.NORMAL ? 18 : state == ServerLoadState.PRESSURE ? 7 : 2;
        Location center = boss.getLocation().add(0.0, 1.0, 0.0);
        for (Player receiver : receivers(center)) {
            int count = VisualMode.forPlayer(receiver).particles(configured);
            if (count > 0) receiver.spawnParticle(Particle.DUST, center, count, 0.75, 0.9, 0.75, 0.02,
                    new Particle.DustOptions(hidden ? HIDDEN_VIOLET : RUINS_GOLD, 1.35F));
            if (count > 0 && state != ServerLoadState.CRITICAL) {
                receiver.spawnParticle(hidden ? Particle.SOUL_FIRE_FLAME : Particle.END_ROD,
                        center, Math.max(1, count / 3), 0.6, 0.8, 0.6, 0.025);
            }
            sound(receiver, center, hidden ? Sound.ENTITY_WITHER_SPAWN : Sound.BLOCK_END_PORTAL_SPAWN,
                    hidden ? 1.25F : 1.65F);
        }
    }

    void bossDefeated(Location center, boolean hidden) {
        if (!enabled()) return;
        ServerLoadState state = state();
        int configured = state == ServerLoadState.NORMAL ? 28 : state == ServerLoadState.PRESSURE ? 10 : 3;
        Location raised = center.clone().add(0.0, 1.0, 0.0);
        for (Player receiver : receivers(center)) {
            int count = VisualMode.forPlayer(receiver).particles(configured);
            if (count > 0) receiver.spawnParticle(Particle.DUST, raised, count, 1.1, 1.0, 1.1, 0.04,
                    new Particle.DustOptions(hidden ? HIDDEN_VIOLET : RUINS_GOLD, 1.25F));
            if (count > 0 && state != ServerLoadState.CRITICAL) {
                receiver.spawnParticle(Particle.TOTEM_OF_UNDYING, raised,
                        Math.max(1, count / 3), 0.9, 0.9, 0.9, 0.08);
            }
            sound(receiver, center, Sound.ENTITY_PLAYER_LEVELUP, hidden ? 0.75F : 1.0F);
        }
    }

    void bossAttackTelegraph(Location center, BossAttackRules.Pattern pattern, boolean hidden, boolean announce) {
        if (!enabled()) return;
        ServerLoadState state = state();
        Color color = patternColor(pattern, hidden);
        Location ground = center.clone().subtract(0.0, 0.85, 0.0);
        for (Player receiver : receivers(center)) {
            int points = VisualMode.forPlayer(receiver).particles(budget(state));
            drawPattern(receiver, ground, pattern, color, points);
            if (announce) sound(receiver, center, Sound.BLOCK_TRIAL_SPAWNER_ABOUT_TO_SPAWN_ITEM, hidden ? 0.62F : 0.78F);
        }
    }

    void bossAttackImpact(Location center, BossAttackRules.Pattern pattern, boolean hidden) {
        if (!enabled()) return;
        ServerLoadState state = state();
        int configured = state == ServerLoadState.NORMAL ? 16 : state == ServerLoadState.PRESSURE ? 6 : 2;
        Location raised = center.clone().add(0.0, 0.35, 0.0);
        Location ground = center.clone().subtract(0.0, 0.82, 0.0);
        for (Player receiver : receivers(center)) {
            int count = VisualMode.forPlayer(receiver).particles(configured);
            drawPattern(receiver, ground, pattern, patternColor(pattern, hidden), count);
            if (count > 0) receiver.spawnParticle(Particle.DUST, raised, count, 1.2, 0.25, 1.2, 0.04,
                    new Particle.DustOptions(patternColor(pattern, hidden), 1.2F));
            Sound impact = switch (pattern.shape()) {
                case SULFUR_VENTS, SULFUR_FISSURES -> Sound.ENTITY_GENERIC_EXPLODE;
                case STORM_SWEEP, STORM_EYE -> Sound.ENTITY_LIGHTNING_BOLT_THUNDER;
                default -> Sound.ENTITY_WARDEN_SONIC_BOOM;
            };
            sound(receiver, center, impact, hidden ? 0.62F : 0.82F);
        }
    }

    void bossPhase(Location center, boolean hidden) {
        if (!enabled()) return;
        ServerLoadState state = state();
        for (Player receiver : receivers(center)) {
            int count = VisualMode.forPlayer(receiver).particles(Math.max(2, budget(state) / 2));
            if (count > 0) receiver.spawnParticle(Particle.ENCHANT, center.clone().add(0.0, 1.0, 0.0),
                    count, 0.65, 0.8, 0.65, 0.08);
            sound(receiver, center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, hidden ? 0.72F : 0.92F);
        }
    }

    void memoryMark(Location center, boolean hostile) {
        if (!enabled()) return;
        ServerLoadState state = state();
        Location ground = center.clone().add(0.0, 0.08, 0.0);
        Color color = hostile ? HIDDEN_VIOLET : MEMORY_PALE;
        for (Player receiver : receivers(center)) {
            int points = Math.min(16, VisualMode.forPlayer(receiver).particles(Math.max(2, budget(state) / 2)));
            ring(receiver, ground, 1.15, color, points);
            if (points > 0 && state != ServerLoadState.CRITICAL) {
                receiver.spawnParticle(Particle.REVERSE_PORTAL, ground.clone().add(0.0, 0.35, 0.0),
                        Math.max(1, points / 4), 0.3, 0.35, 0.3, 0.01);
            }
        }
    }

    void memoryBurst(Location center) {
        if (!enabled()) return;
        ServerLoadState state = state();
        Location raised = center.clone().add(0.0, 0.65, 0.0);
        for (Player receiver : receivers(center)) {
            int count = Math.min(18, VisualMode.forPlayer(receiver).particles(budget(state)));
            if (count > 0) {
                receiver.spawnParticle(Particle.DUST, raised, count, 0.7, 0.45, 0.7, 0.025,
                        new Particle.DustOptions(HIDDEN_VIOLET, 1.25F));
            }
            if (count > 0 && state == ServerLoadState.NORMAL) {
                receiver.spawnParticle(Particle.SCULK_SOUL, raised, Math.max(1, count / 5),
                        0.35, 0.45, 0.35, 0.015);
            }
            sound(receiver, center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.65F);
        }
    }

    void silentStep(Location from, Location to) {
        if (!enabled() || from.getWorld() != to.getWorld()) return;
        ServerLoadState state = state();
        Location start = from.clone().add(0.0, 0.9, 0.0);
        Location end = to.clone().add(0.0, 0.9, 0.0);
        for (Player receiver : receivers(to)) {
            int points = Math.min(18, VisualMode.forPlayer(receiver).particles(budget(state)));
            if (state == ServerLoadState.CRITICAL) points = Math.min(points, 2);
            particleLine(receiver, start, end, HIDDEN_VIOLET, points);
            if (points > 0) {
                receiver.spawnParticle(Particle.SMOKE, end, Math.max(1, points / 4),
                        0.35, 0.55, 0.35, 0.015);
            }
            sound(receiver, end, Sound.ENTITY_ENDERMAN_TELEPORT, 1.45F);
        }
    }

    void gravityPull(Location center, boolean cinnabar) {
        if (!enabled()) return;
        ServerLoadState state = state();
        Color color = cinnabar ? CINNABAR_RED : HIDDEN_VIOLET;
        Location raised = center.clone().add(0.0, 0.25, 0.0);
        for (Player receiver : receivers(center)) {
            int points = Math.min(24, VisualMode.forPlayer(receiver).particles(budget(state)));
            if (points <= 0) continue;
            int spokes = state == ServerLoadState.NORMAL ? 6 : state == ServerLoadState.PRESSURE ? 3 : 1;
            int perSpoke = Math.max(1, points / spokes);
            for (int spoke = 0; spoke < spokes; spoke++) {
                double angle = Math.PI * 2.0 * spoke / spokes;
                Location outer = raised.clone().add(Math.cos(angle) * 4.2, 0.0, Math.sin(angle) * 4.2);
                particleLine(receiver, outer, raised, color, perSpoke);
            }
            sound(receiver, center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, cinnabar ? 0.7F : 0.55F);
        }
    }

    void lightningChain(Location from, Location to) {
        if (!enabled() || from.getWorld() != to.getWorld()) return;
        ServerLoadState state = state();
        Location middle = from.clone().add(to.toVector().subtract(from.toVector()).multiply(0.5));
        for (Player receiver : receivers(middle)) {
            int points = Math.min(20, VisualMode.forPlayer(receiver).particles(budget(state)));
            if (state == ServerLoadState.CRITICAL) points = Math.min(points, 2);
            if (points <= 0) continue;
            Particle.DustOptions dust = new Particle.DustOptions(STORM_CYAN, 1.05F);
            for (int index = 0; index < points; index++) {
                double ratio = points == 1 ? 1.0 : (double) index / (points - 1);
                org.bukkit.util.Vector offset = to.toVector().subtract(from.toVector()).multiply(ratio);
                Location point = from.clone().add(offset);
                if (index > 0 && index + 1 < points && state != ServerLoadState.CRITICAL) {
                    double jitter = index % 2 == 0 ? 0.09 : -0.09;
                    point.add(jitter, (index % 3 - 1) * 0.06, -jitter);
                }
                receiver.spawnParticle(index % 4 == 0 ? Particle.ELECTRIC_SPARK : Particle.DUST,
                        point, 1, 0.0, 0.0, 0.0, 0.0,
                        index % 4 == 0 ? null : dust);
            }
            sound(receiver, middle, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.4F);
        }
    }

    void hazardPulse(Location center, BossAttackRules.Pattern pattern) {
        if (!enabled()) return;
        ServerLoadState state = state();
        Location ground = center.clone().subtract(0.0, 0.82, 0.0);
        Color color = patternColor(pattern, false);
        for (Player receiver : receivers(center)) {
            int points = Math.min(24, VisualMode.forPlayer(receiver).particles(budget(state)));
            drawPattern(receiver, ground, pattern, color, points);
            if (points > 0 && state != ServerLoadState.CRITICAL) {
                Particle accent = switch (pattern.shape()) {
                    case SULFUR_VENTS, SULFUR_FISSURES -> Particle.SMALL_FLAME;
                    case STORM_SWEEP, STORM_EYE -> Particle.ELECTRIC_SPARK;
                    default -> Particle.SOUL_FIRE_FLAME;
                };
                receiver.spawnParticle(accent, center.clone().add(0.0, 0.25, 0.0),
                        Math.max(1, points / 5), 1.0, 0.18, 1.0, 0.015);
            }
        }
    }

    private void ring(Player receiver, Location center, double radius, Color color, int points) {
        if (points <= 0) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, points >= 16 ? 1.25F : 1.0F);
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0 * index / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            receiver.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void cross(Player receiver, Location center, double radius, Color color, int points) {
        crossedLines(receiver, center, radius, color, points, 0.0);
    }

    private void crossedLines(
            Player receiver, Location center, double radius, Color color, int points, double rotation
    ) {
        if (points <= 0) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, points >= 16 ? 1.25F : 1.0F);
        int perAxis = Math.max(2, points / 2);
        for (int index = 0; index < perAxis; index++) {
            double offset = -radius + radius * 2.0 * index / Math.max(1, perAxis - 1);
            dust(receiver, rotated(center, offset, 0.0, rotation), dust);
            dust(receiver, rotated(center, 0.0, offset, rotation), dust);
        }
    }

    private void drawPattern(
            Player receiver, Location center, BossAttackRules.Pattern pattern, Color color, int points
    ) {
        switch (pattern.shape()) {
            case PULSE -> ring(receiver, center, pattern.radius(), color, points);
            case CROSS -> cross(receiver, center, pattern.radius(), color, points);
            case RING -> doubleRing(receiver, center, pattern.innerRadius(), pattern.radius(), color, points);
            case RUIN_DIAGONALS -> crossedLines(receiver, center, pattern.radius(), color, points,
                    pattern.rotationRadians() + Math.PI / 4.0);
            case RUIN_QUADRANTS -> {
                ring(receiver, center, pattern.radius(), color, Math.max(0, points / 2));
                crossedLines(receiver, center, pattern.radius(), color, Math.max(0, points - points / 2),
                        pattern.rotationRadians());
            }
            case SULFUR_VENTS -> vents(receiver, center, pattern, color, points);
            case SULFUR_FISSURES -> fissures(receiver, center, pattern, color, points);
            case STORM_SWEEP -> sweep(receiver, center, pattern, color, points);
            case STORM_EYE -> doubleRing(receiver, center, pattern.innerRadius(),
                    pattern.radius() - pattern.laneHalfWidth(), color, points);
        }
    }

    private void doubleRing(
            Player receiver, Location center, double inner, double outer, Color color, int points
    ) {
        ring(receiver, center, inner, color, Math.max(0, points / 2));
        ring(receiver, center, outer, color, Math.max(0, points - points / 2));
    }

    private void vents(
            Player receiver, Location center, BossAttackRules.Pattern pattern, Color color, int points
    ) {
        if (points <= 0) return;
        int perVent = Math.max(0, points / 4);
        double offset = 3.35;
        Location[] centers = {
                rotated(center, offset, 0.0, pattern.rotationRadians()),
                rotated(center, -offset, 0.0, pattern.rotationRadians()),
                rotated(center, 0.0, offset, pattern.rotationRadians()),
                rotated(center, 0.0, -offset, pattern.rotationRadians())
        };
        if (points < 8) {
            Particle.DustOptions marker = new Particle.DustOptions(color, 1.2F);
            for (Location vent : centers) dust(receiver, vent, marker);
            return;
        }
        for (int index = 0; index < centers.length; index++) {
            int allocated = index == centers.length - 1 ? Math.max(0, points - perVent * 3) : perVent;
            ring(receiver, centers[index], pattern.laneHalfWidth(), color, allocated);
        }
    }

    private void fissures(
            Player receiver, Location center, BossAttackRules.Pattern pattern, Color color, int points
    ) {
        if (points <= 0) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, points >= 16 ? 1.25F : 1.0F);
        int perLine = Math.max(2, points / 3);
        for (double lane : new double[]{-3.0, 0.0, 3.0}) {
            for (int index = 0; index < perLine; index++) {
                double along = -pattern.radius() + pattern.radius() * 2.0 * index / Math.max(1, perLine - 1);
                dust(receiver, rotated(center, along, lane, pattern.rotationRadians()), dust);
            }
        }
    }

    private void sweep(
            Player receiver, Location center, BossAttackRules.Pattern pattern, Color color, int points
    ) {
        if (points <= 0) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, points >= 16 ? 1.25F : 1.0F);
        int boundary = Math.max(2, points / 3);
        for (int index = 0; index < boundary; index++) {
            double across = -pattern.radius() + pattern.radius() * 2.0 * index / Math.max(1, boundary - 1);
            dust(receiver, rotated(center, 0.0, across, pattern.rotationRadians()), dust);
        }
        int arcPoints = Math.max(0, points - boundary);
        for (int index = 0; index < arcPoints; index++) {
            double angle = -Math.PI / 2.0 + Math.PI * index / Math.max(1, arcPoints - 1)
                    + pattern.rotationRadians();
            Location point = center.clone().add(Math.cos(angle) * pattern.radius(), 0.0,
                    Math.sin(angle) * pattern.radius());
            dust(receiver, point, dust);
        }
    }

    private Location rotated(Location center, double x, double z, double rotation) {
        double cosine = Math.cos(rotation);
        double sine = Math.sin(rotation);
        return center.clone().add(x * cosine - z * sine, 0.0, x * sine + z * cosine);
    }

    private void dust(Player receiver, Location point, Particle.DustOptions dust) {
        receiver.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
    }

    private void particleLine(Player receiver, Location from, Location to, Color color, int points) {
        if (points <= 0 || from.getWorld() != to.getWorld()) return;
        Particle.DustOptions dust = new Particle.DustOptions(color, points >= 12 ? 1.15F : 1.0F);
        org.bukkit.util.Vector delta = to.toVector().subtract(from.toVector());
        for (int index = 0; index < points; index++) {
            double ratio = points == 1 ? 1.0 : (double) index / (points - 1);
            dust(receiver, from.clone().add(delta.clone().multiply(ratio)), dust);
        }
    }

    private Color patternColor(BossAttackRules.Pattern pattern, boolean hidden) {
        return switch (pattern.shape()) {
            case RUIN_DIAGONALS, RUIN_QUADRANTS -> hidden ? HIDDEN_VIOLET : RUINS_GOLD;
            case SULFUR_VENTS, SULFUR_FISSURES -> SULFUR_ORANGE;
            case STORM_SWEEP, STORM_EYE -> STORM_CYAN;
            default -> hidden ? HIDDEN_VIOLET : WAVE_RED;
        };
    }

    private Color waveSkillColor(WaveSkillRules.Skill skill, boolean hidden) {
        if (hidden && (skill == WaveSkillRules.Skill.RUIN_LUNGE
                || skill == WaveSkillRules.Skill.ECHO_SEAL)) return HIDDEN_VIOLET;
        return switch (skill) {
            case RUIN_LUNGE, ECHO_SEAL -> RUINS_GOLD;
            case FUME_VENT, SLAG_MORTAR -> SULFUR_ORANGE;
            case STATIC_LINK, GUST_FLANK -> STORM_CYAN;
        };
    }

    private Collection<Player> receivers(Location center) {
        double radius = Math.clamp(plugin.getConfig().getDouble("visual-effects.receiver-radius", 64.0), 16.0, 96.0);
        return center.getWorld().getNearbyPlayers(center, radius);
    }

    private int budget(ServerLoadState state) {
        return switch (state) {
            case NORMAL -> Math.clamp(plugin.getConfig().getInt("visual-effects.particles.normal", 24), 8, 48);
            case PRESSURE -> Math.clamp(plugin.getConfig().getInt("visual-effects.particles.pressure", 10), 3, 20);
            case CRITICAL -> Math.clamp(plugin.getConfig().getInt("visual-effects.particles.critical", 3), 0, 6);
        };
    }

    private void sound(Player player, Location location, Sound sound, float pitch) {
        float volume = VisualMode.forPlayer(player).volume(
                Math.clamp(plugin.getConfig().getDouble("visual-effects.sound-volume", 0.8), 0.0, 1.5));
        if (volume > 0.0F) player.playSound(location, sound, SoundCategory.HOSTILE, volume, pitch);
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("visual-effects.enabled", true);
    }

    private ServerLoadState state() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        return performance == null ? ServerLoadState.NORMAL : performance.state();
    }
}
