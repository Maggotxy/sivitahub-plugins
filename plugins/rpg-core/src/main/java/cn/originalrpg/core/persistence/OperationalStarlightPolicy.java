package cn.originalrpg.core.persistence;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Hard persistence-layer bounds for manually issued Starlight. These are
 * deliberately separate from gameplay exchange quotas: an operator cannot
 * bypass them by using another Paper node or another administrator account.
 */
public record OperationalStarlightPolicy(
        long maximumPerGrant,
        long dailyLimit,
        long weeklyLimit,
        ZoneId zoneId
) {
    public OperationalStarlightPolicy {
        Objects.requireNonNull(zoneId, "zoneId");
        if (maximumPerGrant < 1 || dailyLimit < maximumPerGrant || weeklyLimit < dailyLimit) {
            throw new IllegalArgumentException(
                    "Starlight policy requires 1 <= maximumPerGrant <= dailyLimit <= weeklyLimit");
        }
    }

    static OperationalStarlightPolicy testDefault() {
        return new OperationalStarlightPolicy(1_000_000_000L, 1_000_000_000L,
                1_000_000_000L, ZoneId.of("UTC"));
    }
}
