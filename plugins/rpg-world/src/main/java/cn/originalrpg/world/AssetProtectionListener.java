package cn.originalrpg.world;

import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/** Applies account-asset safety rules to loaded and future instance worlds. */
final class AssetProtectionListener implements Listener {
    private final Plugin plugin;

    AssetProtectionListener(Plugin plugin) {
        this.plugin = plugin;
    }

    void configureLoadedWorlds() {
        for (World world : plugin.getServer().getWorlds()) configure(world);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        configure(event.getWorld());
    }

    /**
     * This event guard also covers an instance manager changing the gamerule
     * after WorldLoadEvent but before a player dies.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Permanent server rule: neither a copied configuration, a hot reload,
        // nor another plugin's earlier death handling may turn a player's
        // inventory into ground entities. Clear itemsToKeep as well because
        // ownership plugins may have populated it before this final guard;
        // leaving both keepInventory and itemsToKeep populated can duplicate
        // protected assets on some death pipelines.
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.getItemsToKeep().clear();
        event.setKeepLevel(true);
        event.setShouldDropExperience(false);
        event.setDroppedExp(0);
    }

    private void configure(World world) {
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
    }
}
