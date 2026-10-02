package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-008 行为级测试：四个真实入口（分解 / 献祭传承 / 交易解绑）调用到的
 * 门禁判定，覆盖「可交易/不可交易 × 需要退出路径/不需要」四种组合。
 *
 * <p>断言的是真的调用 {@link EquipmentExitPathGate} 与读返回值，
 * 不是源码字符串匹配。</p>
 */
class EquipmentExitPathGateTest {
    @Test
    void tradeableForgedGearIsRefusedOnSalvageAndLegacy() {
        assertFalse(EquipmentExitPathGate.allowsExit(true, true, EquipmentOperationKind.SALVAGE));
        assertFalse(EquipmentExitPathGate.allowsExit(true, true, EquipmentOperationKind.LEGACY));
    }

    @Test
    void boundForgedGearKeepsItsExitPaths() {
        assertTrue(EquipmentExitPathGate.allowsExit(true, false, EquipmentOperationKind.SALVAGE));
        assertTrue(EquipmentExitPathGate.allowsExit(true, false, EquipmentOperationKind.LEGACY));
    }

    @Test
    void nonForgedItemsAreNeverBlocked() {
        // 原版物品不归装备状态机管：无论有没有解绑签名都放行。
        assertTrue(EquipmentExitPathGate.allowsExit(false, false, EquipmentOperationKind.SALVAGE));
        assertTrue(EquipmentExitPathGate.allowsExit(false, true, EquipmentOperationKind.SALVAGE));
        assertTrue(EquipmentExitPathGate.allowsExit(false, true, EquipmentOperationKind.LEGACY));
    }

    @Test
    void nullOrUnknownKindFailsClosed() {
        assertFalse(EquipmentExitPathGate.allowsExit(true, false, null));
        assertFalse(EquipmentExitPathGate.allowsExit(false, false, null));
    }

    @Test
    void unbindResultMustLandInTheTradeableState() {
        // 解绑成功写了解绑签名 -> 可交易；没写 -> 拒绝，避免把装备放进账本不接受的身份。
        assertTrue(EquipmentExitPathGate.unbindResultIsTradeable(true));
        assertFalse(EquipmentExitPathGate.unbindResultIsTradeable(false));
    }
}
