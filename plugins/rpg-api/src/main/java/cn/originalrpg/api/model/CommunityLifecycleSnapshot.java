package cn.originalrpg.api.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model of the existing community row and its recent lifecycle decisions. */
public record CommunityLifecycleSnapshot(
        UUID communityId, String name, String tag, UUID ownerId, Instant ownerLastAuthenticated,
        List<CommunityLifecycleProposal> proposals
) {
    public CommunityLifecycleSnapshot {
        proposals = List.copyOf(proposals);
    }
}
