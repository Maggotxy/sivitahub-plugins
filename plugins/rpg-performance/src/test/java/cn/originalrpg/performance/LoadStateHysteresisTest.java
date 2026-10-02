package cn.originalrpg.performance;

import cn.originalrpg.api.model.ServerLoadState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LoadStateHysteresisTest {
    @Test
    void degradesOnlyAfterConsecutiveBadSamples() {
        LoadStateHysteresis policy = new LoadStateHysteresis(3, 4);
        ServerLoadState state = ServerLoadState.NORMAL;
        state = policy.update(state, ServerLoadState.CRITICAL);
        assertEquals(ServerLoadState.NORMAL, state);
        state = policy.update(state, ServerLoadState.CRITICAL);
        assertEquals(ServerLoadState.NORMAL, state);
        state = policy.update(state, ServerLoadState.CRITICAL);
        assertEquals(ServerLoadState.CRITICAL, state);
    }

    @Test
    void oneHealthySampleDoesNotUndoProtection() {
        LoadStateHysteresis policy = new LoadStateHysteresis(1, 3);
        ServerLoadState state = policy.update(ServerLoadState.NORMAL, ServerLoadState.CRITICAL);
        assertEquals(ServerLoadState.CRITICAL, state);
        state = policy.update(state, ServerLoadState.NORMAL);
        assertEquals(ServerLoadState.CRITICAL, state);
        state = policy.update(state, ServerLoadState.PRESSURE);
        assertEquals(ServerLoadState.CRITICAL, state);
        state = policy.update(state, ServerLoadState.NORMAL);
        assertEquals(ServerLoadState.NORMAL, state);
    }

    @Test
    void stableSamplesResetOppositeDirectionCounters() {
        LoadStateHysteresis policy = new LoadStateHysteresis(2, 2);
        ServerLoadState state = policy.update(ServerLoadState.NORMAL, ServerLoadState.PRESSURE);
        state = policy.update(state, ServerLoadState.NORMAL);
        state = policy.update(state, ServerLoadState.PRESSURE);
        assertEquals(ServerLoadState.NORMAL, state);
    }
}
