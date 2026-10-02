package cn.originalrpg.api.model;

import java.time.Instant;

public record PerformanceSnapshot(
        ServerLoadState state,
        double tps,
        double averageMspt,
        int onlinePlayers,
        int loadedChunks,
        int entities,
        long usedHeapBytes,
        long maximumHeapBytes,
        PersistenceSnapshot persistence,
        boolean ledgerAvailable,
        Instant sampledAt
) {
}
