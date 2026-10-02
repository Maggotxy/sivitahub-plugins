package cn.originalrpg.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * Fired once per connection after the server's offline-account login gate has
 * released the player and restored their Bukkit state.
 *
 * <p>Listeners that read or mutate RPG profiles, inventories, persistent item
 * state, receipts, travel state, or HUD snapshots must use this event instead
 * of {@code PlayerJoinEvent}. Connection-only protections may still listen to
 * the ordinary Bukkit lifecycle.</p>
 */
public final class AuthenticatedPlayerReadyEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;

    public AuthenticatedPlayerReadyEvent(Player player) {
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
