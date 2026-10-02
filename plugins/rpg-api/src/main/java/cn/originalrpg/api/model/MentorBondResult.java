package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MentorBondResult(
        Status status,
        Optional<UUID> mentorId,
        Optional<UUID> creditedCommunityId,
        boolean verified,
        int mentorVerifiedMentees
) {
    public MentorBondResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(mentorId, "mentorId");
        Objects.requireNonNull(creditedCommunityId, "creditedCommunityId");
        if (mentorVerifiedMentees < 0 || verified && mentorId.isEmpty()) {
            throw new IllegalArgumentException("Invalid mentor bond result");
        }
    }

    public enum Status {
        SUCCESS,
        ALREADY_BOUND,
        ALREADY_VERIFIED,
        INVALID,
        CODE_NOT_FOUND,
        SELF_REFERENCE,
        MENTOR_NOT_READY,
        BIND_WINDOW_CLOSED,
        NOT_BOUND,
        NOT_READY,
        PROGRESS_LIMIT
    }
}
