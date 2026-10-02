package cn.originalrpg.core.command;

import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LanguageCommand implements CommandExecutor, TabCompleter {
    private final MessageService messages;

    public LanguageCommand(MessageService messages) {
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(Component.text(messages.text(player, "language.current", Map.of(
                    "locale", messages.locale(player),
                    "supported", String.join(", ", messages.supportedLocales())
            )), NamedTextColor.AQUA));
            return true;
        }
        if (args.length != 1 || !messages.setLocale(player, args[0])) {
            player.sendMessage(Component.text(messages.text(player, "language.invalid", Map.of(
                    "supported", String.join(", ", messages.supportedLocales())
            )), NamedTextColor.RED));
            return true;
        }
        player.sendMessage(Component.text(messages.text(player, "language.changed", Map.of(
                "locale", messages.locale(player)
        )), NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return messages.supportedLocales().stream().filter(value -> value.startsWith(prefix)).sorted().toList();
    }
}
