package cn.originalrpg.guide;

import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Hub-only command access to the player's native Ender Chest. */
final class WarehouseCommand implements CommandExecutor {
    private final MessageService messages;
    private final CombatStateService combat;
    private final Set<String> commandWorlds;

    WarehouseCommand(Plugin plugin, MessageService messages, CombatStateService combat) {
        this.messages = messages;
        this.combat = combat;
        commandWorlds = new HashSet<>();
        for (String world : plugin.getConfig().getStringList("warehouse.command-worlds")) {
            if (world != null && !world.isBlank()) commandWorlds.add(world.toLowerCase(Locale.ROOT));
        }
        if (commandWorlds.isEmpty()) commandWorlds.add("sivita_hub");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.text(sender, "common.player-only"));
            return true;
        }
        if (combat != null && combat.inCombat(player.getUniqueId())) {
            player.sendMessage(Component.text(messages.text(player, "warehouse.in-combat"), NamedTextColor.RED));
            return true;
        }
        if (!commandWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) {
            player.sendMessage(Component.text(messages.text(player, "warehouse.hub-only"), NamedTextColor.YELLOW));
            return true;
        }
        player.openInventory(player.getEnderChest());
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN,
                SoundCategory.PLAYERS, 0.45F, 1.0F);
        return true;
    }
}
