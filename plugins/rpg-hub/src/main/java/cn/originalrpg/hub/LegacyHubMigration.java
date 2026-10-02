package cn.originalrpg.hub;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** Moves authenticated test accounts out of the old Overworld hub exactly once. */
final class LegacyHubMigration implements Listener {
    private final Plugin plugin;
    private final Supplier<HubSite> hub;
    private final NamespacedKey migrationVersion;
    private final AuthenticationSession authentication;

    LegacyHubMigration(Plugin plugin, Supplier<HubSite> hub, AuthenticationSession authentication) {
        this.plugin = plugin;
        this.hub = hub;
        this.authentication = authentication;
        this.migrationVersion = new NamespacedKey(plugin, "legacy_hub_migration_version");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        long generation = authentication.token(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> migrate(player, generation));
    }

    private void migrate(Player player, long generation) {
        if (!player.isOnline() || !authentication.valid(player.getUniqueId(), generation)) return;
        int version = Math.max(1, plugin.getConfig().getInt("hub.legacy-migration.version", 1));
        int current = player.getPersistentDataContainer().getOrDefault(
                migrationVersion, PersistentDataType.INTEGER, 0);
        if (current >= version || !insideLegacyHub(player.getLocation())) return;
        HubSite destination = hub.get();
        if (destination == null) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> migrate(player, generation), 40L);
            return;
        }
        if (!player.teleport(destination.arrival())
                || !authentication.valid(player.getUniqueId(), generation)) return;
        player.getPersistentDataContainer().set(
                migrationVersion, PersistentDataType.INTEGER, version);
        player.saveData();
    }

    private boolean insideLegacyHub(Location location) {
        String world = plugin.getConfig().getString("hub.legacy-migration.world", "world");
        if (location.getWorld() == null || !location.getWorld().getName().equals(world)) return false;
        long dx = location.getBlockX() - plugin.getConfig().getInt("hub.legacy-migration.center-x", 7);
        long dz = location.getBlockZ() - plugin.getConfig().getInt("hub.legacy-migration.center-z", 0);
        long radius = Math.clamp(plugin.getConfig().getLong("hub.legacy-migration.radius", 80L), 16L, 256L);
        return dx * dx + dz * dz <= radius * radius;
    }
}
