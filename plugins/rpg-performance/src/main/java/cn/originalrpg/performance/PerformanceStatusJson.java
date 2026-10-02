package cn.originalrpg.performance;

import cn.originalrpg.api.model.PerformanceSnapshot;

import java.util.Locale;

final class PerformanceStatusJson {
    private PerformanceStatusJson() {
    }

    static String encode(PerformanceSnapshot value) {
        return String.format(Locale.ROOT,
                "{\"state\":\"%s\",\"tps\":%.3f,\"mspt\":%.3f,\"players\":%d,"
                        + "\"chunks\":%d,\"entities\":%d,\"usedHeapBytes\":%d,\"maximumHeapBytes\":%d,"
                        + "\"ledgerAvailable\":%b,\"jdbcActive\":%d,\"jdbcTotal\":%d,"
                        + "\"jdbcWaiting\":%d,\"jdbcQueue\":%d,\"sampledAt\":\"%s\"}%n",
                value.state(), value.tps(), value.averageMspt(), value.onlinePlayers(), value.loadedChunks(),
                value.entities(), value.usedHeapBytes(), value.maximumHeapBytes(), value.ledgerAvailable(),
                value.persistence().activeConnections(), value.persistence().totalConnections(),
                value.persistence().threadsAwaitingConnection(), value.persistence().executorQueueSize(),
                value.sampledAt());
    }
}
