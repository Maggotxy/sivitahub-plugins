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
     *
     * <p>原实现只写死检查 SALVAGE 一条：只要分解还在，销毁（DESTROYED）整条
     * 退出路径消失也逃得过启动自检。现在改为逐条枚举 {@link EquipmentOperationKind}，
     * 每个声明为「分解到底」的操作都必须真的落在状态机认可的退出终态上。</p>
     */
    public static boolean hasExitPath() {
        return exitPathFailures().isEmpty();
    }

    /**
     * 启动自检的失败原因清单：哪一条退出路径不成立，说清楚到具体 kind。
     *
     * <p>返回空列表表示自检通过。启动路径只把它当布尔用，但两个分支都有
     * 行为级测试直接断言，避免自检退化成一个永远为真的日志。</p>
     */
    public static java.util.List<String> exitPathFailures() {
        return exitPathFailures(DestroyRules::descendsToTerminal, EquipmentOperationKind.values());
    }

    /**
     * 注入口径与 kind 集合的重载：让「自检真的逐条验过每个 kind」可被直接断言，
     * 而不是只能相信实现里写了一句 for 循环。
     *
     * @param descends 哪些 kind 算「分解到底」（生产路径固定为 {@link DestroyRules#descendsToTerminal}）
     * @param kinds    要验的操作集合（生产路径为全部 kind）
     */
    static java.util.List<String> exitPathFailures(
            java.util.function.Predicate<EquipmentOperationKind> descends,
            EquipmentOperationKind[] kinds) {
        java.util.List<String> failures = new java.util.ArrayList<>();
        if (!EquipmentLifecycle.requiresExitPath()) {
            failures.add("状态机声明了必须存在退出路径，但 requiresExitPath() 返回 false");
            return java.util.List.copyOf(failures);
        }
        if (descends == null || kinds == null) {
            failures.add("退出路径自检没有可用的 kind 来源");
            return java.util.List.copyOf(failures);
        }
        int inspected = 0;
        for (EquipmentOperationKind kind : kinds) {
            if (kind == null || !descends.test(kind)) continue;
            inspected++;
            if (!DestroyRules.outcomeIsTerminal(kind)) {
                failures.add(kind + " 声明为分解到底，但结果态不是状态机认可的退出终态");
            } else if (!isExitPath(kind)) {
                failures.add(kind + " 的结果态未被状态机视为退出路径");
            }
        }
        if (inspected == 0) {
            failures.add("没有任何 kind 声明为分解到底：C-008 的拆解类退出路径已整条消失");
        }
        return java.util.List.copyOf(failures);
    }
}
