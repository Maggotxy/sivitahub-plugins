package cn.originalrpg.api.model;

import java.util.Optional;

/** A committed lifecycle result. A successful approval alone never executes a decision. */
public record CommunityLifecycleResult(Status status, Optional<CommunityLifecycleProposal> proposal) {
    public CommunityLifecycleResult {
        proposal = proposal == null ? Optional.empty() : proposal;
    }

    public static CommunityLifecycleResult of(Status status) {
        return new CommunityLifecycleResult(status, Optional.empty());
    }

    public enum Status {
        SUCCESS, DISABLED, INVALID, NOT_MEMBER, NOT_ALLOWED, OWNER_ACTIVE,
        MEMBERSHIP_TOO_RECENT, ACTIVE_PROPOSAL, NOT_FOUND, ALREADY_APPROVED,
        APPROVALS_REQUIRED, COOLING, EXECUTED, CANCELLED, CONDITIONS_CHANGED, STORAGE_PENDING
    }
}
