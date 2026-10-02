package cn.originalrpg.hub;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.service.CombatStateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Bukkit;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** One vanilla-bed-backed home with combat-safe warmup and no second location database. */
final class HomeTravel implements Listener {
    private final Plugin plugin;
    private final HubMessages hubMessages;
    private final CombatStateService combat;
    private final BackTravel back;
    private final AuthenticationSession authentication;
    private final NamespacedKey cooldownUntil;
    private final Map<UUID, PendingHome> pending = new ConcurrentHashMap<>();
    private final Map<UUID, List<Chunk>> requestChunks = new ConcurrentHashMap<>();
    private final ReferenceCounter<Chunk> heldChunks = new ReferenceCounter<>();

    HomeTravel(
            Plugin plugin, HubMessages messages, CombatStateService combat,
            BackTravel back, AuthenticationSession authentication
    ) {
        this.plugin = plugin;
        this.hubMessages = messages;
        this.combat = combat;
        this.back = back;
        this.authentication = authentication;
        cooldownUntil = new NamespacedKey(plugin, "home_cooldown_until");
    }

    void setHome(Player player) {
        if (!canStart(player)) return;
        if (!isSurvivalWorld(player.getWorld())) {
            player.sendMessage(Component.text(hubMessages.text(player, "home-overworld-only"), NamedTextColor.RED));
            return;
        }
        Block bed = nearestBed(player.getLocation(), Math.clamp(
                plugin.getConfig().getInt("home.bed-search-radius", 4), 1, 8));
        if (bed == null) {
            player.sendMessage(Component.text(hubMessages.text(player, "home-bed-required"), NamedTextColor.YELLOW));
            return;
        }
        player.setRespawnLocation(bed.getLocation(), false);
        player.sendMessage(Component.text(hubMessages.text(player, "home-set"), NamedTextColor.GREEN));
    }

