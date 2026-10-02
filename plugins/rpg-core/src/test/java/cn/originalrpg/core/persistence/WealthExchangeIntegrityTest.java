package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CurrencyExchangeQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WealthExchangeIntegrityTest {
    private static final WealthExchangeIntegrity.Values VALUES = new WealthExchangeIntegrity.Values(
            new BigDecimal("1.00"), new BigDecimal("600.00"), new BigDecimal("1400.00"));

    @Test
    void configuredRoutesCannotIncreaseReferenceValueInAnyCycle() {
        List<CurrencyExchangeQuote> routes = List.of(
                route("gold-to-merit", "600", 0, 0, "0", 1, 0),
                route("merit-to-gold", "0", 1, 0, "300", 0, 0),
                route("gold-merit-to-starlight", "200", 2, 0, "0", 0, 1),
                route("starlight-to-gold", "0", 0, 1, "250", 0, 0),
                route("starlight-to-merit", "0", 0, 4, "0", 1, 0)
        );
        assertEquals(Set.of(), WealthExchangeIntegrity.unsafeRoutes(routes, VALUES));
    }

    @Test
    void valueIncreasingRouteIsRejectedEvenWhenItsReverseLooksLossy() {
        List<CurrencyExchangeQuote> routes = List.of(
                route("unsafe-star", "100", 0, 0, "0", 0, 1),
                route("star-back", "0", 0, 1, "250", 0, 0)
        );
        assertEquals(Set.of("unsafe-star"), WealthExchangeIntegrity.unsafeRoutes(routes, VALUES));
    }

    private static CurrencyExchangeQuote route(
            String id, String goldCost, long meritCost, long starlightCost,
            String goldReward, long meritReward, long starlightReward
    ) {
        return new CurrencyExchangeQuote(id, new BigDecimal(goldCost), meritCost, starlightCost,
                new BigDecimal(goldReward), meritReward, starlightReward,
                10, 50, 10, 50);
    }
}
