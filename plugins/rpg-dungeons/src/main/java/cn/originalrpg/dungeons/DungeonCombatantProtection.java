package cn.originalrpg.dungeons;

import cn.originalrpg.api.rules.CombatTargetRules;
import io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Preserves the identity and damageability of registered encounter entities. */
final class DungeonCombatantProtection implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (CombatTargetRules.isDungeonCombatant(event.getRightClicked())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwallow(SulfurCubeSwallowItemEvent event) {
        if (CombatTargetRules.isDungeonCombatant(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucket(PlayerBucketEntityEvent event) {
        if (CombatTargetRules.isDungeonCombatant(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (CombatTargetRules.isDungeonCombatant(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSplit(SlimeSplitEvent event) {
        // Paper 26.2 dispatches this event for sulfur cubes as well as slimes.
        // The registered parent death alone owns wave/boss completion; children
        // must not inherit its identity or become unregistered encounter actors.
        if (CombatTargetRules.isDungeonCombatant(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (CombatTargetRules.isDungeonCombatant(event.getEntity())) event.setCancelled(true);
    }
}
