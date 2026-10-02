package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Durable result of claiming one completed community project period.
 *
 * <p>Community honor and commemorations are account-bound social progress.
 * They are deliberately separate from every currency and combat progression
 * service.</p>
 */
public record CommunityProjectRewardResult(
        Status status,
        String periodKey,
        int honorAwarded,
        long totalHonor,
        int weeklyCommemorations,
        BigDecimal playerPeriodContribution,
        BigDecimal minimumContribution
) {
    public CommunityProjectRewardResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(periodKey, "periodKey");
        Objects.requireNonNull(playerPeriodContribution, "playerPeriodContribution");
        Objects.requireNonNull(minimumContribution, "minimumContribution");
        if (periodKey.isBlank() || honorAwarded < 0 || totalHonor < 0 || weeklyCommemorations < 0
                || playerPeriodContribution.signum() < 0 || minimumContribution.signum() < 0) {
            throw new IllegalArgumentException("Invalid community project reward result");
        }
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        INVALID,
        NOT_MEMBER,
        PROJECT_INCOMPLETE,
        CONTRIBUTION_REQUIRED,
        PROGRESS_LIMIT
    }
}
