package cn.originalrpg.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Fired when a connection that previously passed the authentication gate is
 * returned to the unauthenticated state without necessarily disconnecting.
 *
 * <p>Listeners must immediately discard connection-scoped caches, pending UI
 * actions, periodic-work membership and visible HUD state. Durable operations
 * and receipts remain owned by their persistence/recovery protocols. Cleanup
 * handlers must be idempotent because a later {@code PlayerQuitEvent} is still
 * expected for an ordinary connection close.</p>
 */
public final class AuthenticatedPlayerRevokedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public AuthenticatedPlayerRevokedEvent(Player player) {
        this.player = Objects.requireNonNull(player, "player");
    }

    public Player player() {
        return player;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
