package cn.originalrpg.items.forge;

import cn.originalrpg.api.rules.EquipmentLifecycle;

import java.util.UUID;

/**
 * 已进终态的装备在交付里携带的墓碑值。
 *
 * <p>终态装备（{@code DESTROYED}）只能经数据库权威路径（邮箱交付、审计快照）
 * 再次出现。携带墓碑意味着「它上次被读到时已经在终态」，因此不能再按可操作
 * 装备加入锻造菜单或触发耐久变化；未携带墓碑的历史装备不受影响。</p>
 */
final class DestroyedTerminalTicket {
    private static final String PREFIX = "item-terminal:";

    private DestroyedTerminalTicket() {
    }

    static String stateFor(UUID operationId) {
        return PREFIX + EquipmentLifecycle.State.DESTROYED.name() + ":" + operationId;
    }

    /** 终态、或已进入终态分离（SALVAGED）的装备都不再允许耐久变化。 */
    static boolean isPersistedTerminal(EquipmentLifecycle.State state) {
        return state != null && EquipmentLifecycle.isTerminal(state);
    }
}
