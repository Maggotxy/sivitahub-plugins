package cn.originalrpg.world;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/** Keeps players inside the season's pregenerated terrain. */
final class WorldBoundaryManager implements Listener {
    private final Plugin plugin;

    WorldBoundaryManager(Plugin plugin) {
        this.plugin = plugin;
    }

    void configureLoadedWorlds() {
        plugin.getServer().getWorlds().forEach(this::configure);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        configure(event.getWorld());
    }

    private void configure(World world) {
        if (!plugin.getConfig().getBoolean("boundaries.enabled", true)) return;
        String key = switch (world.getEnvironment()) {
            case NORMAL -> "overworld";
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> null;
        };
        if (key == null) return;
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("boundaries." + key);
        if (section == null || !world.getName().equals(section.getString("world", ""))) return;
        double diameter = Math.clamp(section.getDouble("diameter", 0.0), 64.0, 59_999_968.0);
        world.getWorldBorder().setCenter(section.getDouble("center-x", 0.0),
                section.getDouble("center-z", 0.0));
        world.getWorldBorder().setSize(diameter);
        world.getWorldBorder().setWarningDistance(Math.clamp(
                section.getInt("warning-distance", 48), 0, 512));
        world.getWorldBorder().setDamageBuffer(4.0);
        world.getWorldBorder().setDamageAmount(0.2);
        plugin.getLogger().info("Pregenerated boundary active: world=" + world.getName()
                + " center=(" + section.getDouble("center-x", 0.0) + ","
                + section.getDouble("center-z", 0.0) + ") diameter=" + diameter);
    }
}
