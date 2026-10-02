package cn.originalrpg.items.forge;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.items.security.EquipmentSecurity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Operator-controlled, one-shot compensation for equipment lost before the
 * durability guard existed. The pending UUID list is empty in distributions.
 */
public final class EquipmentRecoveryListener implements Listener {
    private final JavaPlugin plugin;
    private final ForgeListener forge;
    private final EquipmentSecurity security;
    private final ProfileService profiles;
    private final MessageService messages;

    public EquipmentRecoveryListener(
            JavaPlugin plugin,
            ForgeListener forge,
            EquipmentSecurity security,
            ProfileService profiles,
            MessageService messages
    ) {
        this.plugin = plugin;
        this.forge = forge;
        this.security = security;
        this.profiles = profiles;
        this.messages = messages;
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        if (!isPending(player.getUniqueId())) return;
        profiles.get(player.getUniqueId()).whenComplete((profile, failure) -> sync(() -> {
            if (!currentSession(player)) return;
            if (failure != null || profile == null) {
                plugin.getLogger().warning("Could not resolve recovery profile for "
                        + player.getUniqueId() + "; compensation remains pending.");
                return;
            }
            issue(player, profile.archetype());
        }));
    }

    private void issue(Player player, Archetype archetype) {
        if (!currentSession(player) || !isPending(player.getUniqueId())) return;
        if (hasAuthenticEquipment(player)) {
            complete(player.getUniqueId());
            plugin.getLogger().warning("Skipped pending equipment compensation for " + player.getName()
                    + ": authentic equipment is already present.");
            return;
        }
        if (archetype == Archetype.UNCHOSEN || player.getInventory().firstEmpty() < 0) {
            player.sendMessage(Component.text(messages.text(player,
                    archetype == Archetype.UNCHOSEN
                            ? "items.recovery-class-required" : "items.recovery-space-required"),
                    NamedTextColor.YELLOW));
            return;
        }
        ItemStack replacement = forge.createStarterReplacement(player, archetype);
        if (!player.getInventory().addItem(replacement).isEmpty()) return;
        player.saveData();
        complete(player.getUniqueId());
        player.sendMessage(Component.text(messages.text(player, "items.recovery-issued"), NamedTextColor.GOLD));
        plugin.getLogger().warning("Issued one signed starter-equipment replacement to " + player.getName()
                + " (" + player.getUniqueId() + ", class=" + archetype + ").");
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private boolean hasAuthenticEquipment(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (security.isAuthentic(item)) return true;
        }
        for (ItemStack item : player.getEnderChest().getContents()) {
            if (security.isAuthentic(item)) return true;
        }
        return false;
    }

    private boolean isPending(UUID playerId) {
        String expected = playerId.toString().toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("recovery.pending-starter-replacements").stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(expected::equals);
    }

    private void complete(UUID playerId) {
        String completed = playerId.toString();
        List<String> pending = new ArrayList<>(
                plugin.getConfig().getStringList("recovery.pending-starter-replacements"));
        if (!pending.removeIf(value -> value.equalsIgnoreCase(completed))) return;
        plugin.getConfig().set("recovery.pending-starter-replacements", pending);
        plugin.saveConfig();
    }

    private void sync(Runnable action) {
        if (!plugin.isEnabled()) return;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) action.run();
            });
        } catch (IllegalStateException ignored) {
            // The operator-controlled UUID remains in configuration for the
            // next authenticated session; no compensation is consumed here.
        }
    }
}
