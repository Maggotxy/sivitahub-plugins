package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape gate shared by staging, envelope decoding and the durable outbox.
 * It is the guard against a pinned account: a request that survives staging
 * but the ledger answers INVALID/CONFLICT leaves a non-terminal receipt, the
 * PDC envelope is retained, and every later operation for that player is
 * refused until an operator intervenes.
 */
class EquipmentOperationRequestTest {
    @Test
    void acceptsACompleteRequestAndItsCodecRoundTrip() {
        EquipmentOperationRequest request = request();
        assertTrue(request.settleable());
        assertEquals(request, EquipmentOperationEnvelope.decode(
                EquipmentOperationEnvelope.encode(request)).orElseThrow());
    }

    @Test
    void forgesWithNoCommittedDeliveryRemainSettleable() {
        // A forge can legitimately produce nothing (failed roll); only the
        // refusal lane must always be able to hand the escrow back.
        assertTrue(new EquipmentOperationRequest(
                UUID.randomUUID(), UUID.randomUUID(), EquipmentOperationKind.FORGE,
                Optional.empty(), "b".repeat(64), "forge", BigDecimal.ZERO,
                "forge:" + UUID.randomUUID(), List.of(), List.of("base")).settleable());
    }

    @Test
    void refusesEveryShapeTheLedgerWouldAnswerInvalid() {
        // The fingerprint is the escrow's identity: a wrong length or case
        // means the receipt could never be matched on replay.
        assertFalse(tune(request(), "fingerprint", "a".repeat(63)).settleable());
        assertFalse(tune(request(), "fingerprint", "A".repeat(64)).settleable());
        // An empty refusal lane could never return the escrowed assets.
        assertFalse(tune(request(), "rejected", List.of()).settleable());
        // A blank or over-long request payload cannot be re-validated on replay.
        assertFalse(tune(request(), "payload", " ").settleable());
        assertFalse(tune(request(), "payload", "x".repeat(16_385)).settleable());
        // Debit keys must be idempotency-safe, so the character class is closed.
        assertFalse(tune(request(), "debitKey", "refine:oops!").settleable());
        assertFalse(tune(request(), "debitKey", "ab").settleable());
        // A delivery payload that cannot be stored cannot be delivered.
        assertFalse(tune(request(), "committed", List.of(" ")).settleable());
        // Delivery counts are bounded by the envelope codec (one byte) as well.
        List<String> overflow = new ArrayList<>();
        for (int index = 0; index < 17; index++) overflow.add("item-" + index);
        assertFalse(tune(request(), "committed", overflow).settleable());
    }

    @Test
    void costBoundSurvivesTheConstructorScaleNormalization() {
        // The compacting constructor rounds to 2 decimal places, so a scale
        // violation can never reach the gate — but the ceiling must still bite.
        assertTrue(tune(request(), "cost", new BigDecimal("1000000000000.00")).settleable());
        assertFalse(tune(request(), "cost", new BigDecimal("1000000000001.00")).settleable());
        assertFalse(tune(request(), "cost", new BigDecimal("-1.00")).settleable());
        assertEquals(new BigDecimal("1.01"),
                tune(request(), "cost", new BigDecimal("1.005")).goldCost());
    }

    private static EquipmentOperationRequest request() {
        return new EquipmentOperationRequest(
                UUID.randomUUID(), UUID.randomUUID(), EquipmentOperationKind.REFINEMENT,
                Optional.of(UUID.randomUUID()), "a".repeat(64), "slot=2;safe=true",
                new BigDecimal("125.00"), "refine:" + UUID.randomUUID(),
                List.of("finished-item"), List.of("original-item", "protection-stone"));
    }

    /**
     * Rebuilds the request with one field replaced, because the record is
     * immutable and every field is positional. The sentinels below name the
     * field so a future component reorder fails loudly instead of silently
     * testing the wrong slot.
     */
    private static EquipmentOperationRequest tune(
            EquipmentOperationRequest base, String field, Object value) {
        return switch (field) {
            case "fingerprint" -> rebuild(base, (String) value, base.requestPayload(), base.debitKey(),
                    base.committedDeliveries(), base.rejectedDeliveries(), base.goldCost());
            case "payload" -> rebuild(base, base.inputFingerprint(), (String) value, base.debitKey(),
                    base.committedDeliveries(), base.rejectedDeliveries(), base.goldCost());
            case "debitKey" -> rebuild(base, base.inputFingerprint(), base.requestPayload(), (String) value,
                    base.committedDeliveries(), base.rejectedDeliveries(), base.goldCost());
            case "committed" -> {
                @SuppressWarnings("unchecked") List<String> list = (List<String>) value;
                yield rebuild(base, base.inputFingerprint(), base.requestPayload(), base.debitKey(),
                        list, base.rejectedDeliveries(), base.goldCost());
            }
            case "rejected" -> {
                @SuppressWarnings("unchecked") List<String> list = (List<String>) value;
                yield rebuild(base, base.inputFingerprint(), base.requestPayload(), base.debitKey(),
                        base.committedDeliveries(), list, base.goldCost());
            }
            case "cost" -> rebuild(base, base.inputFingerprint(), base.requestPayload(), base.debitKey(),
                    base.committedDeliveries(), base.rejectedDeliveries(), (BigDecimal) value);
            default -> throw new IllegalArgumentException("unknown field " + field);
        };
    }

    private static EquipmentOperationRequest rebuild(
            EquipmentOperationRequest base, String fingerprint, String payload, String debitKey,
            List<String> committed, List<String> rejected, BigDecimal cost) {
        return new EquipmentOperationRequest(base.operationId(), base.playerId(), base.kind(),
                base.itemId(), fingerprint, payload, cost, debitKey, committed, rejected);
    }
}
