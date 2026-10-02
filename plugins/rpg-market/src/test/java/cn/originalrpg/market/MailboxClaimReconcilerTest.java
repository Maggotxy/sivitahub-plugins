package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为级用例：模拟「领取中 → 断线 → 重连」路径。
 *
 * <p>场景：投递物品已入包并带上凭据，但 markDelivered 还没提交就断线。
 * 重连时数据库仍返回 PENDING 行。断言重连不会重新入包，只做确认。</p>
 */
class MailboxClaimReconcilerTest {

    @Test
    void reconnectAfterCursorReceiptDoesNotRedeliver() {
        UUID delivery = UUID.randomUUID();
        // 断线瞬间凭据停在游标上，物品栏扫描看不到它 —— 这就是 G-104 的窗口。
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(
                DeliveryFreezeRules.collectMarkers(3, new String[]{null, null, null}, delivery.toString()));

        MailboxClaimReconciler.Plan plan = MailboxClaimReconciler.plan(Set.of(delivery), scan);

        assertEquals(Set.of(delivery), plan.stagedDeliveries().keySet(),
                "游标上的凭据必须被识别为已交付");
        assertEquals(DeliveryFreezeRules.CURSOR_SLOT, plan.stagedDeliveries().get(delivery),
                "确认动作应定位到游标槽位");
        assertTrue(plan.newDeliveries().isEmpty(), "不得重新入包，否则就是重复领取");
        assertFalse(plan.duplicateClaimAllowed());
    }

    @Test
    void reconnectAfterInventoryReceiptDoesNotRedeliver() {
        UUID delivery = UUID.randomUUID();
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(
                DeliveryFreezeRules.collectMarkers(3, new String[]{null, delivery.toString(), null}, null));

        MailboxClaimReconciler.Plan plan = MailboxClaimReconciler.plan(Set.of(delivery), scan);

        assertEquals(Set.of(delivery), plan.stagedDeliveries().keySet());
        assertEquals(1, plan.stagedDeliveries().get(delivery));
        assertTrue(plan.newDeliveries().isEmpty());
    }

    @Test
    void reconnectWithoutAnyReceiptDeliversExactlyOnce() {
        UUID delivery = UUID.randomUUID();
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(
                DeliveryFreezeRules.collectMarkers(3, new String[]{null, null, null}, null));

        MailboxClaimReconciler.Plan plan = MailboxClaimReconciler.plan(Set.of(delivery), scan);

        assertTrue(plan.stagedDeliveries().isEmpty());
        assertEquals(Set.of(delivery), plan.newDeliveries(),
                "从没发过的投递才需要入包");
    }

    @Test
    void duplicateStagedReceiptIsQuarantinedInsteadOfRedelivered() {
        UUID delivery = UUID.randomUUID();
        // 同一凭据同时留在物品栏与游标：无法定位唯一物品，必须隔离。
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(
                DeliveryFreezeRules.collectMarkers(3,
                        new String[]{delivery.toString(), null, null}, delivery.toString()));

        assertTrue(MailboxClaimReconciler.quarantinesDuplicates(scan),
                "重复凭据必须隔离，不得进入重发或确认路径");

        MailboxClaimReconciler.Plan plan = MailboxClaimReconciler.plan(Set.of(delivery), scan);
        assertTrue(plan.stagedDeliveries().isEmpty());
        assertEquals(Set.of(delivery), plan.newDeliveries());
    }

    @Test
    void malformedCursorReceiptIsReportedAndDeliveryIsNotTrapped() {
        UUID delivery = UUID.randomUUID();
        DeliveryReceiptRules.Scan scan = DeliveryReceiptRules.scan(
                DeliveryFreezeRules.collectMarkers(2, new String[]{null, null}, "not-a-uuid"));

        MailboxClaimReconciler.Plan plan = MailboxClaimReconciler.plan(Set.of(delivery), scan);

        assertEquals(List.of(DeliveryFreezeRules.CURSOR_SLOT), plan.invalidSlots(),
                "畸形游标凭据要能被清理，否则玩家永久卡住");
        assertEquals(Set.of(delivery), plan.newDeliveries());
    }
}
