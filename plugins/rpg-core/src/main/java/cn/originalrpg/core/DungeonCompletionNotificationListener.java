package cn.originalrpg.core;

import cn.originalrpg.api.event.AuthenticatedPlayerReadyEvent;
import cn.originalrpg.api.event.AuthenticatedPlayerRevokedEvent;
import cn.originalrpg.api.service.MessageService;
import cn.originalrpg.api.service.ProfileService;
import cn.originalrpg.core.persistence.JdbcDungeonCompletionInbox;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delivers committed inbox hints on the Paper main thread, including after a
 * reconnect. SQL runs on the inbox's bounded worker executor. A disconnect,
 * AuthMe logout, replacement connection, or plugin unload leaves unsent rows
 * unread. Chat is at-least-once: a crash between send and acknowledge may repeat
 * a hint, but cannot repeat the already-settled reward.
 */
public final class DungeonCompletionNotificationListener implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final JdbcDungeonCompletionInbox inbox;
    private final ProfileService profiles;
    private final MessageService messages;
    private final Map<UUID, OnlineSession> authenticated = new HashMap<>();
    private final Set<UUID> inFlight = new HashSet<>();
    private final ArrayDeque<UUID> rotation = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private BukkitTask polling;

    public DungeonCompletionNotificationListener(Plugin plugin, JdbcDungeonCompletionInbox inbox,
                                                  ProfileService profiles, MessageService messages) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.inbox = java.util.Objects.requireNonNull(inbox, "inbox");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.messages = java.util.Objects.requireNonNull(messages, "messages");
    }

    public void start() {
        polling = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthenticated(AuthenticatedPlayerReadyEvent event) {
        Player player = event.player();
        UUID id = player.getUniqueId();
        if (closed.get()) return;
        if (!authenticated.containsKey(id)) rotation.addLast(id);
        authenticated.put(id, new OnlineSession(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRevoked(AuthenticatedPlayerRevokedEvent event) { revoke(event.player()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { revoke(event.getPlayer()); }

    private void revoke(Player player) {
        UUID id = player.getUniqueId();
        OnlineSession current = authenticated.get(id);
        if (current != null && current.player() == player) {
            authenticated.remove(id);
            rotation.remove(id);
        }
    }

    private void poll() {
        if (closed.get()) return;
        // At most 16 players and 16 notices each per second, independent of the
        // server population. Rotation prevents a busy first page starving others.
        int count = Math.min(16, rotation.size());
        for (int index = 0; index < count; index++) {
            UUID id = rotation.removeFirst();
            OnlineSession session = authenticated.get(id);
            if (session == null) continue;
            rotation.addLast(id);
            if (!inFlight.add(id)) continue;
            inbox.unread(id, 16).whenComplete((notices, error) -> sync(() -> {
                if (error != null) {
                    inFlight.remove(id);
                    plugin.getLogger().warning("Could not read committed dungeon notifications: " + error.getMessage());
                    return;
                }
                if (!active(id, session)) {
                    inFlight.remove(id);
                    return;
                }
                if (!notices.isEmpty()) {
                    // A completion may have committed on another worker/node.
                    // Drop only the local profile projection, never its ledger.
                    profiles.evict(id);
                    profiles.get(id).exceptionally(failure -> {
                        plugin.getLogger().warning("Could not refresh completed dungeon profile: " + failure.getMessage());
                        return null;
                    });
                }
                var acknowledgements = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Boolean>>();
                for (JdbcDungeonCompletionInbox.Notice notice : notices) {
                    session.player().sendMessage(Component.text(messages.text(session.player(),
                            "dungeon.reward-mailbox", Map.of("count", notice.queuedDeliveries()))));
                    acknowledgements.add(inbox.acknowledge(notice.eventId(), id).toCompletableFuture());
                }
                java.util.concurrent.CompletableFuture.allOf(
                        acknowledgements.toArray(java.util.concurrent.CompletableFuture[]::new))
                        .whenComplete((ignored, ackError) -> sync(() -> {
                            inFlight.remove(id);
                            if (ackError != null) plugin.getLogger().warning(
                                    "Dungeon notification acknowledgement will retry: " + ackError.getMessage());
                        }));
            }));
        }
    }

    private boolean active(UUID id, OnlineSession session) {
        return !closed.get() && authenticated.get(id) == session
                && plugin.getServer().getPlayer(id) == session.player() && session.player().isOnline();
    }

    private void sync(Runnable callback) {
        if (closed.get() || !plugin.isEnabled()) return;
        try { plugin.getServer().getScheduler().runTask(plugin, () -> { if (!closed.get()) callback.run(); }); }
        catch (IllegalStateException unloading) { /* No acknowledgement: next login replays the durable hint. */ }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (polling != null) polling.cancel();
        authenticated.clear();
        rotation.clear();
        inFlight.clear();
    }

    private record OnlineSession(Player player) { }
}
