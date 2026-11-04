package cn.originalrpg.core;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.model.PlayerSessionLease;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.core.persistence.JdbcSessionLease;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acquires authority before Paper loads player state, then renews and releases
 * that exact connection token. JDBC always runs on the authority's bounded
 * executor. Only the asynchronous pre-login event waits, with a fixed timeout;
 * main-thread joins, authentication, kicks, and disconnects never do JDBC.
 * This adapter is not an inventory transport or a Velocity handoff adapter.
 */
public final class SessionLeaseListener implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final JdbcSessionLease sessions;
    private final MessageService messages;
    private final long admissionTimeoutMillis;
    private final Map<UUID, LoginAttempt> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ConnectionState> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private BukkitTask heartbeat;

    public SessionLeaseListener(Plugin plugin, JdbcSessionLease sessions, MessageService messages,
                                Duration admissionTimeout) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.sessions = java.util.Objects.requireNonNull(sessions, "sessions");
        this.messages = java.util.Objects.requireNonNull(messages, "messages");
        admissionTimeoutMillis = Math.clamp(admissionTimeout.toMillis(), 250L, 15_000L);
    }

    public void start() {
        long ticks = Math.max(20L, sessions.duration().toSeconds() * 20L / 3L);
        heartbeat = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::renewConnections,
                ticks, ticks);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        UUID id = event.getUniqueId();
        LoginAttempt attempt = new LoginAttempt(UUID.randomUUID());
        if (closed.get() || pending.putIfAbsent(id, attempt) != null) {
            reject(event, "session-lease.no-valid-lease");
            return;
        }
        CompletionStage<Optional<PlayerSessionLease>> acquisition = sessions.acquire(id, attempt.token);
        attempt.acquisition = acquisition;
        acquisition.whenComplete((lease, error) -> {
            if (error == null && lease.isPresent() && (closed.get() || pending.get(id) != attempt)) {
                sessions.release(lease.orElseThrow());
            }
        });
        try {
            Optional<PlayerSessionLease> lease = acquisition.toCompletableFuture()
                    .get(admissionTimeoutMillis, TimeUnit.MILLISECONDS);
            if (lease.isEmpty() || closed.get() || pending.get(id) != attempt) {
                abandon(id, attempt);
                reject(event, "session-lease.held-by-other-node");
                return;
            }
            attempt.lease = lease.orElseThrow();
            // Acquiring a connection is not completing AuthMe authentication.
            sessions.forget(attempt.lease);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            abandon(id, attempt);
            plugin.getLogger().warning("Player lease admission failed closed for " + id + ": "
                    + error.getClass().getSimpleName());
            reject(event, "session-lease.no-valid-lease");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        LoginAttempt attempt = pending.get(event.getPlayer().getUniqueId());
        if (closed.get() || attempt == null || attempt.lease == null) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    Component.text(messages.text(null, "session-lease.no-valid-lease")));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginResult(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.ALLOWED) return;
        UUID id = event.getPlayer().getUniqueId();
        LoginAttempt attempt = pending.get(id);
        if (attempt != null) abandon(id, attempt);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LoginAttempt attempt = pending.remove(player.getUniqueId());
        if (closed.get() || attempt == null || attempt.lease == null) {
            player.kick(Component.text(messages.text(player, "session-lease.no-valid-lease")));
            return;
        }
        ConnectionState state = new ConnectionState(player, attempt.lease);
        ConnectionState old = connections.put(player.getUniqueId(), state);
        if (old != null) closeConnection(old);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        ConnectionState state = connections.get(event.player().getUniqueId());
        if (state == null || state.player != event.player() || state.closed.get() || closed.get()) return;
        state.authenticated = true;
        sessions.remember(state.lease);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRevoked(AuthenticatedPlayerRevokedEvent event) {
        ConnectionState state = connections.get(event.player().getUniqueId());
        if (state == null || state.player != event.player()) return;
        state.authenticated = false;
        sessions.forget(state.lease);
        // Keep the connection lease while its player.dat remains loaded in
        // authentication limbo. Releasing it would admit a second live backend.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ConnectionState state = connections.get(event.getPlayer().getUniqueId());
        if (state != null && state.player == event.getPlayer()) closeConnection(state);
    }

    private void renewConnections() {
        if (closed.get()) return;
        long now = System.nanoTime();
        pending.forEach((id, attempt) -> {
            if (now - attempt.startedNanos > Duration.ofSeconds(30).toNanos()) abandon(id, attempt);
        });
        for (ConnectionState state : connections.values()) {
            if (state.closed.get() || !state.renewing.compareAndSet(false, true)) continue;
            sessions.renew(state.lease).whenComplete((renewed, error) -> {
                state.renewing.set(false);
                if (error != null || renewed.isEmpty()) {
                    closeConnection(state);
                    sync(() -> {
                        if (plugin.getServer().getPlayer(state.player.getUniqueId()) == state.player
                                && state.player.isOnline()) {
                            state.player.kick(Component.text(messages.text(state.player, "session-lease.no-valid-lease")));
                        }
                    });
                    return;
                }
                state.lease = renewed.orElseThrow();
                if (state.closed.get() || closed.get()) {
                    sessions.release(state.lease);
                    return;
                }
                sync(() -> {
                    if (!state.closed.get() && state.authenticated
                            && connections.get(state.player.getUniqueId()) == state
                            && plugin.getServer().getPlayer(state.player.getUniqueId()) == state.player) {
                        sessions.remember(state.lease);
                    }
                });
            });
        }
    }

    private void closeConnection(ConnectionState state) {
        if (!state.closed.compareAndSet(false, true)) return;
        connections.remove(state.player.getUniqueId(), state);
        sessions.release(state.lease);
    }

    private void abandon(UUID playerId, LoginAttempt attempt) {
        pending.remove(playerId, attempt);
        CompletionStage<Optional<PlayerSessionLease>> acquisition = attempt.acquisition;
        // Register even after a timeout: the transaction may already have
        // committed while its response was still in flight.
        if (acquisition != null) acquisition.whenComplete((lease, error) -> {
            if (error == null) lease.ifPresent(sessions::release);
        });
    }

    private void reject(AsyncPlayerPreLoginEvent event, String key) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(messages.text(null, key)));
    }

    private void sync(Runnable action) {
        if (closed.get() || !plugin.isEnabled()) return;
        try { plugin.getServer().getScheduler().runTask(plugin, action); }
        catch (IllegalStateException shutdown) { /* Database leases expire without any callback. */ }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (heartbeat != null) heartbeat.cancel();
        pending.forEach(this::abandon);
        connections.values().forEach(this::closeConnection);
    }

    static String rejectionKey(JdbcSessionLease.Admission admission) {
        return switch (admission) {
            case NO_VALID_LEASE -> "session-lease.no-valid-lease";
            case STALE_PROCESS_TOKEN -> "session-lease.stale-process-token";
            case HELD_BY_OTHER_NODE -> "session-lease.held-by-other-node";
            case ALLOWED -> throw new IllegalStateException("allowed admission cannot be rejected");
        };
    }

    private static final class LoginAttempt {
        final UUID token;
        final long startedNanos = System.nanoTime();
        volatile PlayerSessionLease lease;
        volatile CompletionStage<Optional<PlayerSessionLease>> acquisition;
        LoginAttempt(UUID token) { this.token = token; }
    }

    private static final class ConnectionState {
        final Player player;
        final AtomicBoolean renewing = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile PlayerSessionLease lease;
        volatile boolean authenticated;
        ConnectionState(Player player, PlayerSessionLease lease) { this.player = player; this.lease = lease; }
    }
}
