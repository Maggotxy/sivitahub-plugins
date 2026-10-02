package cn.originalrpg.integrations;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.service.CurrencyExchangeService;
import cn.originalrpg.api.service.OptionalServices;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.service.ClassBranchService;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.GameplayHudService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RpgIntegrationsPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, RpgSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refreshInFlight = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<UUID> refreshQueue = new ArrayDeque<>();
    private final Set<UUID> queuedRefreshes = new HashSet<>();
    private ProfileService profiles;
    private CurrencyExchangeService wealth;
    private ClassBranchService classBranches;
    private MessageService messages;
    private LuckPermsContextBridge luckPermsBridge;
    private BetterHudGameplayHudService gameplayHud;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        profiles = Objects.requireNonNull(
                getServer().getServicesManager().load(ProfileService.class), "ProfileService");
        wealth = Objects.requireNonNull(
                getServer().getServicesManager().load(CurrencyExchangeService.class), "CurrencyExchangeService");
        classBranches = Objects.requireNonNull(
                getServer().getServicesManager().load(ClassBranchService.class), "ClassBranchService");
        messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        getServer().getPluginManager().registerEvents(this, this);

        if (getServer().getPluginManager().isPluginEnabled("BetterHud")) {
            gameplayHud = new BetterHudGameplayHudService(this);
            getServer().getPluginManager().registerEvents(gameplayHud, this);
            getServer().getServicesManager().register(
                    GameplayHudService.class, gameplayHud, this, ServicePriority.Normal);
            getLogger().info("Registered player-safe gameplay HUD preferences.");
        } else {
            getLogger().info("BetterHud is absent; gameplay HUD preferences are unavailable.");
        }

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            long staleSeconds = Math.max(5L, getConfig().getLong("placeholder-cache.stale-after-seconds", 30L));
            if (new SivitaPlaceholderExpansion(
                    this, snapshots, Duration.ofSeconds(staleSeconds), messages).register()) {
                getLogger().info("Registered non-blocking %sivita_*% PlaceholderAPI expansion.");
            } else {
                getLogger().warning("PlaceholderAPI rejected the Sivita expansion registration.");
            }
        } else {
            getLogger().info("PlaceholderAPI is absent; the optional expansion is disabled.");
        }

        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            luckPermsBridge = LuckPermsContextBridge.register(this, snapshots);
            getLogger().info("Registered LuckPerms contexts: sivita-class, sivita-branch and sivita-ascension.");
        } else {
            getLogger().info("LuckPerms is absent; RPG context calculation is disabled.");
        }

        long legacyRefreshTicks = getConfig().getLong("placeholder-cache.refresh-ticks", 300L);
        long refreshTicks = Math.max(100L,
                getConfig().getLong("placeholder-cache.refresh-interval-ticks", legacyRefreshTicks));
        int playersPerTick = Math.clamp(
                getConfig().getInt("placeholder-cache.players-per-tick", 2), 1, 32);
        getServer().getScheduler().runTaskTimer(this, () -> onlinePlayers.forEach(playerId -> {
            if (queuedRefreshes.add(playerId)) refreshQueue.addLast(playerId);
        }), refreshTicks, refreshTicks);
        getServer().getScheduler().runTaskTimer(this, () -> {
            PerformanceService performance = OptionalServices.enabled(this, PerformanceService.class);
            for (int index = 0; index < playersPerTick; index++) {
                if (performance != null && !performance.allowBackgroundWork()) break;
                UUID playerId = refreshQueue.pollFirst();
                if (playerId == null) break;
                queuedRefreshes.remove(playerId);
                Player player = getServer().getPlayer(playerId);
                if (player != null && player.isOnline()) refresh(player);
            }
        }, 1L, 1L);
    }

    @EventHandler
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        onlinePlayers.add(event.player().getUniqueId());
        if (gameplayHud != null) gameplayHud.reapply(event.player());
        refresh(event.player());
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
        UUID playerId = player.getUniqueId();
        onlinePlayers.remove(playerId);
        snapshots.remove(playerId);
        queuedRefreshes.remove(playerId);
        refreshQueue.removeIf(playerId::equals);
        if (luckPermsBridge != null) luckPermsBridge.signal(player);
    }

    private void refresh(Player player) {
        UUID playerId = player.getUniqueId();
        onlinePlayers.add(playerId);
        if (!refreshInFlight.add(playerId)) return;
        profiles.get(playerId).thenCombine(wealth.balance(playerId), ProfileAndWealth::new)
                .thenCombine(classBranches.branches(playerId), (data, branches) ->
                        RpgSnapshot.from(data.profile(), data.wealth(), branches, Instant.now()))
                .whenComplete((snapshot, error) -> {
            refreshInFlight.remove(playerId);
            if (error != null) {
                getLogger().warning("Placeholder snapshot refresh failed for " + playerId + ": " + error.getMessage());
                return;
            }
            if (onlinePlayers.contains(playerId)) {
                snapshots.put(playerId, snapshot);
                if (luckPermsBridge != null) Bukkit.getScheduler().runTask(this, () -> {
                    Player current = getServer().getPlayer(playerId);
                    if (current != null && current.isOnline()) luckPermsBridge.signal(current);
                });
            }
        });
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (luckPermsBridge != null) luckPermsBridge.close();
        snapshots.clear();
        onlinePlayers.clear();
        refreshInFlight.clear();
        refreshQueue.clear();
        queuedRefreshes.clear();
        Bukkit.getScheduler().cancelTasks(this);
    }

    private record ProfileAndWealth(
            cn.originalrpg.api.model.PlayerProfile profile,
            cn.originalrpg.api.model.WealthBalance wealth
    ) {
    }
}
