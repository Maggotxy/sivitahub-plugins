package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryReceiptRulesTest {
    @Test
    void uniqueReceiptIsRecoverableAtItsRealInventorySlot() {
        UUID delivery = UUID.randomUUID();
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(
                java.util.Arrays.asList(null, null, delivery.toString(), null));

        assertEquals(2, scan.uniqueReceipts().get(delivery));
        assertTrue(scan.invalidSlots().isEmpty());
        assertTrue(scan.duplicateIds().isEmpty());
    }

    @Test
    void malformedReceiptCanBeUnfrozenButDuplicateRemainsQuarantined() {
        UUID duplicate = UUID.randomUUID();
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(List.of(
                duplicate.toString(), "not-a-uuid", duplicate.toString()));

        assertEquals(List.of(1), scan.invalidSlots());
        assertEquals(java.util.Set.of(duplicate), scan.duplicateIds());
        assertFalse(scan.uniqueReceipts().containsKey(duplicate));
    }
}
