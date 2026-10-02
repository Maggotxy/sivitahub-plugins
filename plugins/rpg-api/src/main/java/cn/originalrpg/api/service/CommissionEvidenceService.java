package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CommissionEvidenceClaimResult;
import cn.originalrpg.api.model.CommissionEvidenceGrantResult;
import cn.originalrpg.api.model.CommissionEvidenceSnapshot;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Transactional authority for account-bound active-play evidence.
 *
 * <p>Every mutating operation is serialized by player and date in the database,
 * so several game nodes cannot exceed a source, offer, or total limit.</p>
 */
public interface CommissionEvidenceService {
    CompletionStage<CommissionEvidenceSnapshot> snapshot(UUID playerId, LocalDate date, int dailyCap);

    CompletionStage<CommissionEvidenceGrantResult> grant(
            UUID playerId, LocalDate date, String source, int requested, int sourceDailyCap, int dailyCap
    );

    CompletionStage<CommissionEvidenceClaimResult> claim(
            UUID playerId, LocalDate date, UUID receiptId, String commissionId, int evidenceCost,
            int totalDailyLimit, int offerDailyLimit, int dailyCap
    );

    /** Releases capacity while retaining a tombstone that makes the receipt impossible to replay. */
    CompletionStage<Boolean> release(UUID playerId, LocalDate date, UUID receiptId);
}
