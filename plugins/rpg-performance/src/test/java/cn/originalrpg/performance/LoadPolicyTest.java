package cn.originalrpg.performance;

import cn.originalrpg.api.model.PersistenceSnapshot;
import cn.originalrpg.api.model.ServerLoadState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LoadPolicyTest {
    private final LoadPolicy policy = LoadPolicy.defaults();

    @Test
    void healthyTickAndLedgerRemainNormal() {
        assertEquals(ServerLoadState.NORMAL, policy.classify(20.0, 8.0, persistence(0, 256, 0)));
    }

    @Test
    void ledgerQueueCausesPressureBeforeTickLagAppears() {
        assertEquals(ServerLoadState.PRESSURE, policy.classify(20.0, 8.0, persistence(64, 192, 0)));
        assertEquals(ServerLoadState.CRITICAL, policy.classify(20.0, 8.0, persistence(192, 64, 0)));
    }

    @Test
    void exhaustedQueueOrConnectionWaitersAreCritical() {
        assertEquals(ServerLoadState.CRITICAL, policy.classify(20.0, 8.0, persistence(1, 0, 0)));
        assertEquals(ServerLoadState.CRITICAL, policy.classify(20.0, 8.0, persistence(0, 256, 2)));
    }

    @Test
    void unavailablePersistenceMetricsAreAlwaysCritical() {
        assertEquals(ServerLoadState.CRITICAL,
                policy.classify(20.0, 8.0, PersistenceSnapshot.unavailable()));
        assertEquals(ServerLoadState.CRITICAL,
                policy.classify(20.0, 8.0, new PersistenceSnapshot(0, 0, 0, 0, 0, 0, 256)));
    }

    @Test
    void invalidThresholdOrderingIsSanitisedConservatively() {
        LoadPolicy sanitised = LoadPolicy.sanitise(60.0, 20.0, 15.0, 19.5, 200, 10, 5, 1);
        assertEquals(ServerLoadState.NORMAL,
                sanitised.classify(16.0, 30.0, persistence(100, 156, 0)));
        assertEquals(ServerLoadState.CRITICAL,
                sanitised.classify(16.0, 60.0, persistence(0, 256, 0)));
    }

    private PersistenceSnapshot persistence(int queued, int remaining, int waiters) {
        return new PersistenceSnapshot(0, 6, 6, waiters, 0, queued, remaining);
    }
}
