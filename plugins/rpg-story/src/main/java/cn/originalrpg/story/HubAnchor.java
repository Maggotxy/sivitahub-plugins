package cn.originalrpg.story;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;

record HubAnchor(World world, int centerX, int baseY, int centerZ) {
    static HubAnchor resolve(Plugin plugin) {
        String worldName = plugin.getConfig().getString("actor.world", "world");
        World world = Objects.requireNonNull(plugin.getServer().getWorld(worldName), "Story actor world: " + worldName);
        PersistentDataContainer data = world.getPersistentDataContainer();
        Integer x = data.get(key("hub_center_x"), PersistentDataType.INTEGER);
        Integer y = data.get(key("hub_base_y"), PersistentDataType.INTEGER);
        Integer z = data.get(key("hub_center_z"), PersistentDataType.INTEGER);
        if (x != null && y != null && z != null) return new HubAnchor(world, x, y, z);

        Location spawn = world.getSpawnLocation();
        return new HubAnchor(world, spawn.getBlockX(), spawn.getBlockY() - 1, spawn.getBlockZ() - 40);
    }

    Location offset(List<?> values) {
        if (values.size() != 3 || values.stream().anyMatch(value -> !(value instanceof Number))) {
            throw new IllegalArgumentException("Hub-relative location must contain exactly three numbers");
        }
        double x = ((Number) values.get(0)).doubleValue();
        double y = ((Number) values.get(1)).doubleValue();
        double z = ((Number) values.get(2)).doubleValue();
        return new Location(world, centerX + x, baseY + y, centerZ + z);
    }

    private static NamespacedKey key(String value) {
        return Objects.requireNonNull(NamespacedKey.fromString("rpghub:" + value));
    }
}
