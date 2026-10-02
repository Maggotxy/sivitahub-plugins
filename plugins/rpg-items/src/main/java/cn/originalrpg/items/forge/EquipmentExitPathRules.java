package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.rules.EquipmentLifecycle;

/**
 * C-008 消费者：把「装备退出路径」从规则声明接到真实玩法路径上。
 *
 * <p>C-008 原文：每种可重复产出的高价值物品（装备、稀有材料、门票）必须有
 * 至少一条销毁/退出路径：分解 / 献祭 / 传承 / 调谐 / 税收销毁。只进不出视为
 * 设计缺陷。</p>
 *
 * <p>{@link EquipmentLifecycle} 声明了状态机与
 * {@code requiresExitPath() / isExitPath() / isTradeable()}，但全仓此前没有
 * src/main 调用点（gap-matrix §2 M-020/M-028/M-029）。本类是它们的第一个
 * src/main 消费者：把「一次装备操作」翻译成「是否退出路径」与「是否可交易」，
 * 供分解 / 献祭 / 传承 / 交易解绑四条真实入口使用。</p>
 *
 * <p>判定表直接复用 {@link EquipmentOperationKind}，不再造第二套状态机。</p>
 */
public final class EquipmentExitPathRules {
    private EquipmentExitPathRules() {
    }

    /**
     * 该操作是否把装备带离流通：分解（SALVAGED）/ 献祭 / 传承（LEGACY_EXTRACTED）
     * / 调谐 / 销毁。
     *
     * <p>结果态由 {@link EquipmentLifecycle#afterOperation} 给出，因此
     * REFINEMENT / LIMIT_BREAK / FORGE 这些「继续持有」的操作判为 false。</p>
     */
    public static boolean isExitPath(EquipmentOperationKind kind) {
        if (kind == null) return false;
        EquipmentLifecycle.State outcome = EquipmentLifecycle.afterOperation(
                kind, EquipmentLifecycle.seedForOperation(kind));
        return outcome != null && EquipmentLifecycle.isExitPath(outcome);
    }

    /**
     * 该装备是否处于「可交易」状态。PDC 侧只有 trade_unlocked 能证明装备已被
     * 付费解绑，因此它映射到状态机的 UNBOUND（唯一可交易的持有态）。
     */
    public static boolean isTradeable(boolean tradeUnlocked) {
        return EquipmentLifecycle.isTradeable(tradeUnlocked
                ? EquipmentLifecycle.State.UNBOUND : EquipmentLifecycle.State.BOUND);
    }

    /**
     * 机制核心：该走退出路径的装备不能走捷径。
     *
     * <p>一件已付费解绑（trade_unlocked）的装备若被拿去分解/献祭/传承，玩家就
     * 绕过了「先花钱解绑、再上市场」的唯一流通通道（C-009），把市场价值直接
     * 换成销毁产物。要销毁就得先重新绑定，让销毁落在一个有主的身份上。</p>
     *
     * @return true 表示必须拒绝这次操作
     */
    public static boolean bypassesExitPath(boolean tradeUnlocked, EquipmentOperationKind kind) {
        return isTradeable(tradeUnlocked) && isExitPath(kind);
    }

    /**
     * 确认 C-008 的「只进不出视为设计缺陷」不成立：每种高价值物品类型都必须
     * 至少有一条可用退出路径。供启动自检与测试使用。
     */
    public static boolean hasExitPath() {
        return EquipmentLifecycle.requiresExitPath() && EquipmentLifecycle.isExitPath(
                EquipmentLifecycle.afterOperation(EquipmentOperationKind.SALVAGE,
                        EquipmentLifecycle.seedForOperation(EquipmentOperationKind.SALVAGE)));
    }
}
