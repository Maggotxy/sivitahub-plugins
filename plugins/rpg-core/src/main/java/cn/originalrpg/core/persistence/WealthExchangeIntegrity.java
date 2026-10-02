package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CurrencyExchangeQuote;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Proves that every configured exchange is non-increasing under one strictly
 * positive valuation vector. Therefore no sequence or cycle of routes can
 * create net economic value, including routes that consume two currencies.
 */
final class WealthExchangeIntegrity {
    private WealthExchangeIntegrity() {}

    static Set<String> unsafeRoutes(Collection<CurrencyExchangeQuote> routes, Values values) {
        if (values == null || values.gold().signum() <= 0
                || values.merit().signum() <= 0 || values.starlight().signum() <= 0) {
            return routes.stream().map(CurrencyExchangeQuote::routeId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        Set<String> unsafe = new LinkedHashSet<>();
        for (CurrencyExchangeQuote route : routes) {
            BigDecimal cost = route.goldCost().multiply(values.gold())
                    .add(BigDecimal.valueOf(route.meritCost()).multiply(values.merit()))
                    .add(BigDecimal.valueOf(route.starlightCost()).multiply(values.starlight()));
            BigDecimal reward = route.goldReward().multiply(values.gold())
                    .add(BigDecimal.valueOf(route.meritReward()).multiply(values.merit()))
                    .add(BigDecimal.valueOf(route.starlightReward()).multiply(values.starlight()));
            if (cost.signum() <= 0 || reward.compareTo(cost) > 0) unsafe.add(route.routeId());
        }
        return Set.copyOf(unsafe);
    }

    record Values(BigDecimal gold, BigDecimal merit, BigDecimal starlight) {}
}
