package cn.originalrpg.performance;

import cn.originalrpg.api.model.PersistenceSnapshot;
import cn.originalrpg.api.service.PersistenceMetricsService;

/** One dynamically resolved view of the authoritative persistence service. */
record PersistenceHealth(PersistenceSnapshot snapshot, boolean ledgerAvailable) {
    static PersistenceHealth unavailable() {
        return new PersistenceHealth(PersistenceSnapshot.unavailable(), false);
    }

    static PersistenceHealth capture(boolean providerPluginEnabled, PersistenceMetricsService service) {
        if (!providerPluginEnabled || service == null) return unavailable();
        try {
            PersistenceSnapshot value = service.snapshot();
            if (value == null || value.totalConnections() <= 0) return unavailable();
            return new PersistenceHealth(value, true);
        } catch (RuntimeException exception) {
            return unavailable();
        }
    }
}
