package cn.originalrpg.hub;

import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.JourneyStep;
import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.VisualMode;
import cn.originalrpg.api.service.JourneyService;
import cn.originalrpg.api.service.PerformanceService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Short-lived, player-requested hub navigation. The compass and BossBar are
 * client UI; no waypoint entities are created. One shared low-frequency task
 * updates only active navigators and stops completely when the map is empty.
 */
final class HubWayfinder implements Listener {
    private final Plugin plugin;
    private final HubMessages hubMessages;
    private final JourneyService journeys;
    private final Supplier<PerformanceService> performanceSupplier;
    private final Supplier<HubSite> siteSupplier;
    private final AuthenticationSession authentication;
    private final Map<UUID, Navigation> active = new HashMap<>();
    private final Map<UUID, HubRole> pendingHubTravel = new HashMap<>();
    private final Map<UUID, Long> pendingHubGeneration = new HashMap<>();
    private final int durationSeconds;
    private final long lifetimeNanos;
    private final long updateTicks;
    private final double configuredArrivalDistance;
    private final int configuredParticles;
    private final int particlePlayerBudget;
    private BukkitTask updateTask;
    private int particlePhase;

    HubWayfinder(
            Plugin plugin,
            HubMessages messages,
            JourneyService journeys,
            Supplier<PerformanceService> performanceSupplier,
            Supplier<HubSite> siteSupplier, AuthenticationSession authentication
    ) {
        this.plugin = plugin;
        this.hubMessages = messages;
        this.journeys = journeys;
        this.performanceSupplier = performanceSupplier;
        this.siteSupplier = siteSupplier;
        this.authentication = authentication;
        durationSeconds = Math.clamp(plugin.getConfig().getInt("wayfinding.duration-seconds", 60), 10, 90);
        lifetimeNanos = Duration.ofSeconds(durationSeconds).toNanos();
        updateTicks = Math.clamp(plugin.getConfig().getLong("wayfinding.update-ticks", 20L), 10L, 40L);
        configuredArrivalDistance = plugin.getConfig().getDouble("wayfinding.arrival-distance", 2.5);
        configuredParticles = Math.clamp(plugin.getConfig().getInt("wayfinding.particles-per-pulse", 3), 0, 6);
        particlePlayerBudget = Math.clamp(
                plugin.getConfig().getInt("wayfinding.particle-player-budget-per-pulse", 256), 0, 1_024);
    }

    void navigateCurrent(Player player, HubTravel travel) {
        if (!authentication.authenticated(player.getUniqueId())) return;
        journeys.refresh(player);
        JourneyStep step = journeys.progress(player.getUniqueId()).nextStep().orElse(null);
        roleFor(step).ifPresentOrElse(
                role -> navigate(player, role, travel),
                () -> {
                    cancel(player, false);
                    player.sendMessage(Component.text(
                            hubMessages.text(player, "wayfinding.current-no-npc"), NamedTextColor.GOLD));
                    player.performCommand("rpghelp current");
                });
    }

    void navigate(Player player, String requestedRole, HubTravel travel) {
        if (!authentication.authenticated(player.getUniqueId())) return;
        HubRole.parse(requestedRole).ifPresentOrElse(
                role -> navigate(player, role, travel),
                () -> player.sendMessage(Component.text(hubMessages.text(player, "wayfinding.usage"), NamedTextColor.YELLOW)));
    }

    void cancel(Player player, boolean notify) {
        boolean returnContinues = pendingHubTravel.remove(player.getUniqueId()) != null;
        pendingHubGeneration.remove(player.getUniqueId());
        Navigation navigation = active.remove(player.getUniqueId());
        if (navigation != null) release(player, navigation);
        stopIfIdle();
        if (notify) {
            String key = returnContinues ? "wayfinding.cancelled-returning" : "wayfinding.cancelled";
            player.sendActionBar(Component.text(hubMessages.text(player, key), NamedTextColor.GRAY));
        }
    }

