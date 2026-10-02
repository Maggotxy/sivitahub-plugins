package cn.originalrpg.guide;

import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class VisualPreferenceCommand implements CommandExecutor, TabCompleter {
    private static final List<String> MODES = List.of("full", "reduced", "minimal", "off");
    private final MessageService messages;

    VisualPreferenceCommand(MessageService messages) {
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length == 0) {
            VisualMode current = VisualMode.forPlayer(player);
            player.sendMessage(Component.text(messages.text(player, "visuals.status", Map.of(
                    "mode", modeName(player, current))), NamedTextColor.AQUA));
            player.sendMessage(Component.text(messages.text(player, "visuals.usage"), NamedTextColor.GRAY));
            return true;
        }
        if (args.length != 1) return false;
        VisualMode mode = VisualMode.parse(args[0]).orElse(null);
        if (mode == null) {
            player.sendMessage(Component.text(messages.text(player, "visuals.invalid"), NamedTextColor.RED));
            return true;
        }
        mode.save(player);
        preview(player, mode);
        player.sendMessage(Component.text(messages.text(player, "visuals.changed", Map.of(
                "mode", modeName(player, mode))), NamedTextColor.GREEN));
        return true;
    }

    private void preview(Player player, VisualMode mode) {
        int particles = mode.particles(24);
        if (particles > 0) {
            player.spawnParticle(Particle.DUST_COLOR_TRANSITION, player.getLocation().add(0.0, 1.0, 0.0),
                    particles, 0.65, 0.75, 0.65, 0.02,
                    new Particle.DustTransition(Color.fromRGB(64, 219, 210),
                            Color.fromRGB(171, 92, 255), 1.1F));
        }
        float volume = mode.volume(0.8);
        if (volume > 0.0F) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                    SoundCategory.PLAYERS, volume, 1.35F);
        }
        player.sendActionBar(Component.text(messages.text(player, "visuals.preview"), NamedTextColor.LIGHT_PURPLE));
    }

    private String modeName(Player player, VisualMode mode) {
        return messages.text(player, "visuals.modes." + mode.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return MODES.stream().filter(mode -> mode.startsWith(prefix)).toList();
    }
}
