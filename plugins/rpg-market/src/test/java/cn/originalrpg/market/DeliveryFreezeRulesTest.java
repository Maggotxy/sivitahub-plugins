package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为级用例：直接执行冻结与重连对账逻辑并断言结果，
 * 不使用源码字符串匹配。
 */
class DeliveryFreezeRulesTest {

    private static final DeliveryFreezeRules.ContainerSlots MARKED_CURSOR =
            new DeliveryFreezeRules.ContainerSlots(false, true, false, false, false);

    @Test
    void creativeContainerWithMarkedCursorIsFrozen() {
        assertTrue(DeliveryFreezeRules.blocksCreativeContainer(true, false),
                "创造模式事件只带游标时也必须冻结");
    }

    @Test
    void creativeContainerWithMarkedCurrentSlotIsFrozen() {
        assertTrue(DeliveryFreezeRules.blocksCreativeContainer(false, true),
                "创造模式事件带当前槽位时也必须冻结");
    }

    @Test
    void creativeContainerWithoutReceiptIsAllowed() {
        assertFalse(DeliveryFreezeRules.blocksCreativeContainer(false, false),
                "普通创造模式操作不应被误伤");
    }

    @Test
    void dragWithReceiptOnCursorIsFrozen() {
        assertTrue(DeliveryFreezeRules.decide(MARKED_CURSOR).frozen());
    }

    @Test
    void dragWithReceiptOnOldCursorIsFrozen() {
        assertTrue(DeliveryFreezeRules.decide(
                new DeliveryFreezeRules.ContainerSlots(false, false, true, false, false)).frozen(),
                "拖拽发起时的旧游标同样承载凭据");
    }

    @Test
    void clickWithReceiptOnlyInInventorySlotIsFrozen() {
        assertTrue(DeliveryFreezeRules.decide(
                new DeliveryFreezeRules.ContainerSlots(true, false, false, false, false)).frozen());
    }

    @Test
    void clickWithNoReceiptAnywhereIsAllowed() {
        assertFalse(DeliveryFreezeRules.decide(
                new DeliveryFreezeRules.ContainerSlots(false, false, false, false, false)).frozen());
    }

    @Test
    void cursorTakesPrecedenceOverInventorySlotExactlyOnce() {
        // 互不覆盖的关键：同一事件里游标与物品栏都命中时，只认游标一次，
        // 不会产生两条清理路径互相覆盖。
        DeliveryFreezeRules.Decision decision = DeliveryFreezeRules.decide(
                new DeliveryFreezeRules.ContainerSlots(true, true, true, true, true));
        assertTrue(decision.frozen());
        assertTrue(decision.cursorOwnsReceipt());
    }

    @Test
    void markerCollectionAppendsCursorAsLastEntry() {
        List<String> markers = DeliveryFreezeRules.collectMarkers(
                3, new String[]{"a", null, "c"}, "cursor");
        assertEquals(java.util.Arrays.asList("a", null, "c", "cursor"), markers);
        assertEquals(2, DeliveryReceiptRules.slot(markers.size(), 2));
        assertEquals(DeliveryFreezeRules.CURSOR_SLOT,
                DeliveryReceiptRules.slot(markers.size(), markers.size() - 1));
    }

    @Test
    void cursorReceiptIsScannedAndMappedToCursorSlot() {
        UUID delivery = UUID.randomUUID();
        List<String> markers = DeliveryFreezeRules.collectMarkers(
                3, new String[]{null, null, null}, delivery.toString());
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(markers);

        assertEquals(DeliveryFreezeRules.CURSOR_SLOT, scan.uniqueReceipts().get(delivery));
        assertTrue(scan.invalidSlots().isEmpty());
        assertTrue(scan.duplicateIds().isEmpty());
    }

    @Test
    void malformedCursorReceiptIsReportedOnTheCursorSlot() {
        List<String> markers = DeliveryFreezeRules.collectMarkers(
                2, new String[]{null, null}, "not-a-uuid");
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(markers);

        assertEquals(List.of(DeliveryFreezeRules.CURSOR_SLOT), scan.invalidSlots());
    }

    @Test
    void markerCountMustMatchInventorySize() {
        try {
            DeliveryFreezeRules.collectMarkers(3, new String[]{"a"}, null);
            throw new AssertionError("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 尺寸不一致说明调用方拼错了扫描范围，必须立刻失败而不是静默越界。
        }
    }
}