    void close() {
        if (updateTask != null) updateTask.cancel();
        updateTask = null;
        pendingHubTravel.clear();
        pendingHubGeneration.clear();
        for (Map.Entry<UUID, Navigation> entry : active.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) release(player, entry.getValue());
        }
        active.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer());
    }

    @EventHandler
    public void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event) {
        clearSession(event.player());
    }

    private void clearSession(Player player) {
        pendingHubTravel.remove(player.getUniqueId());
        pendingHubGeneration.remove(player.getUniqueId());
        Navigation navigation = active.remove(player.getUniqueId());
        if (navigation != null) release(player, navigation);
        stopIfIdle();
    }

    private void navigate(Player player, HubRole role, HubTravel travel) {
        HubSite site = siteSupplier.get();
        if (site == null) {
            player.sendMessage(Component.text(hubMessages.text(player, "building"), NamedTextColor.YELLOW));
            return;
        }
        Navigation navigation = active.remove(player.getUniqueId());
        if (navigation != null) release(player, navigation);
        stopIfIdle();
        if (!site.contains(player.getLocation())) {
            UUID playerId = player.getUniqueId();
            long generation = authentication.token(playerId);
            if (!authentication.valid(playerId, generation)) return;
            HubRole previous = pendingHubTravel.put(player.getUniqueId(), role);
            pendingHubGeneration.put(playerId, generation);
            if (previous != null) {
                player.sendMessage(Component.text(hubMessages.text(player, "wayfinding.retargeted",
                        "name", name(player, role)), NamedTextColor.AQUA));
                return;
            }
            player.sendMessage(Component.text(hubMessages.text(player, "wayfinding.returning", "name", name(player, role)),
                    NamedTextColor.AQUA));
            travel.toHub(player, HubTravel.ReturnSubscriber.WAYFINDER, success -> {
                HubRole latest = pendingHubTravel.remove(playerId);
                Long expected = pendingHubGeneration.remove(playerId);
                if (latest == null || expected == null || expected != generation
                        || !success || !player.isOnline()
                        || !authentication.valid(playerId, generation)) return;
                begin(player, latest);
            });
            return;
        }
        pendingHubTravel.remove(player.getUniqueId());
        begin(player, role);
    }

    private void begin(Player player, HubRole role) {
        HubSite site = siteSupplier.get();
        if (site == null || !site.contains(player.getLocation())) return;
        Location target = npcLocation(site, role);
        double arrivalDistance = arrivalDistance(player);
        double distance = spatialDistance(player.getLocation(), target);
        if (distance <= arrivalDistance) {
            arrived(player, role);
            return;
        }
        Location previousCompass = player.getCompassTarget().clone();
        BossBar bar = BossBar.bossBar(barName(player, role, distance), 0.0F,
                barColor(role), BossBar.Overlay.PROGRESS);
        Navigation navigation = new Navigation(
                role, target, previousCompass, bar, System.nanoTime() + lifetimeNanos, Math.max(distance, arrivalDistance));
        try {
            player.setCompassTarget(target);
            player.showBossBar(bar);
            active.put(player.getUniqueId(), navigation);
        } catch (RuntimeException exception) {
            // Do not leave a changed compass target if a client UI call fails
            // during enable/disable races or an incompatible Paper build.
            try {
                player.hideBossBar(bar);
                player.setCompassTarget(previousCompass);
            } catch (RuntimeException ignored) {
                // The player may already be disconnecting; no server object is retained.
            }
            plugin.getLogger().warning("Could not start hub navigation for " + player.getUniqueId()
                    + ": " + exception.getMessage());
            player.sendMessage(Component.text(hubMessages.text(player, "wayfinding.unavailable"), NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text(hubMessages.text(player, "wayfinding.started",
                        "name", name(player, role),
                        "route", route(player, role),
                        "distance", blocks(distance),
                        "seconds", durationSeconds), NamedTextColor.AQUA));
        float volume = VisualMode.forPlayer(player).volume(0.45);
        if (volume > 0.0F) {
            player.playSound(player.getLocation(), Sound.BLOCK_LODESTONE_PLACE,
                    SoundCategory.PLAYERS, volume, 1.35F);
        }
        // The first frame already has compass, text, sound, and BossBar. Let
        // the shared budgeted pulse own particles so a mass command cannot
        // create an unbounded same-tick burst.
        update(player, navigation, loadState(), false);
        startTask();
    }

    private void startTask() {
        if (updateTask != null || active.isEmpty()) return;
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pulse, updateTicks, updateTicks);
    }

    private void pulse() {
        long now = System.nanoTime();
        ServerLoadState state = loadState();
        int budget = switch (state) {
            case NORMAL -> particlePlayerBudget;
            case PRESSURE -> Math.min(32, particlePlayerBudget);
            case CRITICAL -> 0;
        };
        int stride = particleStride(active.size(), budget);
        int index = 0;
        Iterator<Map.Entry<UUID, Navigation>> iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Navigation> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Navigation navigation = entry.getValue();
            if (player == null || !player.isOnline() || !authentication.authenticated(entry.getKey())) {
                iterator.remove();
                continue;
            }
            try {
                HubSite site = siteSupplier.get();
                if (site == null || !site.contains(player.getLocation())) {
                    iterator.remove();
                    release(player, navigation);
                    continue;
                }
                if (now >= navigation.deadlineNanos) {
                    iterator.remove();
                    release(player, navigation);
                    player.sendActionBar(Component.text(hubMessages.text(player, "wayfinding.expired",
                            "name", name(player, navigation.role)), NamedTextColor.GRAY));
                    continue;
                }
                double distance = spatialDistance(player.getLocation(), navigation.target);
                if (distance <= arrivalDistance(player)) {
                    iterator.remove();
                    release(player, navigation);
                    arrived(player, navigation.role);
                    continue;
                }
                boolean particlesAllowed = budget > 0 && Math.floorMod(index + particlePhase, stride) == 0;
                update(player, navigation, state, particlesAllowed);
            } catch (RuntimeException exception) {
                // One stale player/world state must not abort navigation for
                // every other active player sharing this scheduler task.
                iterator.remove();
                release(player, navigation);
                plugin.getLogger().warning("Stopped invalid hub navigation for " + player.getUniqueId()
                        + ": " + exception.getMessage());
            }
            index++;
        }
        particlePhase = stride <= 1 ? 0 : Math.floorMod(particlePhase + 1, stride);
        stopIfIdle();
    }

    private void update(
            Player player, Navigation navigation, ServerLoadState state, boolean particlesAllowed
    ) {
        double distance = spatialDistance(player.getLocation(), navigation.target);
        int roundedDistance = blocks(distance);
        double completion = 1.0 - distance / navigation.startDistance;
        float progress = (float) Math.clamp(completion, 0.02, 0.98);
        // A stationary navigator does not need an identical BossBar packet
        // every second. Distance and progress change together in this hub.
        if (roundedDistance != navigation.lastDistanceBlocks
                || Math.abs(progress - navigation.lastProgress) >= 0.005F) {
            navigation.bar.name(barName(player, navigation.role, distance));
            navigation.bar.progress(progress);
            navigation.lastDistanceBlocks = roundedDistance;
            navigation.lastProgress = progress;
        }
        if (!particlesAllowed) return;
        int particles = VisualMode.forPlayer(player).particles(configuredParticles);
        if (state == ServerLoadState.CRITICAL) particles = 0;
        else if (state == ServerLoadState.PRESSURE) particles = Math.min(1, particles);
        if (particles > 0) directionParticles(player, navigation.target, navigation.role, particles);
    }

    private void directionParticles(Player player, Location target, HubRole role, int count) {
        Location origin = player.getEyeLocation().add(0.0, -0.65, 0.0);
        Vector direction = target.toVector().subtract(origin.toVector());
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.01) return;
        direction.normalize();
        Particle.DustOptions dust = new Particle.DustOptions(role.color(), 0.85F);
        for (int index = 0; index < count; index++) {
            Location point = origin.clone().add(direction.clone().multiply(1.35 + index * 0.9));
            player.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void release(Player player, Navigation navigation) {
        try {
            player.hideBossBar(navigation.bar);
        } catch (RuntimeException ignored) {
            // Adventure already drops audiences on disconnect; continue so
            // the compass restoration is still attempted.
        }
        Location previous = navigation.previousCompassTarget;
        if (previous.getWorld() != null) {
            try {
                Location current = player.getCompassTarget();
                if (!ownsCompassTarget(current, navigation.target)) return;
                player.setCompassTarget(previous);
            } catch (RuntimeException ignored) {
                // A disconnect/world-unload race owns no plugin-side resource.
            }
        }
    }

    private void arrived(Player player, HubRole role) {
        player.sendActionBar(Component.text(hubMessages.text(player, "wayfinding.arrived",
                "name", name(player, role)), NamedTextColor.GREEN));
        float volume = VisualMode.forPlayer(player).volume(0.55);
        if (volume > 0.0F) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING,
                    SoundCategory.PLAYERS, volume, 1.55F);
        }
    }

    private void stopIfIdle() {
        if (!active.isEmpty() || updateTask == null) return;
        updateTask.cancel();
        updateTask = null;
    }

    private ServerLoadState loadState() {
        PerformanceService performance = performanceSupplier.get();
        if (performance == null) return ServerLoadState.NORMAL;
        try {
            return performance.state();
        } catch (RuntimeException ignored) {
            return ServerLoadState.PRESSURE;
        }
    }

    private Component barName(Player player, HubRole role, double distance) {
        return Component.text(hubMessages.text(player, "wayfinding.bar",
                "name", name(player, role),
                "distance", blocks(distance),
                "route", route(player, role)), NamedTextColor.WHITE);
    }

    private String name(Player player, HubRole role) {
        return hubMessages.text(player, "wayfinding.roles." + role.id());
    }

    private String route(Player player, HubRole role) {
        return hubMessages.text(player, "wayfinding.routes." + role.id());
    }

    private static int blocks(double distance) {
        return Math.max(0, (int) Math.ceil(distance));
    }

    private static Location npcLocation(HubSite site, HubRole role) {
        return new Location(site.world(), site.centerX() + role.offsetX() + 0.5,
                site.baseY() + 1.05, site.centerZ() + role.offsetZ() + 0.5);
    }

    private double arrivalDistance(Player player) {
        double interactionRange = 3.0;
        try {
            AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
            if (attribute != null) interactionRange = attribute.getValue();
        } catch (RuntimeException ignored) {
            // Keep the vanilla survival fallback during disconnect or API races.
        }
        return HubWayfindingPolicy.arrivalDistance(configuredArrivalDistance, interactionRange);
    }

    private static double spatialDistance(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().getUID().equals(to.getWorld().getUID())) return Double.POSITIVE_INFINITY;
        double deltaX = from.getX() - to.getX();
        double deltaY = from.getY() - to.getY();
        double deltaZ = from.getZ() - to.getZ();
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }

    private static boolean ownsCompassTarget(Location current, Location navigation) {
        return current != null && navigation != null
                && current.getWorld() != null && navigation.getWorld() != null
                && HubWayfindingPolicy.ownsCompassTarget(
                current.getWorld().getUID(), current.getX(), current.getZ(),
                navigation.getWorld().getUID(), navigation.getX(), navigation.getZ());
    }

    static Optional<HubRole> roleFor(JourneyStep step) {
        if (step == null) return Optional.of(HubRole.QUESTS);
        return switch (step) {
            case HUB -> Optional.of(HubRole.GUIDE);
            case CLASS, ATTRIBUTE -> Optional.of(HubRole.CLASS);
            case EXPLORE -> Optional.of(HubRole.WILDERNESS);
            case HUNT, REVEAL -> Optional.empty();
            case FORGE -> Optional.of(HubRole.FORGE);
            case DUNGEON -> Optional.of(HubRole.DUNGEON);
        };
    }

    static int particleStride(int activePlayers, int budget) {
        if (activePlayers <= 0 || budget <= 0) return 1;
        return Math.max(1, (activePlayers + budget - 1) / budget);
    }

    private static BossBar.Color barColor(HubRole role) {
        return switch (role) {
            case CLASS, SKILLS, AI -> BossBar.Color.PURPLE;
            case FORGE, ECONOMY -> BossBar.Color.YELLOW;
            case ENHANCEMENT -> BossBar.Color.BLUE;
            case DUNGEON -> BossBar.Color.RED;
            case MARKET, WILDERNESS, QUESTS -> BossBar.Color.GREEN;
            case GUIDE -> BossBar.Color.WHITE;
        };
    }

    private static final class Navigation {
        private final HubRole role;
        private final Location target;
        private final Location previousCompassTarget;
        private final BossBar bar;
        private final long deadlineNanos;
        private final double startDistance;
        private int lastDistanceBlocks = -1;
        private float lastProgress = -1.0F;

        private Navigation(
                HubRole role,
                Location target,
                Location previousCompassTarget,
                BossBar bar,
                long deadlineNanos,
                double startDistance
        ) {
            this.role = role;
            this.target = target;
            this.previousCompassTarget = previousCompassTarget;
            this.bar = bar;
            this.deadlineNanos = deadlineNanos;
            this.startDistance = startDistance;
        }
    }
}
