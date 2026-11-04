package cn.originalrpg.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.UUID;

/** Server-thread refresh hint after a committed cosmetic purchase; the event itself grants no ownership. */
public final class SupportEntitlementsChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    public SupportEntitlementsChangedEvent(UUID playerId) { this.playerId = Objects.requireNonNull(playerId, "playerId"); }
    public UUID playerId() { return playerId; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
