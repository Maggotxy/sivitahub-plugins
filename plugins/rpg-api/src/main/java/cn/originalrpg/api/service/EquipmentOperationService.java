package cn.originalrpg.api.service;

import cn.originalrpg.api.model.EquipmentOperationRequest;
import cn.originalrpg.api.model.EquipmentOperationResult;

import java.util.concurrent.CompletionStage;

/** Atomically records, charges and enqueues the deterministic result of an equipment operation. */
public interface EquipmentOperationService {
    CompletionStage<EquipmentOperationResult> settle(EquipmentOperationRequest request);
}
