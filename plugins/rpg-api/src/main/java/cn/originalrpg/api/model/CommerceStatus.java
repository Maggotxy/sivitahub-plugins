package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CommerceStatus(
        UUID playerId,
        BigDecimal lifetimePaid,
        long credits,
        Instant memberUntil,
        Set<String> claimedRewards
) {
    public CommerceStatus {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(lifetimePaid, "lifetimePaid");
        Objects.requireNonNull(claimedRewards, "claimedRewards");
        if (credits < 0) throw new IllegalArgumentException("Credits cannot be negative");
        claimedRewards = Set.copyOf(claimedRewards);
    }

    public boolean memberActive(Instant now) {
        return memberUntil != null && memberUntil.isAfter(now);
    }
}
