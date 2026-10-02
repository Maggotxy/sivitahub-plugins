package cn.originalrpg.api.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Complete per-player reward intent for one managed dungeon run. */
public record DungeonRewardRequest(
        UUID runId,
        String dungeonId,
        UUID playerId,
        long experience,
        List<DungeonRewardDelivery> deliveries,
        Optional<DungeonTicketCandidate> ticketCandidate
) {
    public DungeonRewardRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(dungeonId, "dungeonId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(deliveries, "deliveries");
        ticketCandidate = ticketCandidate == null ? Optional.empty() : ticketCandidate;
        deliveries = List.copyOf(deliveries);
        if (dungeonId.isBlank() || experience < 1 || deliveries.isEmpty()) {
            throw new IllegalArgumentException("Invalid dungeon reward request");
        }
        long distinctIds = java.util.stream.Stream.concat(
                        deliveries.stream().map(DungeonRewardDelivery::deliveryId),
                        ticketCandidate.stream().map(candidate -> candidate.delivery().deliveryId()))
                .distinct().count();
        if (distinctIds != deliveries.size() + ticketCandidate.stream().count()) {
            throw new IllegalArgumentException("Dungeon reward delivery IDs must be unique");
        }
    }
}
