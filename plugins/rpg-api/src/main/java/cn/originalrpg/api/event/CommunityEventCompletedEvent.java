package cn.originalrpg.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一次社群级的共同成果（当前由副本通关发布）。
 *
 * <p>与 {@link DungeonCompletedEvent} 分开：后者是成长/奖励结算信号，
 * 本事件只用于 Phase 6 治理动作 {@code community.event} 的双批审批触发。</p>
 */
public final class CommunityEventCompletedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String source;
    private final Set<UUID> participantIds;
    private final String victor;

    public CommunityEventCompletedEvent(String source, Set<UUID> participantIds, String victor) {
        this.source = Objects.requireNonNull(source, "source");
        if (source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        this.participantIds = Set.copyOf(Objects.requireNonNull(participantIds, "participantIds"));
        if (this.participantIds.isEmpty()) {
            throw new IllegalArgumentException("participantIds must not be empty");
        }
        this.victor = victor == null ? "" : victor;
    }

    public String source() {
        return source;
    }

    public Set<UUID> participantIds() {
        return participantIds;
    }

    public String victor() {
        return victor;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
