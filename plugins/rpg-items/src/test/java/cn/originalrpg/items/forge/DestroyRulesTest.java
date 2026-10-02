package cn.originalrpg.items.forge;

import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.rules.EquipmentLifecycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C-008 行为级测试：分解到底的终态（DESTROYED）判定真的读返回值，不是源码匹配。 */
class DestroyRulesTest {
    @Test
    void destroyLandsInASecondTerminalExitStateBesideSalvage() {
        // W3-A3 之前只有 SALVAGED 一个终态出口；DESTROYED 现在也有生产操作了。
        EquipmentLifecycle.State salvaged = EquipmentLifecycle.afterOperation(
                EquipmentOperationKind.SALVAGE, EquipmentLifecycle.seedForOperation(EquipmentOperationKind.SALVAGE));
        EquipmentLifecycle.State destroyed = EquipmentLifecycle.afterOperation(
                EquipmentOperationKind.DESTROY, EquipmentLifecycle.seedForOperation(EquipmentOperationKind.DESTROY));
        assertTrue(EquipmentLifecycle.isTerminal(salvaged));
        assertTrue(EquipmentLifecycle.isTerminal(destroyed));
        assertTrue(EquipmentLifecycle.isExitPath(destroyed));
        assertNotEquals(salvaged, destroyed, "分解与明确销毁必须是两个不同的终态");
    }

    @Test
    void bothTerminalExitsAreDeclaredAndReachableFromEveryOwningState() {
        for (EquipmentOperationKind kind : new EquipmentOperationKind[]{
                EquipmentOperationKind.SALVAGE, EquipmentOperationKind.DESTROY}) {
            assertTrue(DestroyRules.descendsToTerminal(kind), kind + " 必须声明为分解到底");
            assertTrue(DestroyRules.outcomeIsTerminal(kind), kind + " 的结果态必须是终态");
            for (EquipmentLifecycle.State from : new EquipmentLifecycle.State[]{
                    EquipmentLifecycle.State.BOUND, EquipmentLifecycle.State.UNBOUND}) {
                EquipmentLifecycle.State to = EquipmentLifecycle.afterOperation(kind, from);
                assertTrue(EquipmentLifecycle.descendsToTerminalExit(from, to),
                        from + " 必须能走完 " + kind + " 到终态");
            }
        }
    }

    @Test
    void nonTerminalKindsAndNoOpOutcomesAreRejected() {
        // 只发一条结果消息、物品原样不动的不变操作会把已花的金币留在没推进的身份上。
        assertFalse(DestroyRules.descendsToTerminal(EquipmentOperationKind.REFINEMENT));
        assertFalse(DestroyRules.descendsToTerminal(EquipmentOperationKind.LIMIT_BREAK));
        assertFalse(DestroyRules.descendsToTerminal(EquipmentOperationKind.UNBIND));
        assertFalse(DestroyRules.descendsToTerminal(EquipmentOperationKind.LEGACY));
        assertFalse(DestroyRules.outcomeIsTerminal(null));
        // 终态不能再操作：afterOperation 明确返回 null，不能当成合法结果。
        assertNull(EquipmentLifecycle.afterOperation(EquipmentOperationKind.DESTROY, EquipmentLifecycle.State.SALVAGED));
        assertNull(EquipmentLifecycle.afterOperation(EquipmentOperationKind.DESTROY, EquipmentLifecycle.State.DESTROYED));
        assertFalse(EquipmentLifecycle.descendsToTerminalExit(EquipmentLifecycle.State.SALVAGED, null));
    }

    /**
     * 「分解到底」必须与「终态」区分开：传承（LEGACY_EXTRACTED）也是终态，
     * 但它不属于分解路径。
     *
     * <p>这条区分是 {@link DestroyRules#outcomeIsTerminal} 改用
     * {@code descendsToTerminalExit(from, to)} 而不是 {@code isTerminal(outcome)}
     * 的原因：用后者时 LEGACY 也会被判成「分解到底」，将来谁把 LEGACY 也接进
     * 邮箱通道就会被静默放过。</p>
     */
    @Test
    void terminalExitIsNarrowerThanTerminalStateSoLegacyIsNotTreatedAsSalvage() {
        EquipmentLifecycle.State legacy = EquipmentLifecycle.afterOperation(
                EquipmentOperationKind.LEGACY, EquipmentLifecycle.seedForOperation(EquipmentOperationKind.LEGACY));
        assertTrue(EquipmentLifecycle.isTerminal(legacy),
                "传承已经落进终态集合，这是另一个事实");
        assertFalse(EquipmentLifecycle.descendsToTerminalExit(EquipmentLifecycle.State.BOUND, legacy),
                "传承不是「分解到底」，不得占用销毁的终态出口口径");

        // 只有 BOUND / UNBOUND 这两条有主状态能走完分解到底。
        for (EquipmentLifecycle.State from : EquipmentLifecycle.State.values()) {
            EquipmentLifecycle.State to = EquipmentLifecycle.afterOperation(EquipmentOperationKind.DESTROY, from);
            boolean descends = EquipmentLifecycle.descendsToTerminalExit(from, to);
            if (from == EquipmentLifecycle.State.BOUND || from == EquipmentLifecycle.State.UNBOUND) {
                assertTrue(descends, from + " 必须有销毁灭路径");
            } else {
                assertFalse(descends, from + " 不该能直接跳到 DESTROYED");
            }
        }
        assertFalse(EquipmentLifecycle.descendsToTerminalExit(null, EquipmentLifecycle.State.DESTROYED));
    }

    @Test
    void persistedStateGateOnlyAcceptsRealTerminalStates() {
        assertTrue(DestroyRules.persistedStateIsTerminal("DESTROYED"));
        assertTrue(DestroyRules.persistedStateIsTerminal("SALVAGED"));
        assertTrue(DestroyRules.persistedStateIsTerminal("LEGACY_EXTRACTED"));
        assertFalse(DestroyRules.persistedStateIsTerminal("BOUND"));
        assertFalse(DestroyRules.persistedStateIsTerminal("UNBOUND"));
        // 并发竞争下预登记行可能被别人先行推进，读不到行就不能再扣金币。
        assertFalse(DestroyRules.persistedStateIsTerminal(null));
        assertFalse(DestroyRules.persistedStateIsTerminal(""));
        assertFalse(DestroyRules.persistedStateIsTerminal("NOT_A_STATE"));
    }

    @Test
    void terminalTombstoneBlocksPlayerFacingMutations() {
        assertTrue(DestroyRules.blocksFurtherDurabilityMutation("item-terminal:DESTROYED:x"));
        // 历史装备没有墓碑子键，不能被误判成已销毁。
        assertFalse(DestroyRules.blocksFurtherDurabilityMutation(null));
        assertFalse(DestroyRules.blocksFurtherDurabilityMutation(""));
        assertTrue(DestroyedTerminalTicket.isPersistedTerminal(EquipmentLifecycle.State.DESTROYED));
        assertTrue(DestroyedTerminalTicket.isPersistedTerminal(EquipmentLifecycle.State.SALVAGED));
        assertFalse(DestroyedTerminalTicket.isPersistedTerminal(EquipmentLifecycle.State.BOUND));
        assertFalse(DestroyedTerminalTicket.isPersistedTerminal(null));
    }
}
