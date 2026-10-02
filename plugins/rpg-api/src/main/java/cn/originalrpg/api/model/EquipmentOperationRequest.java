package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Complete, replayable description of an equipment operation.
 *
 * <p>Both delivery lists contain Base64 encoded Paper item payloads. Accepted
 * operations enqueue {@code committedDeliveries}; an insufficient-funds
 * rejection enqueues {@code rejectedDeliveries}, which are the exact assets
 * placed in escrow before this request was submitted.</p>
 */
public record EquipmentOperationRequest(
        UUID operationId,
        UUID playerId,
        EquipmentOperationKind kind,
        Optional<UUID> itemId,
        String inputFingerprint,
        String requestPayload,
        BigDecimal goldCost,
        String debitKey,
        List<String> committedDeliveries,
        List<String> rejectedDeliveries
) {
    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{3,96}");
    private static final java.util.regex.Pattern FINGERPRINT = java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private static final BigDecimal MAX_COST = new BigDecimal("1000000000000.00");
    private static final int MAX_PAYLOAD = 2_000_000;
    private static final int MAX_REQUEST_PAYLOAD = 16_384;
    private static final int MAX_DELIVERIES = 16;

    public EquipmentOperationRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(kind, "kind");
        itemId = Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(inputFingerprint, "inputFingerprint");
        Objects.requireNonNull(requestPayload, "requestPayload");
        goldCost = Objects.requireNonNull(goldCost, "goldCost").setScale(2, RoundingMode.HALF_UP);
        Objects.requireNonNull(debitKey, "debitKey");
        committedDeliveries = List.copyOf(Objects.requireNonNull(committedDeliveries, "committedDeliveries"));
        rejectedDeliveries = List.copyOf(Objects.requireNonNull(rejectedDeliveries, "rejectedDeliveries"));
    }

    /**
     * Whether the durable outbox can accept this request at all. The escrow
     * coordinator checks this before writing its PDC envelope and blanking the
     * input items, because a request the ledger would answer {@code INVALID}
     * leaves a non-terminal receipt: the envelope stays staged, every later
     * operation for that account is refused, and the escrowed items are only
     * reachable through operator intervention. Same predicate on both sides is
     * the point — this is the single source of truth for that shape check.
     */
    public boolean settleable() {
        if (!FINGERPRINT.matcher(inputFingerprint).matches()) return false;
        if (requestPayload.isBlank() || requestPayload.length() > MAX_REQUEST_PAYLOAD) return false;
        if (goldCost.signum() < 0 || goldCost.compareTo(MAX_COST) > 0) return false;
        if (!KEY.matcher(debitKey).matches()) return false;
        if (committedDeliveries.size() > MAX_DELIVERIES || rejectedDeliveries.size() > MAX_DELIVERIES) return false;
        // The refusal lane must always be able to hand the escrow back, so it
        // is never empty — an operation with nothing to return is a bug.
        if (rejectedDeliveries.isEmpty()) return false;
        return allValidPayloads(committedDeliveries) && allValidPayloads(rejectedDeliveries);
    }

    private static boolean allValidPayloads(List<String> payloads) {
        for (String payload : payloads) {
            if (payload == null || payload.isBlank() || payload.length() > MAX_PAYLOAD) return false;
        }
        return true;
    }
}
