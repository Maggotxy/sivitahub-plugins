package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record CommunitySummary(
        UUID communityId,
        String name,
        String tag,
        int members,
        int capacity,
        BigDecimal periodContribution,
        BigDecimal periodGoal,
        int verifiedRecruits,
        boolean completed
) {
    public CommunitySummary {
        Objects.requireNonNull(communityId, "communityId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(periodContribution, "periodContribution");
        Objects.requireNonNull(periodGoal, "periodGoal");
        if (name.isBlank() || tag.isBlank() || members < 0 || capacity < 1 || members > capacity
                || periodContribution.signum() < 0 || periodGoal.signum() <= 0 || verifiedRecruits < 0) {
            throw new IllegalArgumentException("Invalid community summary");
        }
    }
}
