package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentOperationEnvelopeTest {
    @Test
    void roundTripsACompleteRecoveryEnvelope() {
        UUID operation = UUID.randomUUID();
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                operation, UUID.randomUUID(), EquipmentOperationKind.REFINEMENT,
                Optional.of(UUID.randomUUID()), "a".repeat(64), "slot=2;safe=true",
                new BigDecimal("125.00"), "refine:" + operation,
                List.of("finished-item"), List.of("original-item", "protection-stone"));

        assertEquals(request, EquipmentOperationEnvelope.decode(
                EquipmentOperationEnvelope.encode(request)).orElseThrow());
    }

    @Test
    void rejectsCorruptAndTrailingData() {
        assertTrue(EquipmentOperationEnvelope.decode("not+url+base64").isEmpty());
        EquipmentOperationRequest request = new EquipmentOperationRequest(
                UUID.randomUUID(), UUID.randomUUID(), EquipmentOperationKind.FORGE,
                Optional.empty(), "b".repeat(64), "forge", BigDecimal.ZERO,
                "forge:" + UUID.randomUUID(), List.of(), List.of("base"));
        assertTrue(EquipmentOperationEnvelope.decode(EquipmentOperationEnvelope.encode(request) + "AA").isEmpty());
    }

    @Test
    void decodeRefusesWhatTheCodecWouldHappilyPersist() {
        // The codec only bounds sizes; the shape gate is settleable(). A staged
        // envelope that decodes to an unsettled shape would pin the account,
        // so recovery must treat it as absent rather than replay a guaranteed
        // INVALID from the ledger.
        EquipmentOperationRequest badFingerprint = new EquipmentOperationRequest(
                UUID.randomUUID(), UUID.randomUUID(), EquipmentOperationKind.REFINEMENT,
                Optional.of(UUID.randomUUID()), "a".repeat(63), "slot=2",
                new BigDecimal("5.00"), "refine:" + UUID.randomUUID(),
                List.of("finished"), List.of("original"));
        assertTrue(EquipmentOperationEnvelope.decode(
                EquipmentOperationEnvelope.encode(badFingerprint)).isEmpty());
        EquipmentOperationRequest emptyRefusalLane = new EquipmentOperationRequest(
                UUID.randomUUID(), UUID.randomUUID(), EquipmentOperationKind.REFINEMENT,
                Optional.of(UUID.randomUUID()), "a".repeat(64), "slot=2",
                new BigDecimal("5.00"), "refine:" + UUID.randomUUID(),
                List.of("finished"), List.of());
        assertTrue(EquipmentOperationEnvelope.decode(
                EquipmentOperationEnvelope.encode(emptyRefusalLane)).isEmpty());
    }

    @Test
    void fingerprintsAndDerivedIdsAreStableAndOrderSensitive() {
        UUID operation = UUID.randomUUID();
        assertEquals(EquipmentOperationEnvelope.fingerprint("refine", List.of("a", "b")),
                EquipmentOperationEnvelope.fingerprint("refine", List.of("a", "b")));
        assertNotEquals(EquipmentOperationEnvelope.fingerprint("refine", List.of("a", "b")),
                EquipmentOperationEnvelope.fingerprint("refine", List.of("b", "a")));
        assertEquals(EquipmentOperationEnvelope.derivedId(operation, "result", 0),
                EquipmentOperationEnvelope.derivedId(operation, "result", 0));
        assertNotEquals(EquipmentOperationEnvelope.deterministicSeed(operation, "roll"),
                EquipmentOperationEnvelope.deterministicSeed(operation, "trait"));
    }

    @Test
    void recoveryPendingIsAnExplicitNonDurableSessionOutcome() {
        EquipmentOperationResult result = new EquipmentOperationResult(
                EquipmentOperationResult.Status.RECOVERY_PENDING, UUID.randomUUID(),
                false, 0, BigDecimal.ZERO);

        assertEquals(EquipmentOperationResult.Status.RECOVERY_PENDING, result.status());
        assertEquals(0, result.queuedDeliveries());
    }

    @Test
    void persistedKindOrdinalsRemainAppendOnly() {
        assertArrayEquals(new EquipmentOperationKind[]{
                        EquipmentOperationKind.FORGE,
                        EquipmentOperationKind.REFINEMENT,
                        EquipmentOperationKind.LIMIT_BREAK,
                        EquipmentOperationKind.UNBIND,
                        EquipmentOperationKind.LEGACY,
                        EquipmentOperationKind.SALVAGE
                },
                EquipmentOperationKind.values());
    }

}
