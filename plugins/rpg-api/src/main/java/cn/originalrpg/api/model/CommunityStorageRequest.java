package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable consent to deliver one existing escrowed stack to one named community member. */
public record CommunityStorageRequest(
        UUID id, UUID communityId, UUID listingId, UUID proposerId, UUID recipientId,
        State state, Instant createdAt, Optional<Instant> completedAt,
        List<CommunityApprovalOutcome.GrantedApproval> approvals
) {
    public CommunityStorageRequest {
        completedAt = completedAt == null ? Optional.empty() : completedAt;
        approvals = List.copyOf(approvals);
    }

    public enum State { PENDING, COMPLETED, CANCELLED }
}
