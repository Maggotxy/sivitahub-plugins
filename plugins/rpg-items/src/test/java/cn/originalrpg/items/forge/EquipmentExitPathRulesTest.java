package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-008 行为级测试：可交易 / 不可交易 × 需要退出路径 / 不需要 四种组合。
 *
 * <p>断言的是真实判定方法（真的执行 {@link EquipmentExitPathRules} 并读返回值），
 * 不是源码字符串匹配。</p>
 */
class EquipmentExitPathRulesTest {
    @Test
    void unboundTradeableGearMayNotTakeAnExitShortcut() {
        // 可交易 + 需要退出路径（分解 / 献祭 / 传承）＝ 必须拒绝。
        assertTrue(EquipmentExitPathRules.isTradeable(true));
        for (EquipmentOperationKind kind : new EquipmentOperationKind[]{
                EquipmentOperationKind.SALVAGE, EquipmentOperationKind.LEGACY}) {
            assertTrue(EquipmentExitPathRules.isExitPath(kind), kind + " 必须是退出路径");
            assertTrue(EquipmentExitPathRules.bypassesExitPath(true, kind),
                    kind + " 在可交易状态下必须被拒绝（不能走捷径）");
        }
    }

    @Test
    void boundGearMayAlwaysTakeItsExitPath() {
        // 不可交易 + 需要退出路径 ＝ 允许（这是 C-008 要求的正常销毁路径）。
        assertFalse(EquipmentExitPathRules.isTradeable(false));
        for (EquipmentOperationKind kind : new EquipmentOperationKind[]{
                EquipmentOperationKind.SALVAGE, EquipmentOperationKind.LEGACY}) {
            assertFalse(EquipmentExitPathRules.bypassesExitPath(false, kind),
                    kind + " 在绑定状态下必须放行");
        }
    }

    @Test
    void tradeableGearMayStillBeRefinedBecauseThatIsNotAnExit() {
        // 可交易 + 不需要退出路径（精炼 / 突破）＝ 允许，不是退出路径。
        for (EquipmentOperationKind kind : new EquipmentOperationKind[]{
                EquipmentOperationKind.REFINEMENT, EquipmentOperationKind.LIMIT_BREAK}) {
            assertFalse(EquipmentExitPathRules.isExitPath(kind), kind + " 不是退出路径");
            assertFalse(EquipmentExitPathRules.bypassesExitPath(true, kind),
                    kind + " 在可交易状态下仍应放行");
        }
    }

    @Test
    void boundGearMayBeRefinedAsBefore() {
        // 不可交易 + 不需要退出路径 ＝ 允许，保持原有玩法。
        for (EquipmentOperationKind kind : new EquipmentOperationKind[]{
                EquipmentOperationKind.REFINEMENT, EquipmentOperationKind.LIMIT_BREAK}) {
            assertFalse(EquipmentExitPathRules.bypassesExitPath(false, kind));
        }
    }

    @Test
    void forgeIsNeverAnExitAndNullOrGarbageFailsClosed() {
        assertFalse(EquipmentExitPathRules.isExitPath(EquipmentOperationKind.FORGE));
        assertFalse(EquipmentExitPathRules.isExitPath(null));
        assertFalse(EquipmentExitPathRules.bypassesExitPath(true, null));
        assertFalse(EquipmentExitPathRules.bypassesExitPath(false, null));
    }

    @Test
    void everyHighValueItemTypeDeclaresAnExitPath() {
        // C-008 第 2 条：只进不出视为设计缺陷。
        assertTrue(EquipmentExitPathRules.hasExitPath());
        assertTrue(EquipmentExitPathRules.isExitPath(EquipmentOperationKind.SALVAGE));
        assertTrue(EquipmentExitPathRules.isExitPath(EquipmentOperationKind.LEGACY));
    }

    /**
     * 启动自检必须覆盖**每一条**「分解到底」的操作，而不是写死 SALVAGE 一条。
     *
     * <p>原实现只问 SALVAGE：销毁（DESTROYED）整条退出路径消失时自检仍为真。
     * 这里用注入的 kind 集合直接驱动真实的自检实现，把「漏检销毁」这件事变成
     * 一条会失败的断言，而不是靠读源码相信它写了 for 循环。</p>
     */
    @Test
    void theStartupSelfCheckCoversEverySalvageLikeExitNotJustSalvageItself() {
        assertEquals(java.util.List.of(), EquipmentExitPathRules.exitPathFailures(),
                "当前所有拆解类退出路径都应通过自检，实际失败项："
                        + EquipmentExitPathRules.exitPathFailures());

        // 只喂 SALVAGE 一条：老实现会返回 true，新实现也必须通过（分解本身是好的）。
        assertEquals(java.util.List.of(), EquipmentExitPathRules.exitPathFailures(
                        DestroyRules::descendsToTerminal,
                        new EquipmentOperationKind[]{EquipmentOperationKind.SALVAGE}),
                "只看分解时自检应通过");

        // 只喂 DESTROY 一条：写死 SALVAGE 的老实现根本不会检查它。
        // 新实现必须真的验过销毁，并在它成立时同样返回空。
        assertEquals(java.util.List.of(), EquipmentExitPathRules.exitPathFailures(
                        DestroyRules::descendsToTerminal,
                        new EquipmentOperationKind[]{EquipmentOperationKind.DESTROY}),
                "只看销毁时自检也必须通过，证明销毁这条线真的在自检覆盖面内");

        // 全量：两条都在，且当前全仓的拆解类 kind 恰好是这两条。
        assertEquals(java.util.List.of(), EquipmentExitPathRules.exitPathFailures(
                DestroyRules::descendsToTerminal, EquipmentOperationKind.values()));
    }

    /**
     * 自检不是恒空的摆设：口径被改坏 / kind 来源为空时必须报出失败项。
     */
    @Test
    void theStartupSelfCheckReportsBrokenOrEmptyExitCoverage() {
        // 口径全 false（等价于全仓没有任何拆解类退出路径）：必须报错。
        assertFalse(EquipmentExitPathRules.exitPathFailures(kind -> false,
                EquipmentOperationKind.values()).isEmpty(),
                "没有任何 kind 声明为分解到底时自检必须红，不能静默放行");
        assertEquals(1, EquipmentExitPathRules.exitPathFailures(kind -> false,
                EquipmentOperationKind.values()).size());
        assertTrue(EquipmentExitPathRules.exitPathFailures(kind -> false,
                EquipmentOperationKind.values()).get(0).contains("没有任何 kind"));

        // 口径为 null：失败关闭，不得默认通过。
        assertFalse(EquipmentExitPathRules.exitPathFailures(null,
                EquipmentOperationKind.values()).isEmpty());
        assertFalse(EquipmentExitPathRules.exitPathFailures(DestroyRules::descendsToTerminal,
                null).isEmpty());

        // 只喂非拆解类 kind：同样等于「没有拆解类退出路径」，必须报错。
        assertFalse(EquipmentExitPathRules.exitPathFailures(DestroyRules::descendsToTerminal,
                        new EquipmentOperationKind[]{EquipmentOperationKind.REFINEMENT,
                                EquipmentOperationKind.FORGE})
                .isEmpty(), "只喂精炼/锻造时不得当成「退出路径齐备」");

        // 恒空实现会连着两条正例一起放过，因此上面这些反例是自检的活性证明。
        assertTrue(EquipmentExitPathRules.exitPathFailures(DestroyRules::descendsToTerminal,
                EquipmentOperationKind.values()).isEmpty());
    }
}
