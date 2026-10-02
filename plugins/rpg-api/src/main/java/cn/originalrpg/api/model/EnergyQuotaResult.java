package cn.originalrpg.api.model;

import java.util.Objects;

/** Result of a persistent, period-bounded Energy recovery source. */
public record EnergyQuotaResult(
        Status status,
        PlayerProfile profile,
        int energyGranted,
        int uses,
        int grantedThisPeriod,
        int periodLimit,
        int nextGrant
) {
    public EnergyQuotaResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(profile, "profile");
        if (energyGranted < 0 || uses < 0 || grantedThisPeriod < 0
                || periodLimit < 1 || nextGrant < 0 || grantedThisPeriod > periodLimit) {
            throw new IllegalArgumentException("Energy quota values are invalid");
        }
    }

    public enum Status {
        SUCCESS,
        ENERGY_FULL,
        PERIOD_LIMIT
    }
}
