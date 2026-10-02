package cn.originalrpg.guide;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import fr.xephi.authme.api.v3.AuthMeApi;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import fr.xephi.authme.events.RegisterEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Converts AuthMe's lifecycle into the suite-wide, once-per-connection gate. */
final class AuthenticationReadyBridge implements Listener {
    private final Plugin plugin;
    private final Set<UUID> published = ConcurrentHashMap.newKeySet();

    AuthenticationReadyBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(LoginEvent event) {
        publishNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(RegisterEvent event) {
        publishNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // Covers explicitly unrestricted accounts and future authenticated
        // session modes without letting ordinary unregistered joins through.
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> publishIfReady(player), 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        published.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogout(LogoutEvent event) {
        // /logout does not close the connection. Revoke this connection's
        // readiness so its next successful /login can publish a fresh event.
        Player player = event.getPlayer();
        if (!published.remove(player.getUniqueId())) return;
        plugin.getServer().getPluginManager().callEvent(new AuthenticatedPlayerRevokedEvent(player));
    }

    private void publishNextTick(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> publishIfReady(player));
    }

    private void publishIfReady(Player player) {
        if (!player.isOnline() || !AuthMeApi.getInstance().isAuthenticated(player)) return;
        if (!published.add(player.getUniqueId())) return;
        plugin.getServer().getPluginManager().callEvent(new AuthenticatedPlayerReadyEvent(player));
    }
}
