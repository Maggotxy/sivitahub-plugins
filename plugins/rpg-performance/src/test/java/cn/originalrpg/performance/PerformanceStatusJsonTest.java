package cn.originalrpg.performance;

import cn.originalrpg.api.model.PerformanceSnapshot;
import cn.originalrpg.api.model.PersistenceSnapshot;
import cn.originalrpg.api.model.ServerLoadState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerformanceStatusJsonTest {
    @Test
    void writesLedgerHealthAsAJsonBoolean() {
        PerformanceSnapshot snapshot = new PerformanceSnapshot(
                ServerLoadState.CRITICAL, 20.0, 8.0, 2, 100, 80, 1_000L, 2_000L,
                PersistenceSnapshot.unavailable(), false, Instant.parse("2026-08-12T00:00:00Z"));

        String json = PerformanceStatusJson.encode(snapshot);

        assertTrue(json.contains("\"ledgerAvailable\":false"));
        assertTrue(json.contains("\"jdbcTotal\":-1"));
        assertTrue(json.contains("\"sampledAt\":\"2026-08-12T00:00:00Z\""));
    }
}
