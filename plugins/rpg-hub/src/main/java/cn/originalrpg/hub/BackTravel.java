package cn.originalrpg.hub;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.service.CombatStateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Persistent, combat-safe return point shared by hub, wilderness, home and death travel. */
final class BackTravel implements Listener {
    private final Plugin plugin;
    private final HubMessages hubMessages;
    private final CombatStateService combat;
    private final NamespacedKey backLocation;
    private final AuthenticationSession authentication;
    private final java.util.Map<UUID, UUID> pending = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, List<Chunk>> requestChunks = new ConcurrentHashMap<>();
    private final ReferenceCounter<Chunk> heldChunks = new ReferenceCounter<>();

    BackTravel(
            Plugin plugin, HubMessages hubMessages, CombatStateService combat,
            AuthenticationSession authentication
    ) {
        this.plugin = plugin;
        this.hubMessages = hubMessages;
        this.combat = combat;
        this.authentication = authentication;
        this.backLocation = new NamespacedKey(plugin, "back_location");
    }

    void remember(Player player, Location location) {
        if (!authentication.authenticated(player.getUniqueId()) || !storable(location)) return;
        player.getPersistentDataContainer().set(
                backLocation, PersistentDataType.STRING, encode(location));
        player.saveData();
    }

    void returnToLastLocation(Player player) {
        UUID playerId = player.getUniqueId();
        if (!authentication.authenticated(playerId)) return;
        if (pending.containsKey(playerId)) {
            player.sendMessage(Component.text(hubMessages.text(player, "back-busy"), NamedTextColor.YELLOW));
            return;
        }
        if (player.getWorld().getName().startsWith("dungeon_")) {
            player.sendMessage(Component.text(hubMessages.text(player, "travel-dungeon-blocked"), NamedTextColor.RED));
            return;
        }
        if (combat.inCombat(player.getUniqueId())) {
            player.sendMessage(Component.text(hubMessages.text(player, "travel-combat-blocked",
                    "seconds", combat.remainingSeconds(player.getUniqueId())), NamedTextColor.RED));
            return;
        }
        String encoded = player.getPersistentDataContainer().get(backLocation, PersistentDataType.STRING);
        Location target = decode(encoded);
        if (!returnable(target)) {
            clear(player);
            player.sendMessage(Component.text(hubMessages.text(player, "back-missing"), NamedTextColor.YELLOW));
            return;
        }
        int chunkX = target.getBlockX() >> 4;
        int chunkZ = target.getBlockZ() >> 4;
        long generation = authentication.token(playerId);
        if (!authentication.valid(playerId, generation)) return;
        UUID request = UUID.randomUUID();
        if (pending.putIfAbsent(playerId, request) != null) return;
        Location origin = player.getLocation().clone();
        player.sendActionBar(Component.text(hubMessages.text(player, "back-loading"), NamedTextColor.AQUA));
        loadNeighborhood(target.getWorld(), chunkX, chunkZ)
                .orTimeout(12L, TimeUnit.SECONDS)
                .whenComplete((chunks, loadError) -> {
                    if (!plugin.isEnabled()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!request.equals(pending.get(playerId))
                            || !currentSession(player) || !authentication.valid(playerId, generation)) {
                        cancel(playerId, request);
                        return;
                    }
                    if (loadError != null || chunks == null || chunks.isEmpty()
                            || combat.inCombat(player.getUniqueId())) {
                        cancel(playerId, request);
                        String message = combat.inCombat(player.getUniqueId()) ? "travel-combat-blocked"
                                : loadError == null ? "back-unavailable" : "back-failed";
                        if (loadError == null && (chunks == null || chunks.isEmpty())) clear(player);
                        player.sendMessage(Component.text(hubMessages.text(player, message,
                                "seconds", combat.remainingSeconds(player.getUniqueId())), NamedTextColor.RED));
                        return;
                    }
                    hold(request, chunks);
                    try {
                        Location safe = safeArrival(target);
                        if (safe == null) {
                            cancel(playerId, request);
                            player.sendMessage(Component.text(
                                    hubMessages.text(player, "back-unavailable"), NamedTextColor.RED));
                            return;
                        }
                        // Remove our request before teleport() fires its
                        // synchronous PlayerTeleportEvent. The listener then
                        // only cancels genuinely external teleports and cannot
                        // consume this operation underneath its success path.
                        if (!authentication.valid(playerId, generation)
                                || !pending.remove(playerId, request)) return;
                        boolean success = player.teleport(safe, PlayerTeleportEvent.TeleportCause.COMMAND);
                        if (!success || !currentSession(player)
                                || !authentication.valid(playerId, generation)) {
                            if (currentSession(player) && authentication.valid(playerId, generation)) {
                                player.sendMessage(Component.text(
                                        hubMessages.text(player, "back-failed"), NamedTextColor.RED));
                            }
                            return;
                        }
                        // Conventional /back toggling remains useful, while the
                        // same combat/dungeon checks apply on every direction.
                        remember(player, origin);
                        player.sendMessage(Component.text(
                                hubMessages.text(player, "back-arrived"), NamedTextColor.GREEN));
                    } finally {
                        release(request);
                    }
                    });
                });
    }

    void close() {
        pending.clear();
        requestChunks.clear();
        for (Chunk chunk : heldChunks.retained()) chunk.removePluginChunkTicket(plugin);
        heldChunks.clear();
    }

    private void hold(UUID request, List<Chunk> chunks) {
        List<Chunk> retained = chunks.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (retained.isEmpty() || requestChunks.putIfAbsent(request, retained) != null) return;
        for (Chunk chunk : retained) {
            if (heldChunks.retain(chunk)) chunk.addPluginChunkTicket(plugin);
        }
    }

    private void release(UUID request) {
        List<Chunk> chunks = requestChunks.remove(request);
        if (chunks == null) return;
        for (Chunk chunk : chunks) {
            if (heldChunks.release(chunk)) chunk.removePluginChunkTicket(plugin);
        }
    }

    private void cancel(UUID playerId, UUID request) {
        pending.remove(playerId, request);
        release(request);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!authentication.authenticated(event.getEntity().getUniqueId())) return;
        Location death = event.getEntity().getLocation().clone();
        if (storable(death)) remember(event.getEntity(), death);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID request = pending.remove(event.getPlayer().getUniqueId());
        if (request != null) release(request);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        UUID request = pending.remove(event.player().getUniqueId());
        if (request != null) release(request);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID request = pending.remove(event.getPlayer().getUniqueId());
        if (request != null) release(request);
    }

    private CompletableFuture<List<Chunk>> loadNeighborhood(World world, int centerX, int centerZ) {
        return GeneratedChunkLoads.square(world, centerX, centerZ, 1);
    }

    private Location safeArrival(Location target) {
        if (safeFeet(target.getBlock())) return target.clone();
        for (int radius = 1; radius <= 3; radius++) {
            for (int y = -2; y <= 2; y++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        Block feet = target.getWorld().getBlockAt(
                                target.getBlockX() + x, target.getBlockY() + y, target.getBlockZ() + z);
                        if (!safeFeet(feet)) continue;
                        Location safe = feet.getLocation().add(0.5, 0.05, 0.5);
                        safe.setYaw(target.getYaw());
                        safe.setPitch(target.getPitch());
                        if (target.getWorld().getWorldBorder().isInside(safe)) return safe;
                    }
                }
            }
        }
        return null;
    }

    private boolean safeFeet(Block feet) {
        Block head = feet.getRelative(0, 1, 0);
        Block ground = feet.getRelative(0, -1, 0);
        Material type = ground.getType();
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()
                && type.isSolid() && !Tag.LEAVES.isTagged(type)
                && type != Material.MAGMA_BLOCK && type != Material.CACTUS
                && type != Material.CAMPFIRE && type != Material.SOUL_CAMPFIRE
                && type != Material.POWDER_SNOW;
    }

    private boolean returnable(Location location) {
        return storable(location) && location.getWorld().getWorldBorder().isInside(location);
    }

    private boolean storable(Location location) {
        return location != null && location.getWorld() != null
                && !location.getWorld().getName().startsWith("dungeon_")
                && Double.isFinite(location.getX()) && Double.isFinite(location.getY())
                && Double.isFinite(location.getZ()) && Float.isFinite(location.getYaw())
                && Float.isFinite(location.getPitch());
    }

    private String encode(Location location) {
        return location.getWorld().getUID() + ";" + location.getX() + ";" + location.getY() + ";"
                + location.getZ() + ";" + location.getYaw() + ";" + location.getPitch();
    }

    private Location decode(String encoded) {
        if (encoded == null) return null;
        try {
            String[] parts = encoded.split(";", -1);
            if (parts.length != 6) return null;
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            if (world == null) return null;
            Location value = new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]), Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
            return storable(value) ? value : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void clear(Player player) {
        player.getPersistentDataContainer().remove(backLocation);
        player.saveData();
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }
}
