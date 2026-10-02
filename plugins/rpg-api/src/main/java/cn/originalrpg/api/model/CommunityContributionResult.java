package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;

public record CommunityContributionResult(
        Status status,
        BigDecimal amount,
        BigDecimal balance,
        BigDecimal playerPeriodContribution,
        BigDecimal communityPeriodContribution,
        BigDecimal periodGoal,
        boolean newlyCompleted
) {
    public CommunityContributionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(playerPeriodContribution, "playerPeriodContribution");
        Objects.requireNonNull(communityPeriodContribution, "communityPeriodContribution");
        Objects.requireNonNull(periodGoal, "periodGoal");
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        INVALID,
        NOT_MEMBER,
        INSUFFICIENT_GOLD,
        PLAYER_LIMIT_REACHED,
        PROGRESS_LIMIT
    }
}
