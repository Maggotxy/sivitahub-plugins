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
 *
 * <p>「哪些操作算分解到底」的口径只有 {@link DestroyRules#descendsToTerminal}
 * 一处；本类不反向依赖它，避免两个类互相引用（那样任何一侧的判定都可能被
 * 另一侧的改动悄悄改掉，而测试仍然只看得见其中一侧）。</p>
 */
public final class DestroyDeliveryRules {
    private DestroyDeliveryRules() {
    }

    /**
     * 通道判定的输入口径：该操作是不是「分解到底」（产出一件绑定精华的终态操作）。
     *
     * <p>由调用方注入，因此测试可以把「已接线」与「未接线」两种口径都跑一遍，
     * 而不是让实现自己去问一个可能被改坏的判定表。</p>
     */
    @FunctionalInterface
    interface TerminalExit {
        boolean test(EquipmentOperationKind kind);
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
        return laneFor(kind, DestroyRules::descendsToTerminal);
    }

    /** 注入口径的重载：让「判定变了交付通道也会跟着变」这件事可被直接断言。 */
    static Lane laneFor(EquipmentOperationKind kind, TerminalExit terminalExit) {
        if (kind == null || terminalExit == null) return Lane.MESSAGE_ONLY;
        return terminalExit.test(kind) ? Lane.ESCROW_MAILBOX : Lane.MESSAGE_ONLY;
    }

    /**
     * 结算被拒时原物退回哪条通道。
     *
     * <p>被拒的销毁不能凭空吞掉原装备：拒绝收据已经落库，原物必须经同一托管
     * 出站的 recovery 通道退回玩家，否则「生命周期拒绝」就等于一次无提示的
     * 永久删除。</p>
     *
     * <p>当前实现与 {@link #laneFor} 同为「分解到底即邮箱」，但这**不是**
     * 刻意写死的常量：退回通道必须跟着同一条口径走，将来若某条退出路径改成
     * 不进邮箱，退回通道不能继续留在邮箱里把原物寄给玩家。</p>
     */
    static Lane refundLaneFor(EquipmentOperationKind kind) {
        return laneFor(kind);
    }

    /**
     * 结果通道与退回通道是否一致。
     *
     * <p>不一致意味着「被拒时原物的去向」和「成功时产物的去向」不是同一个
     * 托管面，这正是 C-009「只能有一套托管结算」要防的分裂。</p>
     */
    static boolean lanesAgree(EquipmentOperationKind kind) {
        return laneFor(kind) == refundLaneFor(kind);
    }
}
