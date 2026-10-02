package cn.originalrpg.api.service;

import cn.originalrpg.api.model.PetRevivalIntent;
import cn.originalrpg.api.model.PetRevivalRequest;
import cn.originalrpg.api.model.PetRevivalResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Persists the Gold side of the cross-database MyPet revival protocol.
 *
 * <p>{@link #begin(PetRevivalRequest)} commits the debit and a replayable intent
 * in one transaction. Callers must persist the corresponding MyPet state before
 * marking that intent complete.</p>
 */
public interface PetRevivalService {
    CompletionStage<PetRevivalResult> begin(PetRevivalRequest request);

    CompletionStage<List<PetRevivalIntent>> pending(UUID playerId);

    /** Returns one operation regardless of state for an administrator audit. */
    CompletionStage<Optional<PetRevivalIntent>> find(UUID operationId);

    /** Oldest quarantined operations first; bounded to protect the database. */
    CompletionStage<List<PetRevivalIntent>> reviewQueue(int limit);

    CompletionStage<PetRevivalResult> complete(UUID operationId, UUID playerId, UUID petId);

    CompletionStage<PetRevivalResult> quarantine(
            UUID operationId,
            UUID playerId,
            UUID petId,
            String failureReason
    );

    /** Reopens a quarantined operation so the normal MyPet replay can verify it again. */
    CompletionStage<PetRevivalResult> retryQuarantined(UUID operationId);

    /**
     * Atomically reverses the original revival debit and closes a quarantined
     * operation. The ledger's unique reversal constraint makes this safe to
     * replay after an ambiguous database response.
     */
    CompletionStage<PetRevivalResult> compensateQuarantined(UUID operationId, String administrator);
}
