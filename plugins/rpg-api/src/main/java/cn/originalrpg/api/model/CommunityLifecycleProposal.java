package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable evidence and current eligible signatures for a durable lifecycle decision. */
public record CommunityLifecycleProposal(
        UUID id,
        UUID communityId,
        CommunityLifecycleAction action,
        UUID proposerId,
        UUID incumbentOwnerId,
        Optional<UUID> candidateId,
        State state,
        Instant createdAt,
        Optional<Instant> executeAfter,
        Optional<Instant> finishedAt,
        List<CommunityApprovalOutcome.GrantedApproval> approvals
) {
    public CommunityLifecycleProposal {
        candidateId = candidateId == null ? Optional.empty() : candidateId;
        executeAfter = executeAfter == null ? Optional.empty() : executeAfter;
        finishedAt = finishedAt == null ? Optional.empty() : finishedAt;
        approvals = List.copyOf(approvals);
    }

    public enum State {
        PENDING, COOLING, EXECUTED, CANCELLED;

        public boolean terminal() {
            return this == EXECUTED || this == CANCELLED;
        }
    }
}
