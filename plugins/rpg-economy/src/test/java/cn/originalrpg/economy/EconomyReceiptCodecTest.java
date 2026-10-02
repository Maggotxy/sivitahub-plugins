package cn.originalrpg.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyReceiptCodecTest {
    @Test
    void preparedRecoveryNeverTreatsAnEmptyInventoryAsEscrow() {
        assertFalse(EconomyCommand.verifiesPreparedEscrow(0, 64, false));
        assertFalse(EconomyCommand.verifiesPreparedEscrow(32, 64, false));
        assertFalse(EconomyCommand.verifiesPreparedEscrow(64, 64, false));
        assertTrue(EconomyCommand.verifiesPreparedEscrow(64, 64, true));
    }

    @Test
    void receiptsRoundTripAndMalformedFragmentsAreIgnored() {
        EconomyReceiptCodec.Receipt receipt = new EconomyReceiptCodec.Receipt(
                EconomyReceiptCodec.Kind.MERCHANT_BUYBACK, EconomyReceiptCodec.Stage.ESCROWED,
                UUID.randomUUID(), "merchant-sell:flow-1", "mutation-shards", "p100",
                new BigDecimal("123.45"), 0, 2, EconomyReceiptCodec.Rejection.NONE, "YWJjZA==");
        String encoded = EconomyReceiptCodec.encodeAll(List.of(receipt));

        assertEquals(List.of(receipt), EconomyReceiptCodec.decodeAll(encoded));
        assertEquals(List.of(receipt), EconomyReceiptCodec.decodeAll("broken\n" + encoded));
        assertTrue(EconomyReceiptCodec.decodeAll("2|unknown").isEmpty());
    }

    @Test
    void pendingEvidenceReleaseSurvivesPlayerDataRoundTrip() {
        EconomyReceiptCodec.Receipt receipt = new EconomyReceiptCodec.Receipt(
                EconomyReceiptCodec.Kind.COMMISSION, EconomyReceiptCodec.Stage.RELEASE_PENDING,
                UUID.randomUUID(), "commission:flow-2", "stone-mason", "2026-08-12",
                new BigDecimal("40.00"), 2, 0,
                EconomyReceiptCodec.Rejection.BALANCE_LIMIT, "YWJjZA==");

        assertEquals(List.of(receipt),
                EconomyReceiptCodec.decodeAll(EconomyReceiptCodec.encodeAll(List.of(receipt))));
    }

    @Test
    void versionOneReceiptsRemainRecoverableWithAnUnknownRejectionReason() {
        UUID id = UUID.randomUUID();
        String legacy = "1|COMMISSION|RELEASE_PENDING|" + id
                + "|commission:flow-3|stone-mason|2026-08-12|40.00|2|0|YWJjZA==";

        EconomyReceiptCodec.Receipt decoded = EconomyReceiptCodec.decodeAll(legacy).getFirst();

        assertEquals(EconomyReceiptCodec.Rejection.NONE, decoded.rejection());
        assertEquals(id, decoded.id());
    }
}
