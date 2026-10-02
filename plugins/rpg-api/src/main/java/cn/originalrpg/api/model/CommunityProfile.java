package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CommunityProfile(
        UUID playerId,
        String inviteCode,
        Optional<UUID> mentorId,
        boolean mentorVerified,
        int verifiedMentees,
        long communityHonor,
        int weeklyCommemorations,
        boolean weeklyRewardClaimed,
        Optional<CommunityMembership> membership
) {
    public CommunityProfile {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(inviteCode, "inviteCode");
        Objects.requireNonNull(mentorId, "mentorId");
        Objects.requireNonNull(membership, "membership");
        if (inviteCode.isBlank() || verifiedMentees < 0 || communityHonor < 0 || weeklyCommemorations < 0
                || mentorVerified && mentorId.isEmpty()) {
            throw new IllegalArgumentException("Invalid community profile");
        }
    }
}
