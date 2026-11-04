package cn.originalrpg.api.service;

import cn.originalrpg.api.model.MechanismSwitch;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** Trusted operator control over the existing registry; never a player reward API. */
public interface MechanismControlService {
    /** Reads the durable switches and refreshes this node's bounded presentation/admission snapshot. */
    CompletionStage<List<MechanismSwitch>> switches();

    /**
     * Sets a registered mechanism to an explicit state and persists the operator's reason.
     * Implementations reject unknown IDs and blank/oversized audit labels before dispatch.
     * Disabling admission does not cancel an already accepted transaction or suppress refunds.
     */
    CompletionStage<MechanismSwitch> setEnabled(String mechanismId, boolean enabled, String auditLabel);
}
