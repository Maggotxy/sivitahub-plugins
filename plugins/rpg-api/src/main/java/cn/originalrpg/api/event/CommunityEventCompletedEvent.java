package cn.originalrpg.api.event;

import cn.originalrpg.api.model.CommunityRole;
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
    private final java.util.Map<UUID, CommunityRole> participantRoles;

    /** 未指定参与者角色：消费方按「无法判定角色」处理，不得假设参与者有治理权。 */
    public CommunityEventCompletedEvent(String source, Set<UUID> participantIds, String victor) {
        this(source, participantIds, victor, java.util.Map.of());
    }

    public CommunityEventCompletedEvent(
            String source,
            Set<UUID> participantIds,
            String victor,
            java.util.Map<UUID, CommunityRole> participantRoles
    ) {
        this.source = Objects.requireNonNull(source, "source");
        if (source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        this.participantIds = Set.copyOf(Objects.requireNonNull(participantIds, "participantIds"));
        if (this.participantIds.isEmpty()) {
            throw new IllegalArgumentException("participantIds must not be empty");
        }
        this.victor = victor == null ? "" : victor;
        java.util.Map<UUID, CommunityRole> roles = new java.util.HashMap<>();
        Objects.requireNonNull(participantRoles, "participantRoles")
                .forEach((id, role) -> {
                    if (id != null && role != null && this.participantIds.contains(id)) roles.put(id, role);
                });
        this.participantRoles = java.util.Map.copyOf(roles);
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

    /**
     * 该参与者在发起方社群中的角色；未知返回 {@link CommunityRole#MEMBER}。
     *
     * <p>返回 MEMBER 是 fail closed：领域前置与审批资格都不会因为「不知道角色」而被放宽。</p>
     */
    public CommunityRole roleOf(UUID playerId) {
        return participantRoles.getOrDefault(playerId, CommunityRole.MEMBER);
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
