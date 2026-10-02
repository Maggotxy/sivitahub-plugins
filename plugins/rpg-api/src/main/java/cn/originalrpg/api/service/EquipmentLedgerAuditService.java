package cn.originalrpg.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Phase 2 (C-013): operator access to the equipment lifecycle ledger.
 *
 * <p>A freeze is the only mutation an automated system may perform on a
 * player-owned identity, so this service is the intended landing point for
 * risk-control findings; a thaw is the only way out and is always attributed.
 * Every mutation writes a durable audit row (actor + note) atomically with the
 * state change — the ledger keeps a short operation token, the full note lives
 * in the audit trail. Neither rewrites the owning player: the freeze keeps it
 * so a verified restore hands the identity back to whoever held it.</p>
 *
 * <p>The released state is decided by the lifecycle rules, never chosen by the
 * operator; a freeze or thaw the rules forbid is refused rather than forced.</p>
 */
public interface EquipmentLedgerAuditService {

    /** Raw lifecycle row snapshot; empty when the identity was never registered. */
    CompletionStage<Optional<Entry>> entry(UUID itemId);

    /**
     * Bounded slice of identities an operator needs to look at — frozen,
     * recently restored, or terminal — newest first.
     */
    CompletionStage<List<Entry>> watchlist(int limit);

    /** Freezes an identity for investigation. Refuses terminal identities. */
    CompletionStage<AuditResult> quarantine(UUID itemId, String actor, String auditNote);

    /** Lifts a freeze after human verification. Refuses anything not frozen. */
    CompletionStage<AuditResult> restore(UUID itemId, String actor, String auditNote);

    /** Durable decision trail for one identity, newest first. */
    CompletionStage<List<AuditRecord>> auditTrail(UUID itemId, int limit);

    record Entry(UUID itemId, String state, UUID ownerUuid, String lastOperation) {
    }

    record AuditRecord(String action, String fromState, String toState, String actor, String note, Instant at) {
    }

    record AuditResult(Status status, String state) {
        public enum Status {
            /** The transition applied; {@code state} is the new lifecycle state. */
            APPLIED,
            /** Already in the requested state; nothing was written. */
            ALREADY_IN_STATE,
            /** No ledger row exists for this identity. */
            NOT_FOUND,
            /** Only a frozen identity may be restored; the row was live or terminal. */
            NOT_FROZEN,
            /** The identity was already spent (salvaged, extracted, destroyed). */
            TERMINAL,
            /** The lifecycle graph does not allow this transition. */
            RULES_REFUSED,
            /** Missing, blank, or over-long audit note. */
            INVALID_NOTE
        }
    }
}
