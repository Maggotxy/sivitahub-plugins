package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;

/**
 * C-008 / C-009 退出路径：销毁产物的交付通道判定。
 *
 * <p>{@code /salvage}、{@code /legacy} 与 {@code /unbind} 的产物都经装备托管出站
 * （outbox）进入唯一市场邮箱，再由 {@code /market claim} 取出。{@code /destroy}
 * 若把「绑定锻造精华」直接塞进玩家背包，同一条退出路径就有两条不同的交付语义：
 * 背包已满时产物会被静默丢弃（玩家永久失去了被销毁装备换来的唯一回报），
 * 而且这条旁路绕过了市场邮箱的单航班领取与幂等落库。</p>
 *
 * <p>这里把该决策抽成不依赖 Bukkit 的纯函数，让「销毁到底走哪条交付通道」
 * 可以被行为级测试直接断言，而不是散落在指令实现里。</p>
 */
public final class DestroyDeliveryRules {
    private DestroyDeliveryRules() {
    }

    /** 销毁产物的交付通道。 */
    public enum Lane {
        /** 经装备托管出站进入市场邮箱（唯一权威交付通道）。 */
        ESCROW_MAILBOX,
        /** 不改变玩家资产，只发一条结果消息。 */
        MESSAGE_ONLY
    }

    /**
     * 该操作的结果必须走哪条通道。
     *
     * <p>只有「分解到底」的终态操作会产出物品，因此都必须走邮箱；其余操作
     * （精炼 / 突破 / 解绑）不在这里判定，交给它们各自的结算路径。</p>
     */
    static Lane laneFor(EquipmentOperationKind kind) {
        if (kind == null) return Lane.MESSAGE_ONLY;
        return DestroyRules.descendsToTerminal(kind) ? Lane.ESCROW_MAILBOX : Lane.MESSAGE_ONLY;
    }

    /**
     * 结算被拒时原物退回哪条通道。
     *
     * <p>被拒的销毁不能凭空吞掉原装备：拒绝收据已经落库，原物必须经同一托管
     * 出站的 recovery 通道退回玩家，否则「生命周期拒绝」就等于一次无提示的
     * 永久删除。常量返回也能防止将来有人把拒绝分支改成 {@code MESSAGE_ONLY}。</p>
     */
    static Lane refundLaneFor(EquipmentOperationKind kind) {
        if (kind == null) return Lane.MESSAGE_ONLY;
        return DestroyRules.descendsToTerminal(kind) ? Lane.ESCROW_MAILBOX : Lane.MESSAGE_ONLY;
    }
}
