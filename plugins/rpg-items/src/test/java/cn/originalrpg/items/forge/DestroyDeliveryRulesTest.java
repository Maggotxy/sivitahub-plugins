package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C-008 / C-009 行为级测试：销毁产物的交付通道真的读返回值。
 *
 * <p>负向对照：把 {@code DestroyRules.deliveryLane} 改成返回 {@code MESSAGE_ONLY}
 * 后，{@link #destroyProductsMustTravelTheEscrowMailbox()} 会失败。</p>
 */
class DestroyDeliveryRulesTest {

    @Test
    void destroyProductsMustTravelTheEscrowMailbox() {
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyRules.deliveryLane(EquipmentOperationKind.DESTROY));
        // /salvage 与 /legacy 已在前一轮接线到同一条邮箱通道，两条「分解到底」
        // 的退出路径不能有两种交付语义。
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyRules.deliveryLane(EquipmentOperationKind.SALVAGE));
    }

    @Test
    void nonTerminalOperationsNeverClaimTheMailbox() {
        for (EquipmentOperationKind kind : new EquipmentOperationKind[]{
                EquipmentOperationKind.REFINEMENT, EquipmentOperationKind.LIMIT_BREAK,
                EquipmentOperationKind.UNBIND, EquipmentOperationKind.FORGE}) {
            assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY, DestroyDeliveryRules.laneFor(kind),
                    kind + " 不产出「分解到底」的终态产物，不得占用邮箱通道");
        }
        assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY, DestroyDeliveryRules.laneFor(null));
    }

    @Test
    void aRefusedSettlementStillReturnsTheOriginalThroughTheMailbox() {
        // 拒绝不能吞装备：原物必须经 recovery 通道退回，否则「生命周期拒绝」＝无提示删除。
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyRules.refundLane(EquipmentOperationKind.DESTROY));
        assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY, DestroyRules.refundLane(null));
    }
}
