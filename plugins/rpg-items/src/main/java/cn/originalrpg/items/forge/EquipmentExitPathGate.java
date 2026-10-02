package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;

/**
 * C-008 接线点：真实装备路径落库前的退出路径判定。
 *
 * <p>分解（{@link SalvageCommand}）、献祭/传承（{@link LegacyCommand}）在落库前
 * 调用 {@link #allowsExit}；解绑（{@link UnbindCommand}）在产物落库前调用
 * {@link #unbindResultIsTradeable}。判定不通过时必须**不做任何 PDC 修改**
 * （不清 trade_unlocked、不消耗物品），失败关闭。</p>
 *
 * <p>本类只吃两个布尔事实（是否自制、是否已付费解绑），不依赖 Bukkit，
 * 因此行为级测试可以直接断言判定结果。</p>
 */
final class EquipmentExitPathGate {
    private EquipmentExitPathGate() {
    }

    /**
     * @param custom       是否自制装备（非自制不归状态机管）
     * @param tradeUnlocked PDC 里是否存在解绑签名
     * @return true 表示允许该装备进入这条销毁/退出路径
     */
    static boolean allowsExit(boolean custom, boolean tradeUnlocked, EquipmentOperationKind kind) {
        if (kind == null) return false;
        if (!custom) return true;
        return !EquipmentExitPathRules.bypassesExitPath(tradeUnlocked, kind);
    }

    /**
     * 解绑（唯一交易通道的入口）的产物必须落在状态机认可的「可交易」态。
     * 若解绑没能写出解绑签名，产物就落在一个账本不接受交易的身份上，
     * 这次解绑必须失败关闭（C-008 / C-009）。
     */
    static boolean unbindResultIsTradeable(boolean tradeUnlocked) {
        return EquipmentExitPathRules.isTradeable(tradeUnlocked);
    }
}
