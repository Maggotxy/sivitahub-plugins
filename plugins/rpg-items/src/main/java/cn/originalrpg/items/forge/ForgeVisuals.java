package cn.originalrpg.items.forge;

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

import java.util.Map;

/** Personal forge result feedback, emitted only after settlement and equipment signing. */
final class ForgeVisuals {
    private final Plugin plugin;
    private final MessageService messages;

    ForgeVisuals(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    void success(Player player, String rarity, int slots, boolean rareTrait) {
        ServerLoadState load = load();
        NamedTextColor textColor = switch (rarity) {
            case "LEGENDARY" -> NamedTextColor.GOLD;
            case "EPIC" -> NamedTextColor.LIGHT_PURPLE;
            case "RARE" -> NamedTextColor.AQUA;
            default -> NamedTextColor.GREEN;
        };
        Component heading = Component.text(messages.text(player, "items.forge-visual-success"), textColor);
        player.showTitle(Title.title(
                HudGlyphs.available(player) ? HudGlyphs.withIcon(HudGlyphs.FORGE, heading) : heading,
                Component.text(messages.text(player, "items.forge-visual-detail", Map.of(
                        "rarity", messages.text(player, "item-rarities." + rarity.toLowerCase(java.util.Locale.ROOT)),
                        "slots", slots)), NamedTextColor.GRAY)));
        int rgb = switch (rarity) {
            case "LEGENDARY" -> 0xF6B73C;
            case "EPIC" -> 0xC56BFF;
            case "RARE" -> 0x50DCE8;
            default -> 0x8BD17C;
        };
        burst(player, Particle.DUST, 18, new Particle.DustOptions(Color.fromRGB(rgb), 1.25F), load);
        if (rareTrait) burst(player, Particle.ENCHANT, 12, null, load);
        sound(player, rarity.equals("LEGENDARY") ? Sound.ITEM_TOTEM_USE : Sound.BLOCK_SMITHING_TABLE_USE,
                rarity.equals("LEGENDARY") ? 1.25F : 1.1F);
    }

    void failure(Player player) {
        burst(player, Particle.LARGE_SMOKE, 10, null, load());
        sound(player, Sound.BLOCK_ANVIL_BREAK, 0.8F);
    }

    void slotRevealed(Player player, int revealed, int total, boolean traitRevealed) {
        player.sendActionBar(Component.text(messages.text(player, "items.slot-revealed-detail", Map.of(
                "revealed", revealed, "total", total)), NamedTextColor.LIGHT_PURPLE));
        burst(player, traitRevealed ? Particle.ENCHANT : Particle.END_ROD,
                traitRevealed ? 18 : 10, null, load());
        sound(player, traitRevealed ? Sound.BLOCK_ENCHANTMENT_TABLE_USE : Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                traitRevealed ? 0.75F : 1.55F);
    }

    private void burst(
            Player player, Particle particle, int configured, Object data, ServerLoadState load
    ) {
        if (!plugin.getConfig().getBoolean("visual-effects.enabled", true)) return;
        int loadCount = switch (load) {
            case NORMAL -> configured;
            case PRESSURE -> configured / 2;
            case CRITICAL -> Math.min(2, configured / 4);
        };
        int count = VisualMode.forPlayer(player).particles(Math.clamp(loadCount, 0, 28));
        if (count <= 0) return;
        Location center = player.getLocation().add(0.0, 1.0, 0.0);
        if (data == null) player.spawnParticle(particle, center, count, 0.55, 0.7, 0.55, 0.025);
        else player.spawnParticle(particle, center, count, 0.55, 0.7, 0.55, 0.025, data);
    }

    private void sound(Player player, Sound sound, float pitch) {
        float volume = VisualMode.forPlayer(player).volume(
                plugin.getConfig().getDouble("visual-effects.sound-volume", 0.70));
        if (volume > 0.0F) player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private ServerLoadState load() {
        PerformanceService performance = OptionalServices.enabled(plugin, PerformanceService.class);
        return performance == null ? ServerLoadState.NORMAL : performance.state();
    }
}
