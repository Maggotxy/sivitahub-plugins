package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C-008 / C-009 行为级测试：明确销毁入口的门禁真实判定。 */
class DestroyGateTest {
    @Test
    void tradeableForgedGearMustRebindBeforeItMayBeDestroyed() {
        assertFalse(DestroyGate.allowsTerminalExit(true, true, EquipmentOperationKind.DESTROY));
    }

    @Test
    void boundForgedGearKeepsItsTerminalExit() {
        assertTrue(DestroyGate.allowsTerminalExit(true, false, EquipmentOperationKind.DESTROY));
    }

    @Test
    void vanillaItemsAreNeverBlocked() {
        assertTrue(DestroyGate.allowsTerminalExit(false, false, EquipmentOperationKind.DESTROY));
        assertTrue(DestroyGate.allowsTerminalExit(false, true, EquipmentOperationKind.DESTROY));
    }

    @Test
    void otherKindsAndUnknownInputFailClosed() {
        assertFalse(DestroyGate.allowsTerminalExit(true, false, null));
        assertFalse(DestroyGate.allowsTerminalExit(true, false, EquipmentOperationKind.REFINEMENT));
        assertFalse(DestroyGate.allowsTerminalExit(true, false, EquipmentOperationKind.LEGACY));
    }
}
