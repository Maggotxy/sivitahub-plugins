package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.UUID;

/** An append-only, non-economic transition in the public community governance history. */
public record CommunityLifecycleAudit(
        UUID id, UUID communityId, UUID proposalId, UUID actorId, String transition,
        String detail, Instant recordedAt
) {
}
