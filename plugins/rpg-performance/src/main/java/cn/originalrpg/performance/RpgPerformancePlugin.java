package cn.originalrpg.performance;

import cn.originalrpg.api.model.PerformanceSnapshot;
import cn.originalrpg.api.model.ServerLoadState;
import cn.originalrpg.api.model.PersistenceSnapshot;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.PerformanceService;
import cn.originalrpg.api.service.PersistenceMetricsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RpgPerformancePlugin extends JavaPlugin implements PerformanceService, CommandExecutor, Listener {
    private final Map<UUID, Integer> normalViewDistances = new HashMap<>();
    private final Map<UUID, Integer> normalSimulationDistances = new HashMap<>();
    private final AtomicBoolean statusWriteInFlight = new AtomicBoolean();
    private final AtomicBoolean acceptingStatusWrites = new AtomicBoolean();
    private volatile PerformanceSnapshot snapshot = new PerformanceSnapshot(
            ServerLoadState.CRITICAL, 20.0, 0.0, 0, 0, 0, 0L, 0L,
            PersistenceSnapshot.unavailable(), false, Instant.EPOCH);
    private MessageService messages;
    private LoadPolicy loadPolicy = LoadPolicy.defaults();
    private AuthoritativeLoadState loadState = new AuthoritativeLoadState(
            new LoadStateHysteresis(3, 12));
    private long lastStatusWrite;
    private boolean rejectCriticalJoins;
    private String criticalJoinMessage;
    private PerformanceStatusStore statusStore;
    private ExecutorService statusExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = Objects.requireNonNull(
                getServer().getServicesManager().load(MessageService.class), "MessageService");
        rejectCriticalJoins = getConfig().getBoolean("overload-protection.reject-joins-while-critical", true);
        criticalJoinMessage = getConfig().getString("overload-protection.join-message",
                "Server load protection is active; try again shortly.");
        loadPolicy = LoadPolicy.fromConfig(getConfig());
        loadState = new AuthoritativeLoadState(new LoadStateHysteresis(
                getConfig().getInt("sampling.samples-to-degrade", 3),
                getConfig().getInt("sampling.samples-to-recover", 12)));
        if (getConfig().getBoolean("status-file.enabled", true)) {
            statusStore = new PerformanceStatusStore(getDataFolder().toPath());
            try {
                statusStore.activate();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not activate the performance status store", exception);
            }
            statusExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                    .daemon(true).name("rpg-performance-status").factory());
            statusWriteInFlight.set(false);
            lastStatusWrite = 0L;
            acceptingStatusWrites.set(true);
        }
        getServer().getServicesManager().register(
                PerformanceService.class, this, this, ServicePriority.Normal);
        Objects.requireNonNull(getCommand("rpgperf"), "rpgperf command").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        long interval = Math.clamp(getConfig().getLong("sampling.interval-ticks", 100L), 20L, 1200L);
        // Replace a previous process's status immediately; never leave a
        // recently written but stale healthy ledger snapshot visible.
        sample();
        getServer().getScheduler().runTaskTimer(this, this::sample, interval, interval);
    }

    @Override
    public void onDisable() {
        acceptingStatusWrites.set(false);
        if (statusStore != null) {
            try {
                statusStore.deactivate();
            } catch (IOException exception) {
                getLogger().warning("Could not fence the performance status store: " + exception.getMessage());
            }
        }
        closeStatusExecutor();
        restoreDistances();
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public PerformanceSnapshot snapshot() {
        return snapshot;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerConnectionValidateLoginEvent event) {
        if (!event.isAllowed() || snapshot.state() != ServerLoadState.CRITICAL || !rejectCriticalJoins) return;
        event.kickMessage(Component.text(criticalJoinMessage, NamedTextColor.YELLOW));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        PerformanceSnapshot current = snapshot;
        String state = messages.text(sender, "performance.states."
                + current.state().name().toLowerCase(Locale.ROOT));
        sender.sendMessage(Component.text(messages.text(sender, "performance.status", Map.ofEntries(
                Map.entry("state", state),
                Map.entry("tps", String.format(Locale.ROOT, "%.2f", current.tps())),
                Map.entry("mspt", String.format(Locale.ROOT, "%.2f", current.averageMspt())),
                Map.entry("players", current.onlinePlayers()),
                Map.entry("chunks", current.loadedChunks()),
                Map.entry("entities", current.entities()),
                Map.entry("jdbc_active", current.persistence().activeConnections()),
                Map.entry("jdbc_total", current.persistence().totalConnections()),
                Map.entry("jdbc_queue", current.persistence().executorQueueSize()),
                Map.entry("heap", String.format(Locale.ROOT, "%.1f", current.usedHeapBytes() / 1_073_741_824.0)),
                Map.entry("heap_max", String.format(Locale.ROOT, "%.1f", current.maximumHeapBytes() / 1_073_741_824.0))
        )), current.state() == ServerLoadState.NORMAL ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        return true;
    }

    private void sample() {
        double[] recentTps = Bukkit.getTPS();
        double tps = recentTps.length == 0 ? 20.0 : Math.min(20.0, recentTps[0]);
        double mspt = Math.max(0.0, Bukkit.getAverageTickTime());
        int chunks = 0;
        int entities = 0;
        for (World world : Bukkit.getWorlds()) {
            // getLoadedChunks() allocates and fills an array for every world.
            // Paper exposes the same count directly, which keeps this recurring
            // five-second telemetry sample allocation-free.
            chunks += world.getChunkCount();
            entities += world.getEntityCount();
        }
        Runtime runtime = Runtime.getRuntime();
        var registration = getServer().getServicesManager().getRegistration(PersistenceMetricsService.class);
        PersistenceHealth persistenceHealth = registration == null
                ? PersistenceHealth.unavailable()
                : PersistenceHealth.capture(registration.getPlugin().isEnabled(), registration.getProvider());
        PersistenceSnapshot persistence = persistenceHealth.snapshot();
        ServerLoadState measured = loadPolicy.classify(tps, mspt, persistence);
        ServerLoadState previous = snapshot.state();
        // The overload hysteresis protects against noisy TPS samples. A missing
        // authoritative ledger is not noisy and must fail health immediately.
        ServerLoadState state = loadState.update(
                previous, measured, persistenceHealth.ledgerAvailable());
        if (state != previous) {
            if (state.ordinal() > previous.ordinal()) {
                getLogger().warning("Server load state changed from " + previous + " to " + state);
            } else {
                getLogger().info("Server load state recovered from " + previous + " to " + state);
            }
        }
        snapshot = new PerformanceSnapshot(state, tps, mspt, Bukkit.getOnlinePlayers().size(), chunks, entities,
                runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), persistence,
                persistenceHealth.ledgerAvailable(),
                Instant.now());
        if (state == ServerLoadState.CRITICAL) applyCriticalDistances();
        else if (snapshot.state() == ServerLoadState.NORMAL) restoreDistances();
        maybeWriteStatusFile();
    }


    private void applyCriticalDistances() {
        if (!getConfig().getBoolean("overload-protection.enabled", true)) return;
        int view = Math.clamp(getConfig().getInt("overload-protection.critical-view-distance", 5), 2, 32);
        int simulation = Math.clamp(
                getConfig().getInt("overload-protection.critical-simulation-distance", 3), 2, view);
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().startsWith("dungeon_instance_")) continue;
            normalViewDistances.putIfAbsent(world.getUID(), world.getViewDistance());
            normalSimulationDistances.putIfAbsent(world.getUID(), world.getSimulationDistance());
            if (world.getViewDistance() > view) world.setViewDistance(view);
            if (world.getSimulationDistance() > simulation) world.setSimulationDistance(simulation);
        }
    }

    private void restoreDistances() {
        for (World world : Bukkit.getWorlds()) {
            Integer view = normalViewDistances.remove(world.getUID());
            Integer simulation = normalSimulationDistances.remove(world.getUID());
            if (view != null) world.setViewDistance(view);
            if (simulation != null) world.setSimulationDistance(simulation);
        }
    }

    private void maybeWriteStatusFile() {
        if (!acceptingStatusWrites.get() || statusExecutor == null || statusStore == null) return;
        long intervalMillis = Math.clamp(
                getConfig().getLong("status-file.interval-seconds", 15L), 5L, 300L) * 1000L;
        long now = System.currentTimeMillis();
        if (now - lastStatusWrite < intervalMillis || !statusWriteInFlight.compareAndSet(false, true)) return;
        lastStatusWrite = now;
        PerformanceSnapshot value = snapshot;
        PerformanceStatusStore store = statusStore;
        ExecutorService executor = statusExecutor;
        try {
            executor.execute(() -> {
                try {
                    store.write(PerformanceStatusJson.encode(value));
                } catch (IOException exception) {
                    if (acceptingStatusWrites.get()) {
                        getLogger().warning("Could not write performance status: " + exception.getMessage());
                    }
                } finally {
                    statusWriteInFlight.set(false);
                }
            });
        } catch (RejectedExecutionException exception) {
            statusWriteInFlight.set(false);
        }
    }

    private void closeStatusExecutor() {
        ExecutorService executor = statusExecutor;
        statusExecutor = null;
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                    getLogger().warning("Performance status writer did not stop within the shutdown bound");
                }
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
