package cn.originalrpg.api.service;

import cn.originalrpg.api.model.EnergyRecovery;
import cn.originalrpg.api.model.DungeonTicketEscrow;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface DungeonEntryService {
    enum ReservationStatus {
        SUCCESS,
        INSUFFICIENT_ENERGY,
        DUPLICATE,
        INVALID
    }

    enum PartyCommitStatus {
        COMMITTED,
        RESERVED,
        INVALID
    }

    CompletionStage<ReservationStatus> reserve(UUID reservationId, UUID playerId, int energyCost);

    /**
     * Stores the exact ticket removed from a player's inventory against the
     * matching still-RESERVED entry. Replays with identical content succeed.
     */
    CompletionStage<Boolean> escrowTicket(
            UUID reservationId, UUID playerId, String ticketId, String itemPayload
    );

    /**
     * Returns the durable ticket record for this exact entry, if one exists,
     * including a record already consumed or returned.  Reconciliation code
     * uses existence—not mutable ownership—to remove the matching temporary
     * inventory copy after a commit, rollback, disconnect, or process restart.
     */
    CompletionStage<java.util.Optional<DungeonTicketEscrow>> ticketEscrow(
            UUID reservationId, UUID playerId
    );

    CompletionStage<Boolean> commit(UUID reservationId, UUID playerId);

    /**
     * Commits one party's complete reservation set atomically. Implementations
     * must leave every reservation RESERVED when any member, owner, or state is
     * invalid so the caller can safely refund the whole party.
     */
    CompletionStage<Boolean> commitAll(Map<UUID, UUID> reservations);

    /**
     * Resolves an ambiguous party commit using the same immutable reservation map.
     * COMMITTED is returned only when every reservation is durably committed;
     * RESERVED only when every reservation is still refundable.
     */
    CompletionStage<PartyCommitStatus> partyCommitStatus(Map<UUID, UUID> reservations);

    CompletionStage<Integer> refund(UUID reservationId, UUID playerId);

    CompletionStage<EnergyRecovery> recoverAll();
}
