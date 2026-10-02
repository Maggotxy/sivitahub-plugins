package cn.originalrpg.performance;

import cn.originalrpg.api.model.ServerLoadState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AuthoritativeLoadStateTest {
    @Test
    void firstLedgerBackedSampleEstablishesTheBaselineImmediately() {
        AuthoritativeLoadState state = new AuthoritativeLoadState(
                new LoadStateHysteresis(3, 12));

        assertEquals(ServerLoadState.NORMAL,
                state.update(ServerLoadState.CRITICAL, ServerLoadState.NORMAL, true));
    }

    @Test
    void unavailableLedgerAlwaysFailsClosedWithoutConsumingTheInitialBaseline() {
        AuthoritativeLoadState state = new AuthoritativeLoadState(
                new LoadStateHysteresis(3, 12));

        assertEquals(ServerLoadState.CRITICAL,
                state.update(ServerLoadState.CRITICAL, ServerLoadState.NORMAL, false));
        assertEquals(ServerLoadState.NORMAL,
                state.update(ServerLoadState.CRITICAL, ServerLoadState.NORMAL, true));
    }

    @Test
    void samplesAfterTheBaselineStillUseHysteresis() {
        AuthoritativeLoadState state = new AuthoritativeLoadState(
                new LoadStateHysteresis(3, 4));
        ServerLoadState current = state.update(
                ServerLoadState.CRITICAL, ServerLoadState.NORMAL, true);

        current = state.update(current, ServerLoadState.CRITICAL, true);
        assertEquals(ServerLoadState.NORMAL, current);
        current = state.update(current, ServerLoadState.CRITICAL, true);
        assertEquals(ServerLoadState.NORMAL, current);
        current = state.update(current, ServerLoadState.CRITICAL, true);
        assertEquals(ServerLoadState.CRITICAL, current);
    }
}
