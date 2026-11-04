package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class MailboxReceiptScanTest {
    @Test void aCursorReceiptIsDistinctFromAbsenceAndRetainsItsClaimToken() {
        UUID delivery = UUID.randomUUID(), token = UUID.randomUUID();
        var scan = MailboxReceiptScan.scan(List.of(new MailboxReceiptScan.Input(0, null, null),
                new MailboxReceiptScan.Input(DeliveryFreezeRules.CURSOR_SLOT, delivery.toString(), token.toString())));
        assertEquals(DeliveryFreezeRules.CURSOR_SLOT, scan.unique().get(delivery).slot());
        assertEquals(token, scan.unique().get(delivery).claimToken());
        assertFalse(scan.unique().containsKey(UUID.randomUUID()));
    }
    @Test void duplicateDeliveryAcrossCursorAndInventoryCannotBeAcknowledged() {
        UUID delivery = UUID.randomUUID(), token = UUID.randomUUID();
        var scan = MailboxReceiptScan.scan(List.of(new MailboxReceiptScan.Input(2, delivery.toString(), token.toString()),
                new MailboxReceiptScan.Input(DeliveryFreezeRules.CURSOR_SLOT, delivery.toString(), token.toString())));
        assertTrue(scan.unique().isEmpty()); assertEquals(java.util.Set.of(delivery), scan.ambiguous());
    }
    @Test void aClaimTokenCopiedToAnotherDeliveryQuarantinesBothReceipts() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID(), token = UUID.randomUUID();
        var scan = MailboxReceiptScan.scan(List.of(new MailboxReceiptScan.Input(0, first.toString(), token.toString()),
                new MailboxReceiptScan.Input(1, second.toString(), token.toString())));
        assertTrue(scan.unique().isEmpty()); assertEquals(java.util.Set.of(first, second), scan.ambiguous());
    }
    @Test void orphanAndMalformedTokensRemainInvalidInsteadOfBecomingLegacyReceipts() {
        UUID delivery = UUID.randomUUID(), token = UUID.randomUUID();
        var scan = MailboxReceiptScan.scan(List.of(new MailboxReceiptScan.Input(0, null, token.toString()),
                new MailboxReceiptScan.Input(1, delivery.toString(), "invalid")));
        assertTrue(scan.unique().isEmpty()); assertEquals(java.util.Set.of(0, 1), scan.invalidSlots());
        assertTrue(scan.ambiguous().contains(delivery));
    }
    @Test void aValidTokenCopiedOntoMalformedIdentityStillQuarantinesItsOtherOccurrence() {
        UUID delivery = UUID.randomUUID(), token = UUID.randomUUID();
        var scan = MailboxReceiptScan.scan(List.of(new MailboxReceiptScan.Input(0, "broken-id", token.toString()),
                new MailboxReceiptScan.Input(1, delivery.toString(), token.toString())));
        assertTrue(scan.unique().isEmpty()); assertTrue(scan.ambiguous().contains(delivery));
    }
    @Test void onlyCanonicalNonzeroIdsCanBecomeLegacyRecoveryReceipts() {
        UUID delivery = UUID.randomUUID();
        var scan = MailboxReceiptScan.scan(List.of(new MailboxReceiptScan.Input(0, "1-1-1-1-1", null),
                new MailboxReceiptScan.Input(1, new UUID(0, 0).toString(), null), new MailboxReceiptScan.Input(2, delivery.toString(), null)));
        assertEquals(java.util.Set.of(0, 1), scan.invalidSlots());
        assertEquals(java.util.Set.of(delivery), scan.unique().keySet()); assertNull(scan.unique().get(delivery).claimToken());
    }
}
