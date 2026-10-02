package cn.originalrpg.sulfur;

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
import org.bukkit.plugin.Plugin;

final class SulfurVisuals {
    private final Plugin plugin;

    SulfurVisuals(Plugin plugin) {
        this.plugin = plugin;
    }

    void tuned(Player player, Location cubeLocation, int strength) {
        burst(player, cubeLocation, 10 + strength * 3, false);
        sound(player, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.05F + strength * 0.08F);
    }

    void extracted(Player player, Location cubeLocation, int strength) {
        burst(player, cubeLocation, 18 + strength * 4, true);
        sound(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.25F + strength * 0.08F);
    }

    private void burst(Player player, Location location, int configured, boolean transition) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        ServerLoadState load = performance == null ? ServerLoadState.NORMAL : performance.state();
        int loadCount = switch (load) {
            case NORMAL -> configured;
            case PRESSURE -> configured / 2;
            case CRITICAL -> Math.min(3, configured / 4);
        };
        int count = VisualMode.forPlayer(player).particles(Math.clamp(loadCount, 0, 30));
        if (count <= 0) return;
        Location center = location.clone().add(0.0, 0.65, 0.0);
        Object dust = transition
                ? new Particle.DustTransition(Color.fromRGB(244, 214, 56), Color.fromRGB(189, 48, 43), 1.15F)
                : new Particle.DustOptions(Color.fromRGB(244, 214, 56), 1.0F);
        Particle particle = transition ? Particle.DUST_COLOR_TRANSITION : Particle.DUST;
        player.spawnParticle(particle, center, count, 0.45, 0.45, 0.45, 0.015, dust);
    }

    private void sound(Player player, Sound sound, float pitch) {
        float volume = VisualMode.forPlayer(player).volume(
                plugin.getConfig().getDouble("visual-effects.sound-volume", 0.70));
        if (volume > 0.0F) player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }
}
