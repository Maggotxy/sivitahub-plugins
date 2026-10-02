package cn.originalrpg.guide;

import cn.originalrpg.api.service.GameplayHudService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.OptionalServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Player-safe facade for the production BetterHud rail. */
final class GameplayHudCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final MessageService messages;

    GameplayHudCommand(Plugin plugin, MessageService messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        GameplayHudService service = OptionalServices.enabled(plugin, GameplayHudService.class);
        if (service == null || !service.available()) {
            player.sendMessage(Component.text(messages.text(player, "gameplay-hud.unavailable"),
                    NamedTextColor.YELLOW));
            return true;
        }
        if (args.length == 0) {
            boolean enabled = service.enabled(player).orElse(true);
            player.sendMessage(Component.text(messages.text(player, "gameplay-hud.status", Map.of(
                    "state", messages.text(player, enabled
                            ? "gameplay-hud.states.on" : "gameplay-hud.states.off"))),
                    NamedTextColor.AQUA));
            return true;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        if (!mode.equals("on") && !mode.equals("off")) {
            player.sendMessage(Component.text(messages.text(player, "gameplay-hud.usage"),
                    NamedTextColor.RED));
            return true;
        }
        boolean enabled = mode.equals("on");
        GameplayHudService.ApplyResult result = service.setEnabled(player, enabled);
        String key = switch (result) {
            case APPLIED -> "gameplay-hud.changed";
            case PENDING -> "gameplay-hud.pending";
            case UNAVAILABLE -> "gameplay-hud.unavailable";
        };
        player.sendMessage(Component.text(messages.text(player, key, Map.of(
                "state", messages.text(player, enabled
                        ? "gameplay-hud.states.on" : "gameplay-hud.states.off"))),
                result == GameplayHudService.ApplyResult.UNAVAILABLE
                        ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("on", "off").stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
