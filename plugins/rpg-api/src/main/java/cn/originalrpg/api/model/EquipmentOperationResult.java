package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record EquipmentOperationResult(
        Status status,
        UUID operationId,
        boolean replayed,
        int queuedDeliveries,
        BigDecimal balance
) {
    public enum Status {
        COMMITTED,
        REJECTED_INSUFFICIENT_FUNDS,
        /**
         * Phase 2 lifecycle gate (C-001, C-009): the item identity cannot legally
         * undergo this operation in its authoritative state. Nothing was charged
         * and no lifecycle row changed; the escrowed assets are returned through
         * the same recovery lane as an insufficient-funds rejection.
         */
        OPERATION_REFUSED,
        /**
         * No durable database outcome exists yet. The caller must retain the
         * player PDC recovery envelope, but may release its process-local
         * click gate because that envelope blocks every new operation.
         */
        RECOVERY_PENDING,
        CONFLICT,
        INVALID
    }

    public EquipmentOperationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(operationId, "operationId");
        balance = Objects.requireNonNull(balance, "balance").setScale(2, RoundingMode.HALF_UP);
        if (queuedDeliveries < 0) throw new IllegalArgumentException("queuedDeliveries cannot be negative");
    }
}
