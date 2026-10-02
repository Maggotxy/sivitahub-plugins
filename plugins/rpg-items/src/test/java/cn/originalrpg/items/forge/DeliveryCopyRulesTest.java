package cn.originalrpg.items.forge;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryCopyRulesTest {

    @Test
    void unmarkedOriginalGetsACopyBoundToTheCurrentOperation() {
        UUID operation = UUID.randomUUID();

        assertEquals(DeliveryCopyRules.MarkerSource.ASSIGN,
                DeliveryCopyRules.markerSource(false, operation));
        assertEquals(operation, DeliveryCopyRules.deliveryMarker(null, operation),
                "出站副本必须带着本操作的标记，否则交付出去的就是一份干净可复用的权威物品");
    }

    @Test
    void copyPreservesTheOriginalMarkerInsteadOfRebindingItToAnotherOperation() {
        UUID escrowOperation = UUID.randomUUID();
        UUID otherOperation = UUID.randomUUID();

        assertEquals(DeliveryCopyRules.MarkerSource.PRESERVE,
                DeliveryCopyRules.markerSource(true, otherOperation));
        assertEquals(escrowOperation, DeliveryCopyRules.deliveryMarker(escrowOperation, otherOperation),
                "已标记物品的副本必须沿用原标记，不能被改写成另一个操作");
    }

    @Test
    void markerlessCopyIsOnlyAllowedWhenThereIsNoOwningOperation() {
        assertEquals(DeliveryCopyRules.MarkerSource.NONE,
                DeliveryCopyRules.markerSource(false, null));
        assertNull(DeliveryCopyRules.deliveryMarker(null, null),
                "仅在没有归属操作时才允许不带标记的副本");
    }

    @Test
    void aSecondOperationOnAnEscrowedItemIsRefused() {
        UUID escrowOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();

        assertTrue(DeliveryCopyRules.refusesSecondOperation(escrowOperation, secondOperation),
                "同一物品被两个不同操作引用就是重复交付，必须拒绝后一个");
        assertFalse(DeliveryCopyRules.refusesSecondOperation(escrowOperation, escrowOperation),
                "同一操作号是幂等重放，不是重复交付");
        assertFalse(DeliveryCopyRules.refusesSecondOperation(null, secondOperation),
                "未标记物品本来就可以进入新操作");
    }

    @Test
    void everyUniqueDeliveryReceiptKeepsOneOwnerOperation() {
        UUID operation = UUID.randomUUID();

        // 同一操作连续生成两次副本：标记必须完全一致，交付侧才能按标记判重。
        UUID first = DeliveryCopyRules.deliveryMarker(null, operation);
        UUID second = DeliveryCopyRules.deliveryMarker(first, operation);

        assertNotNull(first);
        assertEquals(first, second, "同一操作的重复出站副本必须带同一个标记，重复交付才可被识别并拒绝");
        assertTrue(DeliveryCopyRules.refusesSecondOperation(first, UUID.randomUUID()));
    }
}
