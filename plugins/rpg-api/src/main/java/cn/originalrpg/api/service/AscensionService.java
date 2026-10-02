package cn.originalrpg.api.service;

import cn.originalrpg.api.model.AscensionEvidence;
import cn.originalrpg.api.model.AscensionResult;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Compound, auditable ascension gate. Callers must not replace this with ProfileService.ascend(). */
public interface AscensionService {
    CompletionStage<AscensionResult> eligibility(UUID playerId, AscensionEvidence evidence);

    CompletionStage<AscensionResult> ascend(UUID playerId, AscensionEvidence evidence);
}