    void toHome(Player player) {
        if (!canStart(player)) return;
        if (!isSurvivalWorld(player.getWorld())) {
            player.sendMessage(Component.text(hubMessages.text(player, "home-origin-overworld"), NamedTextColor.RED));
            return;
        }
        Location target = player.getRespawnLocation();
        if (!basicBedTarget(target)) {
            player.sendMessage(Component.text(hubMessages.text(player, "home-missing"), NamedTextColor.YELLOW));
            return;
        }
        long remaining = cooldownRemaining(player);
        if (remaining > 0L && !player.hasPermission("rpg.hub.home.bypass")) {
            player.sendMessage(Component.text(hubMessages.text(player, "home-cooldown",
                    "minutes", Math.max(1L, (remaining + 59L) / 60L)), NamedTextColor.YELLOW));
            return;
        }
        int warmupSeconds = Math.clamp(plugin.getConfig().getInt("home.warmup-seconds", 5), 1, 30);
        long generation = authentication.token(player.getUniqueId());
        if (!authentication.valid(player.getUniqueId(), generation)) return;
        PendingHome request = new PendingHome(
                UUID.randomUUID(), generation, player.getLocation().clone(), target.clone());
        if (pending.putIfAbsent(player.getUniqueId(), request) != null) {
            player.sendMessage(Component.text(hubMessages.text(player, "home-already-warming"), NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text(hubMessages.text(player, "home-warmup",
                "seconds", warmupSeconds), NamedTextColor.AQUA));
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> finishWarmup(player, request), warmupSeconds * 20L);
    }

    private void finishWarmup(Player player, PendingHome request) {
        UUID playerId = player.getUniqueId();
        if (!currentSession(player) || !request.equals(pending.get(playerId))) return;
        long generation = request.generation();
        if (!authentication.valid(playerId, generation)) {
            pending.remove(playerId, request);
            return;
        }
        if (combat.inCombat(player.getUniqueId())) {
            pending.remove(playerId, request);
            combatBlocked(player);
            return;
        }
        if (!samePosition(player.getLocation(), request.startedAt(), movementToleranceSquared())) {
            pending.remove(playerId, request);
            player.sendMessage(Component.text(hubMessages.text(player, "home-cancelled-move"), NamedTextColor.RED));
            return;
        }
        Location currentRespawn = player.getRespawnLocation();
        if (!sameBed(request.target(), currentRespawn) || !basicBedTarget(currentRespawn)) {
            pending.remove(playerId, request);
            player.sendMessage(Component.text(hubMessages.text(player, "home-missing"), NamedTextColor.YELLOW));
            return;
        }
        loadArrivalChunks(currentRespawn).orTimeout(12L, TimeUnit.SECONDS).whenComplete((chunks, error) -> {
                if (!plugin.isEnabled()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!request.equals(pending.get(playerId))
                            || !currentSession(player) || !authentication.valid(playerId, generation)) {
                        cancel(playerId, request);
                        return;
                    }
                    if (error != null || chunks == null || chunks.isEmpty()) {
                        cancel(playerId, request);
                        if (authentication.valid(playerId, generation)) player.sendMessage(Component.text(
                                hubMessages.text(player, error == null ? "home-missing" : "home-failed"),
                                error == null ? NamedTextColor.YELLOW : NamedTextColor.RED));
                        return;
                    }
                    hold(request, chunks);
                    try {
                        if (combat.inCombat(player.getUniqueId())) {
                            cancel(playerId, request);
                            combatBlocked(player);
                            return;
                        }
                        if (!sameBed(request.target(), player.getRespawnLocation())
                                || !validBedTarget(currentRespawn)) {
                            cancel(playerId, request);
                            player.sendMessage(Component.text(
                                    hubMessages.text(player, "home-missing"), NamedTextColor.YELLOW));
                            return;
                        }
                        Location safe = safeArrival(currentRespawn);
                        if (safe == null) {
                            cancel(playerId, request);
                            player.sendMessage(Component.text(
                                    hubMessages.text(player, "home-obstructed"), NamedTextColor.RED));
                            return;
                        }
                        if (!authentication.valid(playerId, generation)
                                || !pending.remove(playerId, request)) return;
                        boolean success = player.teleport(safe, PlayerTeleportEvent.TeleportCause.COMMAND);
                        if (!success || !currentSession(player)
                                || !authentication.valid(playerId, generation)) return;
                        back.remember(player, request.startedAt());
                        setCooldown(player);
                        player.sendMessage(Component.text(
                                hubMessages.text(player, "home-arrived"), NamedTextColor.GREEN));
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

    private void hold(PendingHome request, List<Chunk> chunks) {
        List<Chunk> retained = chunks.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (retained.isEmpty() || requestChunks.putIfAbsent(request.id(), retained) != null) return;
        for (Chunk chunk : retained) {
            if (heldChunks.retain(chunk)) chunk.addPluginChunkTicket(plugin);
        }
    }

    private void release(PendingHome request) {
        List<Chunk> chunks = requestChunks.remove(request.id());
        if (chunks == null) return;
        for (Chunk chunk : chunks) {
            if (heldChunks.release(chunk)) chunk.removePluginChunkTicket(plugin);
        }
    }

    private void cancel(UUID playerId, PendingHome request) {
        pending.remove(playerId, request);
        release(request);
    }

    private boolean canStart(Player player) {
        if (!authentication.authenticated(player.getUniqueId())) return false;
        if (player.getWorld().getName().startsWith("dungeon_")) {
            player.sendMessage(Component.text(hubMessages.text(player, "travel-dungeon-blocked"), NamedTextColor.RED));
            return false;
        }
        if (combat.inCombat(player.getUniqueId())) {
            combatBlocked(player);
            return false;
        }
        return true;
    }

    private void combatBlocked(Player player) {
        player.sendMessage(Component.text(hubMessages.text(player, "travel-combat-blocked",
                "seconds", combat.remainingSeconds(player.getUniqueId())), NamedTextColor.RED));
    }

    private long cooldownRemaining(Player player) {
        long until = player.getPersistentDataContainer().getOrDefault(
                cooldownUntil, PersistentDataType.LONG, 0L);
        long delta = until - System.currentTimeMillis();
        return delta <= 0L ? 0L : Math.floorDiv(delta + 999L, 1_000L);
    }

    private void setCooldown(Player player) {
        long seconds = Math.clamp(plugin.getConfig().getLong("home.cooldown-seconds", 600L), 0L, 86_400L);
        if (seconds == 0L) player.getPersistentDataContainer().remove(cooldownUntil);
        else player.getPersistentDataContainer().set(cooldownUntil, PersistentDataType.LONG,
                Instant.now().plusSeconds(seconds).toEpochMilli());
        player.saveData();
    }

    private CompletableFuture<List<Chunk>> loadArrivalChunks(Location target) {
        return GeneratedChunkLoads.square(target.getWorld(), target.getBlockX() >> 4,
                target.getBlockZ() >> 4, 1);
    }

    private Location safeArrival(Location bed) {
        for (int radius = 1; radius <= 2; radius++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) continue;
                        Block feet = bed.getWorld().getBlockAt(
                                bed.getBlockX() + x, bed.getBlockY() + y, bed.getBlockZ() + z);
                        if (!safeFeet(feet)) continue;
                        Location result = feet.getLocation().add(0.5, 0.05, 0.5);
                        result.setYaw(bed.getYaw());
                        result.setPitch(0.0F);
                        if (bed.getWorld().getWorldBorder().isInside(result)) return result;
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

    private boolean validBedTarget(Location target) {
        return basicBedTarget(target) && nearestBed(target, 2) != null;
    }

    private boolean basicBedTarget(Location target) {
        return target != null && target.getWorld() != null
                && isSurvivalWorld(target.getWorld());
    }

    private boolean isSurvivalWorld(World world) {
        return world != null && HomeWorldRules.isSurvivalWorld(
                world.getName(), world.getEnvironment() == World.Environment.NORMAL,
                plugin.getConfig().getString("hub.wilderness.world", "world"));
    }

    private Block nearestBed(Location center, int radius) {
        Block closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block candidate = center.getWorld().getBlockAt(
                            center.getBlockX() + x, center.getBlockY() + y, center.getBlockZ() + z);
                    if (!Tag.BEDS.isTagged(candidate.getType())) continue;
                    double distance = candidate.getLocation().distanceSquared(center);
                    if (distance < closestDistance) {
                        closestDistance = distance;
                        closest = candidate;
                    }
                }
            }
        }
        return closest;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PendingHome request = pending.get(event.getPlayer().getUniqueId());
        if (request == null || samePosition(event.getTo(), request.startedAt(), movementToleranceSquared())) return;
        if (pending.remove(event.getPlayer().getUniqueId(), request)) {
            release(request);
            event.getPlayer().sendMessage(Component.text(
                    hubMessages.text(event.getPlayer(), "home-cancelled-move"), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        PendingHome request = pending.remove(player.getUniqueId());
        if (request != null) {
            release(request);
            player.sendMessage(Component.text(
                    hubMessages.text(player, "home-cancelled-damage"), NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PendingHome request = pending.remove(event.getPlayer().getUniqueId());
        if (request != null) release(request);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PendingHome request = pending.remove(event.getPlayer().getUniqueId());
        if (request != null) release(request);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        PendingHome request = pending.remove(event.player().getUniqueId());
        if (request != null) release(request);
    }

    private double movementToleranceSquared() {
        double tolerance = Math.clamp(plugin.getConfig().getDouble(
                "home.cancel-movement-blocks", 0.35), 0.05, 1.0);
        return tolerance * tolerance;
    }

    static boolean samePosition(Location current, Location start, double toleranceSquared) {
        return current != null && start != null && current.getWorld() == start.getWorld()
                && TravelRules.withinTolerance(current.getX() - start.getX(), current.getY() - start.getY(),
                current.getZ() - start.getZ(), toleranceSquared);
    }

    private static boolean sameBed(Location expected, Location actual) {
        return expected != null && actual != null && expected.getWorld() == actual.getWorld()
                && expected.getBlockX() == actual.getBlockX()
                && expected.getBlockY() == actual.getBlockY()
                && expected.getBlockZ() == actual.getBlockZ();
    }

    private static boolean currentSession(Player player) {
        return player != null && player.isOnline()
                && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    private record PendingHome(UUID id, long generation, Location startedAt, Location target) { }
}
