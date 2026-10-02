package cn.originalrpg.api.service;

import cn.originalrpg.api.model.IncidentCompensationRequest;
import cn.originalrpg.api.model.IncidentCompensationResult;

import java.util.concurrent.CompletionStage;

/** OP-facing, durable and replay-safe incident recovery authority. */
public interface AdminIncidentCompensationService {
    CompletionStage<IncidentCompensationResult> apply(IncidentCompensationRequest request);

    CompletionStage<IncidentCompensationResult> audit(String incidentKey);
}
