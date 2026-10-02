package cn.originalrpg.api.service;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.model.HiddenPathActivationResult;
import cn.originalrpg.api.model.HiddenPathEligibility;
import cn.originalrpg.api.model.HiddenPathProfile;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Authoritative hidden-path eligibility, scarcity and activation boundary. */
public interface HiddenPathService {
    CompletionStage<Optional<HiddenPathProfile>> path(UUID playerId);

    CompletionStage<HiddenPathEligibility> eligibility(UUID playerId, HiddenPath path);

    /**
     * Activates at most one path for one account. Implementations must lock the
     * player and the global per-path capacity in the same transaction.
     */
    CompletionStage<HiddenPathActivationResult> activate(UUID playerId, HiddenPath path);
}
