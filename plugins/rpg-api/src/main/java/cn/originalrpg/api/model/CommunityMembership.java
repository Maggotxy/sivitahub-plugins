package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

public record CommunityMembership(
        CommunitySummary community,
        CommunityRole role,
        BigDecimal lifetimeContribution,
        BigDecimal periodContribution,
        BigDecimal periodCapContribution
) {
    public CommunityMembership {
        Objects.requireNonNull(community, "community");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(lifetimeContribution, "lifetimeContribution");
        Objects.requireNonNull(periodContribution, "periodContribution");
        Objects.requireNonNull(periodCapContribution, "periodCapContribution");
        if (lifetimeContribution.signum() < 0 || periodContribution.signum() < 0
                || periodCapContribution.signum() < 0
                || periodContribution.compareTo(periodCapContribution) > 0) {
            throw new IllegalArgumentException("Community contributions cannot be negative");
        }
    }
}
