package cn.originalrpg.hub;

import org.bukkit.Location;
import org.bukkit.World;

record HubSite(
        World world,
        int centerX,
        int baseY,
        int centerZ,
        int radius,
        int protectionRadius,
        int version
) {
    Location arrival() {
        // Keep the first sightline on the onboarding trio. The previous +40
        // spawn overlapped the wilderness mannequin and made a specialist look
        // like the starting NPC.
        return new Location(world, centerX + 0.5, baseY + 1.05, centerZ + 25.5, 180.0F, 0.0F);
    }

    boolean contains(Location location) {
        return location.getWorld() != null
                && location.getWorld().getUID().equals(world.getUID())
                && HubGeometry.inside(centerX, centerZ, protectionRadius,
                location.getBlockX(), location.getBlockZ());
    }
}
