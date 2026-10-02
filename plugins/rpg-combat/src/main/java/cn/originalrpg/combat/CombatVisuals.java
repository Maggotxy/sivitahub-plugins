package cn.originalrpg.combat;

import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.ui.HudGlyphs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/** Attacker-only combat feedback: no entities, broadcasts, or normal-hit sound spam. */
final class CombatVisuals {
    private final Plugin plugin;
    private final MessageService messages;

    CombatVisuals(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    void hit(Player attacker, LivingEntity target, double damage, boolean critical, boolean executed) {
        if (plugin.getConfig().getBoolean("feedback.target-hud.enabled", true)) {
            targetHud(attacker, target, damage, critical, executed);
        }
        if (!plugin.getConfig().getBoolean("feedback.visual-effects.enabled", true)) return;
        VisualMode mode = VisualMode.forPlayer(attacker);
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        ServerLoadState load = performance == null ? ServerLoadState.NORMAL : performance.state();
        int configured = executed
                ? plugin.getConfig().getInt("feedback.visual-effects.execute-particles", 18)
                : critical ? plugin.getConfig().getInt("feedback.visual-effects.critical-particles", 8) : 0;
        configured = switch (load) {
            case NORMAL -> Math.clamp(configured, 0, 32);
            case PRESSURE -> Math.clamp(configured / 2, 0, 12);
            case CRITICAL -> Math.clamp(configured / 4, 0, 3);
        };
        int count = mode.particles(configured);
        Location impact = target.getLocation().add(0.0, Math.max(0.45, target.getHeight() * 0.58), 0.0);
        if (count > 0) {
            attacker.spawnParticle(executed ? Particle.FLASH : Particle.CRIT, impact,
                    executed ? Math.min(2, count) : count, 0.35, 0.45, 0.35, 0.04);
            if (executed && load != ServerLoadState.CRITICAL) {
                attacker.spawnParticle(Particle.SWEEP_ATTACK, impact, Math.max(1, count / 4),
                        0.35, 0.25, 0.35, 0.0);
            }
        }
        float volume = mode.volume(plugin.getConfig().getDouble("feedback.visual-effects.sound-volume", 0.65));
        if (volume <= 0.0F || !critical && !executed) return;
        attacker.playSound(impact, executed ? Sound.ENTITY_WITHER_BREAK_BLOCK : Sound.ENTITY_PLAYER_ATTACK_CRIT,
                SoundCategory.PLAYERS, volume, executed ? 1.45F : 1.15F);
    }

    private void targetHud(Player attacker, LivingEntity target, double damage, boolean critical, boolean executed) {
        AttributeInstance maximumAttribute = target.getAttribute(Attribute.MAX_HEALTH);
        double maximum = maximumAttribute == null ? Math.max(1.0, target.getHealth()) : maximumAttribute.getValue();
        double remaining = Math.max(0.0, target.getHealth() - damage);
        int segments = Math.clamp(plugin.getConfig().getInt("feedback.target-hud.segments", 10), 5, 20);
        int filled = (int) Math.ceil(segments * Math.clamp(remaining / Math.max(1.0, maximum), 0.0, 1.0));
        String bar = "▰".repeat(filled) + "▱".repeat(segments - filled);
        String state = executed ? messages.text(attacker, "combat.execute")
                : critical ? messages.text(attacker, "combat.critical") : "";
        NamedTextColor color = executed ? NamedTextColor.DARK_RED
                : critical ? NamedTextColor.GOLD : NamedTextColor.RED;
        boolean customGlyph = plugin.getConfig().getBoolean(
                "feedback.target-hud.use-resource-glyphs", true)
                && attacker.getResourcePackStatus()
                == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
        Component hud = Component.empty()
                .append((critical || executed) && customGlyph
                        ? HudGlyphs.icon(executed ? HudGlyphs.EXECUTE : HudGlyphs.CRITICAL)
                        : Component.empty())
                .append((critical || executed) && customGlyph ? Component.space() : Component.empty())
                .append(Component.text(state.isEmpty() ? "" : state + "  ", color))
                .append(Component.text("-" + String.format(Locale.ROOT, "%.1f", damage) + "  ", NamedTextColor.RED))
                .append(Component.text(bar + "  ", remaining / Math.max(1.0, maximum) <= 0.25
                        ? NamedTextColor.RED : NamedTextColor.GREEN))
                .append(Component.text(String.format(Locale.ROOT, "%.0f/%.0f", remaining, maximum), NamedTextColor.GRAY));
        attacker.sendActionBar(hud);
    }
}
