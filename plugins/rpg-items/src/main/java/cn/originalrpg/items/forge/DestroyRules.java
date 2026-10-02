package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.rules.EquipmentLifecycle;

/**
 * C-008 退出路径：把「分解走到底」的终态翻译成状态机认可的 {@code DESTROYED}。
 *
 * <p>W2-B3 已把分解（SALVAGED）与传承（LEGACY_EXTRACTED）接到 {@code /salvage}
 * 与 {@code /legacy}，但状态机里声明的 {@code DESTROYED} 仍没有 src/main 生产入口
 * （全仓 grep 只命中 {@code EquipmentLifecycle} 自身与账本审计查询）。本类是
 * {@code SALVAGE} / {@code DESTROY} 两种「分解到底」操作的结果态来源，
 * 供门禁与耐久监听器的取消判定共用，不再造第二套状态机。</p>
 */
public final class DestroyRules {
    /** 终态装备的墓碑子键：只允许进邮箱，不能再被锻造、对话或丢弃。 */
    public static final String TERMINAL_KEY = "terminal_state";

    private DestroyRules() {
    }

    /** 该操作是否必须把装备推进到「分解到底」的终态。 */
    public static boolean descendsToTerminal(EquipmentOperationKind kind) {
        return kind == EquipmentOperationKind.DESTROY || kind == EquipmentOperationKind.SALVAGE;
    }

    /**
     * 这两个操作声明出的结果态是否真的是终态。
     *
     * <p>只发一条结果消息、物品却原样不动的不变（no-op）操作会被拒绝：
     * 已花的金币会留在一个从未推进的账本身份上（C-002 幂等键 + 审计流水）。</p>
     *
     * <p>终态判定用 {@link EquipmentLifecycle#descendsToTerminalExit}(from, to)
     * 而不是「afterOperation 的结果是不是 isTerminal」：LEGACY_EXTRACTED 也是
     * 终态，但它属于传承路径，不能算「分解到底」。同一条口径同时被指令侧与
     * 账本侧读取，避免出现「指令认为走完了、状态机认为没走完」的分裂。</p>
     */
    public static boolean outcomeIsTerminal(EquipmentOperationKind kind) {
        if (!descendsToTerminal(kind)) return false;
        EquipmentLifecycle.State from = EquipmentLifecycle.seedForOperation(kind);
        EquipmentLifecycle.State outcome = EquipmentLifecycle.afterOperation(kind, from);
        return outcome != null && EquipmentLifecycle.descendsToTerminalExit(from, outcome);
    }

    /**
     * 账本已写入的终态是否真的是终态；写入 null（没有行）不算。
     *
     * <p>预登记行在并发竞争下可能被别人先行推进，此时不能再扣金币。</p>
     */
    public static boolean persistedStateIsTerminal(String state) {
        if (state == null || state.isBlank()) return false;
        try {
            return EquipmentLifecycle.isTerminal(EquipmentLifecycle.State.valueOf(state));
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    /**
     * 销毁产物的交付通道：必须与 {@code /salvage}、{@code /legacy} 一样经装备托管
     * 出站进入市场邮箱，不能直接塞进玩家背包（背包满即静默丢失，且绕过唯一市场
     * 邮箱通道 C-009）。
     */
    public static DestroyDeliveryRules.Lane deliveryLane(EquipmentOperationKind kind) {
        return DestroyDeliveryRules.laneFor(kind);
    }

    /**
     * 结算被拒时原物退回的通道。拒绝不能吞装备。
     */
    public static DestroyDeliveryRules.Lane refundLane(EquipmentOperationKind kind) {
        return DestroyDeliveryRules.refundLaneFor(kind);
    }

    /**
     * 已进终态的装备不再允许锻造/对话/丢弃触发的任何耐久变化。
     *
     * <p>未携带墓碑子键的旧装备不受影响，避免把历史装备误判成已销毁。</p>
     */
    public static boolean blocksFurtherDurabilityMutation(String terminalState) {
        return terminalState != null && !terminalState.isBlank();
    }
}
