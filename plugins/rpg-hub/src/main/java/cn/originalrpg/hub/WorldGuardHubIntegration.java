package cn.originalrpg.hub;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

final class WorldGuardHubIntegration {
    private static final String REGION_ID = "sivita_hub";

    private WorldGuardHubIntegration() {
    }

    static boolean apply(Plugin plugin, HubSite site) {
        try {
            RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(site.world()));
            if (manager == null) return false;
            int radius = site.protectionRadius();
            ProtectedRegion existing = manager.getRegion(REGION_ID);
            if (existing != null) manager.removeRegion(REGION_ID);
            ProtectedRegion region = new ProtectedPolygonalRegion(REGION_ID, circle(site, radius),
                    site.world().getMinHeight(), site.world().getMaxHeight() - 1);
            region.setPriority(80);
            region.setFlag(Flags.BUILD, StateFlag.State.DENY);
            region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
            region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
            region.setFlag(Flags.USE, StateFlag.State.ALLOW);
            region.setFlag(Flags.INTERACT, StateFlag.State.ALLOW);
            region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);
            region.setFlag(Flags.PVP, StateFlag.State.DENY);
            // Do not use WorldGuard's blanket mob-spawning flag here. In 26.2
            // mannequins are LivingEntity instances, so that flag also cancels
            // the service NPCs before Bukkit can add them to the world and
            // leaves their separately spawned TextDisplays orphaned. The hub
            // listener below owns the narrower spawn policy and explicitly
            // allows managed/Citizens NPCs.
            region.setFlag(Flags.CREEPER_EXPLOSION, StateFlag.State.DENY);
            region.setFlag(Flags.OTHER_EXPLOSION, StateFlag.State.DENY);
            region.setFlag(Flags.TNT, StateFlag.State.DENY);
            region.setFlag(Flags.LIGHTER, StateFlag.State.DENY);
            region.setFlag(Flags.FIRE_SPREAD, StateFlag.State.DENY);
            region.setFlag(Flags.LAVA_FIRE, StateFlag.State.DENY);
            region.setFlag(Flags.ENDER_BUILD, StateFlag.State.DENY);
            region.setFlag(Flags.RAVAGER_RAVAGE, StateFlag.State.DENY);
            region.setFlag(Flags.PISTONS, StateFlag.State.DENY);
            region.setFlag(Flags.TRAMPLE_BLOCKS, StateFlag.State.DENY);
            region.setFlag(Flags.FALL_DAMAGE, StateFlag.State.DENY);
            manager.addRegion(region);
            manager.saveChanges();
            return true;
        } catch (RuntimeException | com.sk89q.worldguard.protection.managers.storage.StorageException exception) {
            plugin.getLogger().warning("Could not persist the WorldGuard hub region: " + exception.getMessage());
            return false;
        }
    }

    private static List<BlockVector2> circle(HubSite site, int radius) {
        // A 48-sided boundary follows the public circle closely while keeping
        // the serialized WorldGuard region compact. The extra block makes the
        // polygon circumscribe the listener's inclusive radius after rounding.
        List<BlockVector2> points = new ArrayList<>(48);
        for (int index = 0; index < 48; index++) {
            double angle = Math.PI * 2.0 * index / 48.0;
            int x = site.centerX() + (int) Math.round(Math.cos(angle) * (radius + 1.0));
            int z = site.centerZ() + (int) Math.round(Math.sin(angle) * (radius + 1.0));
            points.add(BlockVector2.at(x, z));
        }
        return points;
    }
}
