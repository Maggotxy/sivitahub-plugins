package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import org.junit.jupiter.api.Test;

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
}
