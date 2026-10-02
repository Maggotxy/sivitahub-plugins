package cn.originalrpg.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Published once after a managed dungeon boss has been defeated and the exact
 * set of players eligible for completion rewards has been resolved.
 */
public final class DungeonCompletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String dungeonId;
    private final Set<UUID> recipientIds;

    public DungeonCompletedEvent(String dungeonId, Set<UUID> recipientIds) {
        this.dungeonId = Objects.requireNonNull(dungeonId, "dungeonId");
        if (dungeonId.isBlank()) throw new IllegalArgumentException("dungeonId must not be blank");
        this.recipientIds = Set.copyOf(Objects.requireNonNull(recipientIds, "recipientIds"));
        if (this.recipientIds.isEmpty()) {
            throw new IllegalArgumentException("recipientIds must not be empty");
        }
    }

    public String dungeonId() {
        return dungeonId;
    }

    public Set<UUID> recipientIds() {
        return recipientIds;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
