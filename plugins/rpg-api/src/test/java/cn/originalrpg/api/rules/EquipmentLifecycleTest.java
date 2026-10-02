package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.EquipmentOperationKind;
import cn.originalrpg.api.rules.EquipmentLifecycle.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EquipmentLifecycleTest {
    @Test
    void everyNonTerminalStateIsReachableFromCreation() {
        for (State target : State.values()) {
            if (target == State.CREATED || EquipmentLifecycle.isTerminal(target)) continue;
            assertTrue(reachable(State.CREATED, target, 6),
                    "state " + target + " must be reachable from CREATED without dead-ending");
        }
    }

    @Test
    void terminalStatesAcceptNoTransitionAndExitPathsAreDeclared() {
        for (State terminal : new State[]{State.SALVAGED, State.LEGACY_EXTRACTED, State.DESTROYED}) {
            for (State target : State.values()) {
                assertFalse(EquipmentLifecycle.isValidTransition(terminal, target));
            }
        }
        assertTrue(EquipmentLifecycle.isExitPath(State.SALVAGED));
        assertTrue(EquipmentLifecycle.isExitPath(State.SACRIFICED));
        assertTrue(EquipmentLifecycle.isExitPath(State.SEASON_ATTUNED));
        assertFalse(EquipmentLifecycle.isExitPath(State.UNBOUND));
    }

    @Test
    void onlyUnboundAndListedGearMayChangeHands() {
        assertFalse(EquipmentLifecycle.isTradeable(State.CREATED));
        assertFalse(EquipmentLifecycle.isTradeable(State.BOUND));
        assertTrue(EquipmentLifecycle.isTradeable(State.UNBOUND));
        assertTrue(EquipmentLifecycle.isTradeable(State.LISTED));
    }

    @Test
    void noLiveStateCanTrapAnIdentity() {
        // C-008: every state the ledger can rest in must still lead to an exit.
        // A verified thaw (RESTORED) that only re-opened circulation but not
        // decomposition would strand the identity behind a paid refine.
        for (State state : State.values()) {
            if (EquipmentLifecycle.isTerminal(state)) continue;
            assertTrue(reachesExit(state, 6),
                    "state " + state + " must still be able to reach an exit path");
        }
    }

    @Test
    void quarantineAndRestoreAreAvailableFromEveryLiveState() {
        for (State live : new State[]{State.CREATED, State.BOUND, State.UNBOUND, State.LISTED, State.SOLD,
                State.SEASON_ATTUNED, State.RESTORED}) {
            assertTrue(EquipmentLifecycle.isValidTransition(live, State.QUARANTINED), live.toString());
        }
        assertTrue(EquipmentLifecycle.isValidTransition(State.QUARANTINED, State.RESTORED));
        assertTrue(EquipmentLifecycle.isValidTransition(State.QUARANTINED, State.DESTROYED));
        // A restored identity re-enters circulation and keeps its exits; the
        // unbind fee stays refused, since custody is not a lifecycle decision.
        assertTrue(EquipmentLifecycle.isValidTransition(State.RESTORED, State.UNBOUND));
        assertTrue(EquipmentLifecycle.isValidTransition(State.RESTORED, State.SALVAGED));
        assertTrue(EquipmentLifecycle.isValidTransition(State.RESTORED, State.LISTED));
        // The unbind fee stays refused on a restored identity: custody is not
        // a lifecycle decision, and the freeze hid what the PDC actually held.
        assertNull(EquipmentLifecycle.afterOperation(EquipmentOperationKind.UNBIND, State.RESTORED));
    }

    @Test
    void forgeOperationIntroducesCreatedIdentity() {
        assertEquals(State.CREATED, EquipmentLifecycle.afterOperation(EquipmentOperationKind.FORGE, State.CREATED));
        assertEquals(State.CREATED, EquipmentLifecycle.afterOperation(EquipmentOperationKind.FORGE, State.BOUND));
    }

    @Test
    void operationMappingRefusesIllegalTransitionsAgainstRegistryState() {
        // Terminal identities refuse every mutation; crash replays never reach this
        // gate because the durable operation receipt short-circuits first.
        assertNull(EquipmentLifecycle.afterOperation(EquipmentOperationKind.SALVAGE, State.SALVAGED));
        assertNull(EquipmentLifecycle.afterOperation(EquipmentOperationKind.REFINEMENT, State.DESTROYED));
        // Unbinding gear that never had an owner is refused.
        assertNull(EquipmentLifecycle.afterOperation(EquipmentOperationKind.UNBIND, State.CREATED));
        // Refining listed (escrowed) gear is refused until it returns to the seller.
        assertNull(EquipmentLifecycle.afterOperation(EquipmentOperationKind.REFINEMENT, State.LISTED));
        // Bound refinement and legacy extraction are the canonical exits.
        assertEquals(State.BOUND, EquipmentLifecycle.afterOperation(EquipmentOperationKind.REFINEMENT, State.BOUND));
        assertEquals(State.BOUND, EquipmentLifecycle.afterOperation(EquipmentOperationKind.LIMIT_BREAK, State.CREATED));
        assertEquals(State.UNBOUND, EquipmentLifecycle.afterOperation(EquipmentOperationKind.UNBIND, State.BOUND));
        assertEquals(State.LEGACY_EXTRACTED,
                EquipmentLifecycle.afterOperation(EquipmentOperationKind.LEGACY, State.BOUND));
        assertEquals(State.SALVAGED, EquipmentLifecycle.afterOperation(EquipmentOperationKind.SALVAGE, State.BOUND));
    }

    private static boolean reachable(State from, State target, int depth) {
        if (depth == 0) return false;
        for (State next : State.values()) {
            if (!EquipmentLifecycle.isValidTransition(from, next)) continue;
            if (next == target || reachable(next, target, depth - 1)) return true;
        }
        return false;
    }

    private static boolean reachesExit(State from, int depth) {
        if (depth == 0) return false;
        for (State next : State.values()) {
            if (!EquipmentLifecycle.isValidTransition(from, next)) continue;
            if (EquipmentLifecycle.isExitPath(next) || reachesExit(next, depth - 1)) return true;
        }
        return false;
    }
}
