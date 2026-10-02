package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-008 / C-009 行为级测试：销毁产物的交付通道真的读返回值。
 *
 * <p>负向对照（实测）：把 {@link DestroyDeliveryRules#laneFor} 的返回值改成常量
 * {@code MESSAGE_ONLY} 后，{@link #destroyProductsMustTravelTheEscrowMailbox()}
 * 会失败。</p>
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

    /**
     * 通道判定必须真的读「该操作是否分解到底」这个输入，而不是自己写死一个答案。
     *
     * <p>这条测试的存在理由：原文只断言了 {@link DestroyRules#deliveryLane} 的
     * 返回值，等于把口径与消费者一起钉死成常量，无法发现「判定表被改坏」。
     * 这里注入口径，正反两种输入都必须真的改变交付通道。</p>
     */
    @Test
    void theLaneFollowsTheInjectedTerminalExitPredicateInsteadOfBeingABakedInConstant() {
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyDeliveryRules.laneFor(EquipmentOperationKind.DESTROY, kind -> true),
                "口径判定为「分解到底」时必须走邮箱");
        assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY,
                DestroyDeliveryRules.laneFor(EquipmentOperationKind.DESTROY, kind -> false),
                "口径判定为「不是分解到底」时不得占用邮箱通道");

        // 口径本身也必须真的按 kind 判定，而不是「只要不是 null 就走邮箱」。
        assertEquals(DestroyDeliveryRules.Lane.ESCROW_MAILBOX,
                DestroyDeliveryRules.laneFor(EquipmentOperationKind.SALVAGE, DestroyRules::descendsToTerminal));
        assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY,
                DestroyDeliveryRules.laneFor(EquipmentOperationKind.REFINEMENT, DestroyRules::descendsToTerminal));
        // 没有口径时失败关闭，不得默认走邮箱。
        assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY,
                DestroyDeliveryRules.laneFor(EquipmentOperationKind.DESTROY, null));
        assertEquals(DestroyDeliveryRules.Lane.MESSAGE_ONLY, DestroyDeliveryRules.laneFor(null, kind -> true));
    }

    /**
     * C-009：成功通道与退回通道必须是同一套托管面，不能分裂成两个收件箱。
     */
    @Test
    void resultAndRefundLanesNeverSplitIntoTwoEscrowSurfaces() {
        for (EquipmentOperationKind kind : new EquipmentOperationKind[]{
                EquipmentOperationKind.DESTROY, EquipmentOperationKind.SALVAGE,
                EquipmentOperationKind.REFINEMENT, EquipmentOperationKind.UNBIND}) {
            assertTrue(DestroyDeliveryRules.lanesAgree(kind),
                    kind + " 的成功产物与拒绝退回必须走同一条通道");
        }
        // 两条终态退出路径的通道必须一致：同一条「分解到底」不能有两种交付语义。
        assertEquals(DestroyDeliveryRules.laneFor(EquipmentOperationKind.SALVAGE),
                DestroyDeliveryRules.laneFor(EquipmentOperationKind.DESTROY));
    }

    /**
     * 交付口径只有一处定义：两个「分解到底」的 kind 才允许走邮箱。
     *
     * <p>防止将来把 FORGE/REFINEMENT 之类的中间操作也塞进邮箱通道，
     * 那会让每件日常操作都产生一条需要玩家手动领取的邮箱交付。</p>
     */
    @Test
    void onlyTheTwoTerminalExitKindsClaimTheMailbox() {
        int claiming = 0;
        for (EquipmentOperationKind kind : EquipmentOperationKind.values()) {
            if (DestroyDeliveryRules.laneFor(kind) == DestroyDeliveryRules.Lane.ESCROW_MAILBOX) claiming++;
        }
        assertEquals(2, claiming, "当前只有 SALVAGE 与 DESTROY 两条退出路径产出终态交付物");

        assertFalse(DestroyRules.descendsToTerminal(null));
        assertFalse(DestroyRules.descendsToTerminal(EquipmentOperationKind.FORGE));
    }
}
