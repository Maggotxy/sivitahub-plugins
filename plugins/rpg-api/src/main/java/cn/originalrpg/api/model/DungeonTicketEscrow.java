package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One exact hidden-dungeon ticket held by the durable entry transaction. */
public record DungeonTicketEscrow(
        UUID reservationId,
        UUID playerId,
        String ticketId,
        String itemPayload,
        Instant createdAt
) {
    public DungeonTicketEscrow {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (ticketId == null || ticketId.isBlank() || ticketId.length() > 64
                || itemPayload == null || itemPayload.isBlank() || itemPayload.length() > 2_000_000) {
            throw new IllegalArgumentException("Invalid dungeon ticket escrow");
        }
    }
}
