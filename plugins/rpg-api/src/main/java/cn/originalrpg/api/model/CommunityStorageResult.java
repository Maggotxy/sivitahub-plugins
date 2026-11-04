package cn.originalrpg.api.model;

import java.util.Optional;

/** A committed warehouse approval result; DELIVERED means queued in the existing market mailbox. */
public record CommunityStorageResult(Status status, Optional<CommunityStorageRequest> request) {
    public CommunityStorageResult {
        request = request == null ? Optional.empty() : request;
    }
    public static CommunityStorageResult of(Status status) {
        return new CommunityStorageResult(status, Optional.empty());
    }
    public enum Status {
        SUCCESS, DISABLED, INVALID, NOT_MEMBER, NOT_ALLOWED, NOT_FOUND, TARGET_NOT_MEMBER,
        ACTIVE_REQUEST, ALREADY_APPROVED, DELIVERED, CANCELLED, CONDITIONS_CHANGED
    }
}
