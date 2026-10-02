package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;

/**
 * C-008 接线点：{@link DestroyCommand} 落库前的退出路径判定。
 *
 * <p>与分解/传承同一条机制：已付费解绑（{@code trade_unlocked}）的自制装备
 * 不能直接走销毁，必须先重新绑定，否则等于绕过唯一市场通道（C-009）。
 * 判定不通过时不做任何 PDC 修改，失败关闭。</p>
 */
final class DestroyGate {
    private DestroyGate() {
    }

    static boolean allowsTerminalExit(boolean custom, boolean tradeUnlocked, EquipmentOperationKind kind) {
        if (!DestroyRules.descendsToTerminal(kind)) return false;
        return EquipmentExitPathGate.allowsExit(custom, tradeUnlocked, kind);
    }
}
