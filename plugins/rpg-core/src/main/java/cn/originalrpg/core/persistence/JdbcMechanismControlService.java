package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.MechanismSwitch;
import cn.originalrpg.api.service.MechanismControlService;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Async adapter over the existing registry; it owns no second switch map or table. */
public final class JdbcMechanismControlService implements MechanismControlService {
    private final MechanismRegistry registry;
    private final Executor executor;

    public JdbcMechanismControlService(MechanismRegistry registry, Executor executor) {
        this.registry = Objects.requireNonNull(registry);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public CompletionStage<List<MechanismSwitch>> switches() {
        return AsyncJdbc.supply(executor, () -> {
            registry.refresh();
            return registry.metrics().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .map(entry -> view(entry.getKey(), entry.getValue())).toList();
        });
    }

    @Override
    public CompletionStage<MechanismSwitch> setEnabled(String mechanismId, boolean enabled, String auditLabel) {
        if (mechanismId == null || !MechanismRegistry.DEFAULTS.containsKey(mechanismId)
                || auditLabel == null || auditLabel.isBlank() || auditLabel.length() > 64
                || auditLabel.codePoints().anyMatch(Character::isISOControl)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown mechanism or invalid operator audit label"));
        }
        return AsyncJdbc.supply(executor, () -> {
            registry.setEnabled(mechanismId, enabled, auditLabel);
            return view(mechanismId, registry.metrics().get(mechanismId));
        });
    }

    private static MechanismSwitch view(String id, MechanismRegistry.MechanismMetric metric) {
        return new MechanismSwitch(id, metric.enabled(), metric.allowed(), metric.denied());
    }
}
