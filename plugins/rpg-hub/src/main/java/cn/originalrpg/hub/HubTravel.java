package cn.originalrpg.hub;

import cn.originalrpg.api.service.CombatStateService;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.rules.PlayerJourneyFacts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class HubTravel {
    enum ReturnSubscriber {
        COMMAND,
        WAYFINDER,
        VISITOR
    }

    private final Plugin plugin;
    private final HubMessages hubMessages;
    private final CombatStateService combat;
    private final JourneyService journeys;
    private final BackTravel back;
    private final Supplier<HubSite> siteSupplier;
    private final AuthenticationSession authentication;
    private final Map<UUID, Instant> hubCooldown = new ConcurrentHashMap<>();
    private final HubReturnCoordinator<ReturnSubscriber> hubReturns = new HubReturnCoordinator<>();
    private final Map<UUID, Long> searching = new ConcurrentHashMap<>();
    private final ReferenceCounter<Chunk> heldWildChunks = new ReferenceCounter<>();
    private final AtomicInteger activeWildSearches = new AtomicInteger();

    HubTravel(
            Plugin plugin, HubMessages messages, CombatStateService combat,
            JourneyService journeys, BackTravel back, Supplier<HubSite> siteSupplier,
            AuthenticationSession authentication
    ) {
        this.plugin = plugin;
        this.hubMessages = messages;
        this.combat = combat;
        this.journeys = journeys;
        this.back = back;
        this.siteSupplier = siteSupplier;
        this.authentication = authentication;
    }

    void toHub(Player player) {
        toHub(player, ReturnSubscriber.COMMAND, ignored -> { });
    }

    void toHub(Player player, ReturnSubscriber subscriber, Consumer<Boolean> completion) {
        UUID playerId = player.getUniqueId();
        long generation = authentication.token(playerId);
        if (!authentication.valid(playerId, generation)) return;
        HubReturnCoordinator.Registration registration = hubReturns.register(playerId, subscriber, completion);
        if (registration == HubReturnCoordinator.Registration.JOINED) {
            if (subscriber == ReturnSubscriber.COMMAND) {
                player.sendMessage(Component.text(
                        hubMessages.text(player, "return-in-progress"), NamedTextColor.YELLOW));
            }
            return;
        }
        if (!travelAllowed(player)) {
            finishHubReturn(playerId, false);
            return;
        }
        HubSite site = siteSupplier.get();
        if (site == null) {
            player.sendMessage(Component.text(hubMessages.text(player, "building"), NamedTextColor.YELLOW));
            finishHubReturn(playerId, false);
            return;
        }
        int cooldown = Math.clamp(plugin.getConfig().getInt("hub.return-cooldown-seconds", 15), 0, 300);
        Instant available = hubCooldown.get(player.getUniqueId());
        long remaining = secondsRemaining(available);
        if (remaining > 0L && !player.hasPermission("rpg.hub.admin")) {
            player.sendMessage(Component.text(hubMessages.text(player, "return-cooldown", "seconds", remaining), NamedTextColor.YELLOW));
            finishHubReturn(playerId, false);
            return;
        }
        try {
            Location origin = player.getLocation().clone();
            boolean success = player.teleport(site.arrival(), PlayerTeleportEvent.TeleportCause.COMMAND);
            if (!success || !player.isOnline() || !authentication.valid(playerId, generation)) {
                if (player.isOnline() && authentication.valid(playerId, generation)) player.sendMessage(Component.text(
                        hubMessages.text(player, "travel-failed"), NamedTextColor.RED));
                finishHubReturn(playerId, false);
                return;
            }
            back.remember(player, origin);
            hubCooldown.put(playerId, Instant.now().plusSeconds(cooldown));
            player.sendMessage(Component.text(hubMessages.text(player, "teleported"), NamedTextColor.GREEN));
            finishHubReturn(playerId, true);
        } catch (RuntimeException exception) {
            player.sendMessage(Component.text(hubMessages.text(player, "travel-failed"), NamedTextColor.RED));
            finishHubReturn(playerId, false);
        }
    }

    void close() {
        for (Chunk chunk : heldWildChunks.retained()) chunk.removePluginChunkTicket(plugin);
        heldWildChunks.clear();
        hubReturns.clear();
        searching.clear();
        activeWildSearches.set(0);
        hubCooldown.clear();
    }

    void revoke(UUID playerId) {
        if (playerId == null) return;
        hubReturns.cancel(playerId);
        finishWildSearch(playerId, null);
    }

    boolean authenticated(UUID playerId) {
        return authentication.authenticated(playerId);
    }

    void toWilderness(Player player) {
        if (!travelAllowed(player)) return;
        UUID playerId = player.getUniqueId();
        long generation = authentication.token(playerId);
        if (!authentication.valid(playerId, generation)) return;
        HubSite site = siteSupplier.get();
        if (site == null || !site.contains(player.getLocation())) {
            player.sendMessage(Component.text(hubMessages.text(player, "wild-only-hub"), NamedTextColor.RED));
            return;
        }
        if (searching.putIfAbsent(playerId, generation) != null) {
            player.sendMessage(Component.text(
                    hubMessages.text(player, "wild-in-progress"), NamedTextColor.YELLOW));
            return;
        }
        int maximumConcurrent = Math.clamp(plugin.getConfig().getInt(
                "hub.wilderness.max-concurrent-searches", 2), 1, 8);
        if (activeWildSearches.incrementAndGet() > maximumConcurrent) {
            activeWildSearches.decrementAndGet();
            searching.remove(playerId, generation);
            player.sendMessage(Component.text(hubMessages.text(player, "wild-busy"), NamedTextColor.YELLOW));
            return;
        }
        player.sendMessage(Component.text(hubMessages.text(player, "wild-searching"), NamedTextColor.AQUA));
        int attempts = Math.clamp(plugin.getConfig().getInt("hub.wilderness.attempts", 24), 3, 64);
        seek(player, site, player.getLocation().clone(), attempts, generation);
    }

    private void seek(Player player, HubSite site, Location origin, int attemptsLeft, long generation) {
        UUID playerId = player.getUniqueId();
        if (!player.isOnline() || !authentication.valid(playerId, generation) || attemptsLeft <= 0) {
            finishWildSearch(playerId, generation);
            if (player.isOnline() && authentication.valid(playerId, generation)) player.sendMessage(Component.text(
                    hubMessages.text(player, "wild-failed"), NamedTextColor.RED));
            return;
        }
        int minimum = Math.clamp(plugin.getConfig().getInt("hub.wilderness.minimum-radius", 650), 128, 10_000);
        int maximum = Math.clamp(plugin.getConfig().getInt("hub.wilderness.maximum-radius", 1_600), minimum, 20_000);
        World wilderness = plugin.getServer().getWorld(plugin.getConfig().getString(
                "hub.wilderness.world", "world"));
        if (wilderness == null) {
            finishWildSearch(playerId, generation);
            player.sendMessage(Component.text(hubMessages.text(player, "wild-world-unavailable"), NamedTextColor.RED));
            return;
        }
        int centerX = plugin.getConfig().getInt("hub.wilderness.center-x", 0);
        int centerZ = plugin.getConfig().getInt("hub.wilderness.center-z", 0);
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        int distance = ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
        int x = centerX + (int) Math.round(Math.cos(angle) * distance);
        int z = centerZ + (int) Math.round(Math.sin(angle) * distance);
        int preloadRadius = Math.clamp(plugin.getConfig().getInt(
                "hub.wilderness.preload-radius-chunks", 1), 1, 2);
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        Location borderCenter = wilderness.getWorldBorder().getCenter();
        if (!WildernessTravelRules.neighborhoodInsideBorder(
                chunkX, chunkZ, preloadRadius,
                borderCenter.getX(), borderCenter.getZ(), wilderness.getWorldBorder().getSize())) {
            // Reject the candidate before touching any chunk. The configured
            // radius may outlive a seasonal border shrink, and the whole 3x3
            // client landing neighborhood—not only the player's feet—must be
            // inside terrain that operators have opened for this season.
            seek(player, site, origin, attemptsLeft - 1, generation);
            return;
        }

        player.sendActionBar(Component.text(hubMessages.text(player, "wild-loading"), NamedTextColor.AQUA));
        long timeout = Math.clamp(plugin.getConfig().getLong(
                "hub.wilderness.load-timeout-seconds", 12L), 5L, 30L);
        preloadNeighborhood(wilderness, chunkX, chunkZ, preloadRadius)
                .orTimeout(timeout, TimeUnit.SECONDS)
                .whenComplete((chunks, error) -> {
                    if (!plugin.isEnabled()) return;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!authentication.valid(playerId, generation)) {
                            if (chunks != null) releaseChunks(chunks);
                            finishWildSearch(playerId, generation);
                            return;
                        }
                        if (error != null || chunks == null || chunks.isEmpty() || !player.isOnline()) {
                            seek(player, site, origin, attemptsLeft - 1, generation);
                            return;
                        }
                        holdChunks(chunks);
                        Location safe = safeLocation(wilderness, x, z);
                        if (safe == null) {
                            releaseChunks(chunks);
                            seek(player, site, origin, attemptsLeft - 1, generation);
                            return;
                        }
                        if (!authentication.valid(playerId, generation)) {
                            finishWildSearch(playerId, generation);
                            releaseChunks(chunks);
                            return;
                        }
                        boolean arrived = player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                        finishWildSearch(playerId, generation);
                        if (!arrived || !player.isOnline() || !authentication.valid(playerId, generation)) {
                            releaseChunks(chunks);
                            if (player.isOnline() && authentication.valid(playerId, generation)) {
                                player.sendMessage(Component.text(
                                        hubMessages.text(player, "wild-failed"), NamedTextColor.RED));
                            }
                            return;
                        }
                        back.remember(player, origin);
                        boolean firstArrival = PlayerJourneyFacts.recordWildernessArrival(player);
                        if (firstArrival) player.saveData();
                        player.sendMessage(Component.text(hubMessages.text(player, "wild-arrived"), NamedTextColor.GREEN));
                        if (firstArrival) {
                            player.sendMessage(Component.text(
                                            hubMessages.text(player, "wild-first-claim"), NamedTextColor.GOLD)
                                    .append(Component.space())
                                    .append(Component.text("[" + hubMessages.text(player,
                                                    "wild-first-claim-button") + "]", NamedTextColor.AQUA)
                                            .clickEvent(ClickEvent.runCommand("/rpghelp claims"))
                                            .hoverEvent(Component.text(hubMessages.text(player,
                                                    "wild-first-claim-tooltip"), NamedTextColor.GRAY))));
                        }
                        // The guide owns journey completion. Arrival only records
                        // a shared fact and asks it to reconcile the single EXPLORE step.
                        journeys.refresh(player);
                        // Retain the neighborhood briefly so the client can receive it
                        // before normal Paper chunk-ticket rules take over.
                        plugin.getServer().getScheduler().runTaskLater(plugin,
                                () -> releaseChunks(chunks), 100L);
                    });
                });
    }

    private CompletableFuture<List<Chunk>> preloadNeighborhood(
            World world, int centerX, int centerZ, int radius
    ) {
        return GeneratedChunkLoads.square(world, centerX, centerZ, radius);
    }

    private void holdChunks(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            if (chunk != null && heldWildChunks.retain(chunk)) chunk.addPluginChunkTicket(plugin);
        }
    }

    private void releaseChunks(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            if (chunk == null) continue;
            if (heldWildChunks.release(chunk)) chunk.removePluginChunkTicket(plugin);
        }
    }

    private void finishWildSearch(UUID playerId, Long generation) {
        boolean removed = generation == null
                ? searching.remove(playerId) != null
                : searching.remove(playerId, generation);
        if (removed) activeWildSearches.updateAndGet(current -> Math.max(0, current - 1));
    }

    private Location safeLocation(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block ground = world.getBlockAt(x, y, z);
        Block feet = ground.getRelative(0, 1, 0);
        Block head = ground.getRelative(0, 2, 0);
        Material type = ground.getType();
        if (!type.isSolid() || Tag.LEAVES.isTagged(type) || type == Material.MAGMA_BLOCK
                || type == Material.CACTUS || type == Material.POWDER_SNOW || type == Material.CAMPFIRE
                || type == Material.SOUL_CAMPFIRE || !feet.isPassable() || !head.isPassable()) return null;
        return new Location(world, x + 0.5, y + 1.05, z + 0.5, 0.0F, 0.0F);
    }

    private boolean travelAllowed(Player player) {
        if (!authentication.authenticated(player.getUniqueId())) return false;
        if (player.getWorld().getName().startsWith("dungeon_")) {
            player.sendMessage(Component.text(hubMessages.text(player, "travel-dungeon-blocked"), NamedTextColor.RED));
            return false;
        }
        if (combat.inCombat(player.getUniqueId())) {
            player.sendMessage(Component.text(hubMessages.text(player, "travel-combat-blocked",
                    "seconds", combat.remainingSeconds(player.getUniqueId())), NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private void finishHubReturn(UUID playerId, boolean success) {
        for (Consumer<Boolean> callback : hubReturns.finish(playerId)) {
            try {
                callback.accept(success);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Hub return completion failed for " + playerId
                        + ": " + exception.getMessage());
            }
        }
    }

    private static long secondsRemaining(Instant deadline) {
        if (deadline == null) return 0L;
        long delta = deadline.toEpochMilli() - System.currentTimeMillis();
        return delta <= 0L ? 0L : Math.floorDiv(delta + 999L, 1_000L);
    }
}
