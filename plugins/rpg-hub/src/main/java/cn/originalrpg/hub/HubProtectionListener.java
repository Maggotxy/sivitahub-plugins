package cn.originalrpg.hub;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class HubProtectionListener implements Listener {
    private final Plugin plugin;
    private final Supplier<HubSite> siteSupplier;
    private final HubNpcManager npcs;
    private final HubTravel travel;
    private final HubMessages hubMessages;
    private final Map<UUID, Instant> warningCooldown = new ConcurrentHashMap<>();

    HubProtectionListener(
            Plugin plugin, Supplier<HubSite> siteSupplier, HubNpcManager npcs,
            HubTravel travel, HubMessages messages
    ) {
        this.plugin = plugin;
        this.siteSupplier = siteSupplier;
        this.npcs = npcs;
        this.travel = travel;
        this.hubMessages = messages;
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer());
    }

    private void clearSession(Player player) {
        UUID playerId = player.getUniqueId();
        warningCooldown.remove(playerId);
        travel.revoke(playerId);
        npcs.revoke(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (npcs.interact(event.getPlayer(), event.getRightClicked(), travel)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onNpcInteractAt(PlayerInteractAtEntityEvent event) {
        if (npcs.interact(event.getPlayer(), event.getRightClicked(), travel)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onServiceBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null || !inside(event.getClickedBlock().getLocation())) return;
        if (event.getClickedBlock().getType() != Material.SMITHING_TABLE) return;
        // The hub table is a physical entrance to the authored forge, not a
        // vanilla smithing recipe screen. Handle cancelled WorldGuard/vanilla
        // protection events too and open the same authoritative /forge flow.
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline() && travel.authenticated(player.getUniqueId())) player.performCommand("forge");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (protectedFor(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (protectedFor(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedFor(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedFor(event.getPlayer(), event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (protectedFor(event.getPlayer(), event.getRightClicked().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysical(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && event.getClickedBlock() != null
                && protectedFor(event.getPlayer(), event.getClickedBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (npcs.managed(event.getEntity()) || hubWorld(event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!hubWorld(event.getLocation())) return;
        // The pre-spawn consumer marks native service mannequins before Paper
        // fires CreatureSpawnEvent. Citizens marks its own actors with the NPC
        // metadata key. Every other creature is rejected so removing the
        // blanket WorldGuard flag does not let passive mobs repopulate the hub.
        if (npcs.managed(event.getEntity()) || event.getEntity().hasMetadata("NPC")) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityBlock(EntityChangeBlockEvent event) {
        if (hubWorld(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (hubWorld(event.getEntity().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> hubWorld(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> hubWorld(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (hubWorld(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (hubWorld(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (hubWorld(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (hubWorld(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (hubWorld(event.getToBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().map(Block::getLocation).anyMatch(this::hubWorld)
                || hubWorld(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().map(Block::getLocation).anyMatch(this::hubWorld)
                || hubWorld(event.getBlock().getLocation())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        HubSite site = siteSupplier.get();
        if (site == null || !hubWorld(event.getTo())) return;
        Location to = event.getTo();
        long dx = to.getBlockX() - site.centerX();
        long dz = to.getBlockZ() - site.centerZ();
        long rescueRadius = site.protectionRadius() + 2L;
        if (to.getY() >= site.baseY() - 8.0 && dx * dx + dz * dz <= rescueRadius * rescueRadius) return;
        event.setTo(site.arrival());
        event.getPlayer().sendActionBar(Component.text(
                hubMessages.text(event.getPlayer(), "boundary-rescue"), NamedTextColor.YELLOW));
    }

    private boolean protectedFor(Player player, Location location) {
        if (!hubWorld(location) || player.hasPermission("rpg.hub.build")) return false;
        Instant now = Instant.now();
        Instant previous = warningCooldown.put(player.getUniqueId(), now);
        if (previous == null || Duration.between(previous, now).toMillis() >= 1_500L) {
            player.sendActionBar(Component.text(hubMessages.text(player, "protected"), NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean inside(Location location) {
        HubSite site = siteSupplier.get();
        return site != null && site.contains(location);
    }

    private boolean hubWorld(Location location) {
        HubSite site = siteSupplier.get();
        return site != null && location.getWorld() != null
                && location.getWorld().getUID().equals(site.world().getUID());
    }
}
