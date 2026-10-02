package cn.originalrpg.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

final class HubCommand implements CommandExecutor, TabCompleter {
    private final RpgHubPlugin plugin;
    private final HubTravel travel;
    private final HomeTravel home;
    private final BackTravel back;
    private final HubWayfinder wayfinder;
    private final HubMessages hubMessages;

    HubCommand(
            RpgHubPlugin plugin, HubTravel travel, HomeTravel home, BackTravel back,
            HubWayfinder wayfinder, HubMessages messages
    ) {
        this.plugin = plugin;
        this.travel = travel;
        this.home = home;
        this.back = back;
        this.wayfinder = wayfinder;
        this.hubMessages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "hub" -> player(sender, travel::toHub);
            case "wild" -> player(sender, travel::toWilderness);
            case "home" -> player(sender, home::toHome);
            case "sethome" -> player(sender, home::setHome);
            case "back" -> player(sender, back::returnToLastLocation);
            case "hubfind" -> player(sender, player -> navigate(player, args));
            case "hubadmin" -> admin(sender, args);
            default -> false;
        };
    }

    private void navigate(Player player, String[] args) {
        if (args.length == 0) {
            wayfinder.navigateCurrent(player, travel);
        } else if ("cancel".equalsIgnoreCase(args[0])) {
            wayfinder.cancel(player, true);
        } else if (args.length >= 2 && "visit".equalsIgnoreCase(args[1])) {
            // Backward-compatible alias for menus from older builds. NPC
            // discovery is now always spatial: never teleport beside an NPC.
            wayfinder.navigate(player, args[0], travel);
        } else {
            wayfinder.navigate(player, args[0], travel);
        }
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rpg.hub.admin")) return true;
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "status" -> sender.sendMessage(Component.text(plugin.status(sender), NamedTextColor.AQUA));
            case "npcs" -> {
                if (!plugin.restoreNpcs()) {
                    sender.sendMessage(Component.text(hubMessages.text(sender, "building"), NamedTextColor.YELLOW));
                } else {
                    sender.sendMessage(Component.text(hubMessages.text(sender, "npcs-restored"), NamedTextColor.GREEN));
                }
            }
            case "rebuild" -> {
                if (args.length < 2 || !"confirm".equalsIgnoreCase(args[1])) {
                    sender.sendMessage(Component.text(hubMessages.text(sender, "rebuild-confirm"), NamedTextColor.RED));
                } else if (plugin.rebuild()) {
                    sender.sendMessage(Component.text(hubMessages.text(sender, "rebuild-started"), NamedTextColor.GOLD));
                } else {
                    sender.sendMessage(Component.text(hubMessages.text(sender, "building"), NamedTextColor.YELLOW));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean player(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command requires a player.", NamedTextColor.RED));
            return true;
        }
        action.accept(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("hubfind")) {
            if (args.length == 2) return List.of();
            if (args.length != 1) return List.of();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return java.util.stream.Stream.concat(
                            java.util.Arrays.stream(HubRole.values()).map(HubRole::id),
                            java.util.stream.Stream.of("cancel"))
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (!command.getName().equalsIgnoreCase("hubadmin") || args.length > 2) return List.of();
        if (args.length == 2 && "rebuild".equalsIgnoreCase(args[0])) return List.of("confirm");
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return List.of("status", "npcs", "rebuild").stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
