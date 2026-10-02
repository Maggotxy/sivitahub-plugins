package cn.originalrpg.enhancement;

import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.ui.HudGlyphs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Result-only enhancement feedback; callers invoke it after durable state and signatures succeed. */
final class EnhancementVisuals {
    private final Plugin plugin;
    private final MessageService messages;

    EnhancementVisuals(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    void refineSuccess(Player player, int value) {
        burst(player, Particle.DUST, 14, new Particle.DustOptions(
                Color.fromRGB(value >= 0 ? 70 : 210, value >= 0 ? 220 : 55, value >= 0 ? 165 : 75), 1.15F));
        sound(player, Sound.BLOCK_ANVIL_USE, value >= 0 ? 1.35F : 0.75F);
    }

    void refineFailure(Player player, boolean protectedRoll) {
        burst(player, protectedRoll ? Particle.ENCHANT : Particle.LARGE_SMOKE, protectedRoll ? 10 : 7, null);
        sound(player, protectedRoll ? Sound.BLOCK_AMETHYST_BLOCK_CHIME : Sound.BLOCK_FIRE_EXTINGUISH,
                protectedRoll ? 0.9F : 0.7F);
    }

    void limitSuccess(Player player, int level) {
        burst(player, Particle.END_ROD, 12 + Math.min(10, level), null);
        sound(player, Sound.BLOCK_BEACON_ACTIVATE, Math.min(1.8F, 1.0F + level * 0.06F));
    }

    void limitFailure(Player player, boolean protectedRoll) {
        burst(player, protectedRoll ? Particle.ENCHANT : Particle.LARGE_SMOKE, protectedRoll ? 12 : 9, null);
        sound(player, protectedRoll ? Sound.BLOCK_ENCHANTMENT_TABLE_USE : Sound.BLOCK_ANVIL_LAND,
                protectedRoll ? 1.2F : 0.65F);
    }

    void mutationSuccess(Player player, String variantName) {
        Component heading = Component.text(
                messages.text(player, "enhancement.mutation-visual-title"), NamedTextColor.LIGHT_PURPLE);
        player.showTitle(Title.title(
                HudGlyphs.available(player) ? HudGlyphs.withIcon(HudGlyphs.SKILL, heading) : heading,
                Component.text(variantName, NamedTextColor.AQUA)));
        burst(player, Particle.ENCHANT, 24, null);
        sound(player, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1.35F);
    }

    void mutationFailure(Player player) {
        burst(player, Particle.LARGE_SMOKE, 10, null);
        sound(player, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.7F);
    }

    private void burst(Player player, Particle particle, int configured, Object data) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        ServerLoadState load = performance == null ? ServerLoadState.NORMAL : performance.state();
        int loadCount = switch (load) {
            case NORMAL -> configured;
            case PRESSURE -> configured / 2;
            case CRITICAL -> Math.min(2, configured / 4);
        };
        int count = VisualMode.forPlayer(player).particles(Math.clamp(loadCount, 0, 32));
        if (count <= 0) return;
        Location center = player.getLocation().add(0.0, 1.0, 0.0);
        if (data == null) player.spawnParticle(particle, center, count, 0.55, 0.75, 0.55, 0.025);
        else player.spawnParticle(particle, center, count, 0.55, 0.75, 0.55, 0.025, data);
    }

    private void sound(Player player, Sound sound, float pitch) {
        float volume = VisualMode.forPlayer(player).volume(
                plugin.getConfig().getDouble("visual-effects.sound-volume", 0.70));
        if (volume > 0.0F) player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }
}
