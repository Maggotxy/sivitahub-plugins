package cn.originalrpg.performance;

import cn.originalrpg.api.model.PersistenceSnapshot;
import cn.originalrpg.api.model.ServerLoadState;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Pure load classification policy. Tick health and persistence saturation are
 * both first-class signals: an asynchronous ledger queue can be exhausted
 * while TPS still appears healthy, so waiting for tick lag would shed work too
 * late.
 */
record LoadPolicy(
        double pressureMspt,
        double criticalMspt,
        double pressureTps,
        double criticalTps,
        int pressureJdbcQueue,
        int criticalJdbcQueue,
        int pressureJdbcWaiters,
        int criticalJdbcWaiters
) {
    static LoadPolicy defaults() {
        return sanitise(38.0, 48.0, 19.0, 17.5, 64, 192, 1, 2);
    }

    static LoadPolicy fromConfig(FileConfiguration config) {
        return sanitise(
                config.getDouble("sampling.pressure-mspt", 38.0),
                config.getDouble("sampling.critical-mspt", 48.0),
                config.getDouble("sampling.pressure-tps", 19.0),
                config.getDouble("sampling.critical-tps", 17.5),
                config.getInt("sampling.pressure-jdbc-queue-size", 64),
                config.getInt("sampling.critical-jdbc-queue-size", 192),
                config.getInt("sampling.pressure-jdbc-waiters", 1),
                config.getInt("sampling.critical-jdbc-waiters", 2));
    }

    static LoadPolicy sanitise(
            double pressureMspt,
            double criticalMspt,
            double pressureTps,
            double criticalTps,
            int pressureJdbcQueue,
            int criticalJdbcQueue,
            int pressureJdbcWaiters,
            int criticalJdbcWaiters
    ) {
        double safePressureMspt = Math.clamp(finiteOr(pressureMspt, 38.0), 1.0, 1_000.0);
        double safeCriticalMspt = Math.max(safePressureMspt,
                Math.clamp(finiteOr(criticalMspt, 48.0), 1.0, 1_000.0));
        double safePressureTps = Math.clamp(finiteOr(pressureTps, 19.0), 0.0, 20.0);
        double safeCriticalTps = Math.min(safePressureTps,
                Math.clamp(finiteOr(criticalTps, 17.5), 0.0, 20.0));
        int safePressureQueue = Math.clamp(pressureJdbcQueue, 1, 1_000_000);
        int safeCriticalQueue = Math.max(safePressureQueue,
                Math.clamp(criticalJdbcQueue, 1, 1_000_000));
        int safePressureWaiters = Math.clamp(pressureJdbcWaiters, 1, 100_000);
        int safeCriticalWaiters = Math.max(safePressureWaiters,
                Math.clamp(criticalJdbcWaiters, 1, 100_000));
        return new LoadPolicy(safePressureMspt, safeCriticalMspt, safePressureTps, safeCriticalTps,
                safePressureQueue, safeCriticalQueue, safePressureWaiters, safeCriticalWaiters);
    }

    ServerLoadState classify(double tps, double mspt, PersistenceSnapshot persistence) {
        double safeTps = finiteOr(tps, 0.0);
        double safeMspt = Math.max(0.0, finiteOr(mspt, criticalMspt));
        if (safeMspt >= criticalMspt || safeTps <= criticalTps || criticalPersistence(persistence)) {
            return ServerLoadState.CRITICAL;
        }
        if (safeMspt >= pressureMspt || safeTps <= pressureTps || pressuredPersistence(persistence)) {
            return ServerLoadState.PRESSURE;
        }
        return ServerLoadState.NORMAL;
    }

    private boolean criticalPersistence(PersistenceSnapshot value) {
        if (value == null || value.totalConnections() <= 0) return true;
        return atLeast(value.executorQueueSize(), criticalJdbcQueue)
                || atLeast(value.threadsAwaitingConnection(), criticalJdbcWaiters)
                || value.executorQueueRemainingCapacity() == 0;
    }

    private boolean pressuredPersistence(PersistenceSnapshot value) {
        if (value == null) return false;
        return atLeast(value.executorQueueSize(), pressureJdbcQueue)
                || atLeast(value.threadsAwaitingConnection(), pressureJdbcWaiters);
    }

    private static boolean atLeast(int value, int threshold) {
        return value >= 0 && value >= threshold;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
