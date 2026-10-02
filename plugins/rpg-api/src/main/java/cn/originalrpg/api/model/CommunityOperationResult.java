package cn.originalrpg.api.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record CommunityOperationResult(
        Status status,
        Optional<CommunitySummary> community,
        BigDecimal balance
) {
    public CommunityOperationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(community, "community");
        Objects.requireNonNull(balance, "balance");
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        INVALID,
        LEVEL_REQUIRED,
        INSUFFICIENT_GOLD,
        NAME_TAKEN,
        TAG_TAKEN,
        ALREADY_MEMBER,
        NOT_MEMBER,
        NOT_ALLOWED,
        COMMUNITY_FULL,
        INVITE_MISSING,
        RATE_LIMITED,
        OWNER_MUST_TRANSFER,
        TARGET_NOT_MEMBER
    }
}
