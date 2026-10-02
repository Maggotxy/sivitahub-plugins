package cn.originalrpg.mobs;

import java.util.Set;

/** Pure admission and retry rules for event-driven adaptive-mob assignment. */
public final class AdaptiveAssignmentPolicy {
    private static final Set<String> CANDIDATE_SPAWNS = Set.of(
            "NATURAL", "REINFORCEMENTS", "PATROL", "RAID", "VILLAGE_INVASION");

    private AdaptiveAssignmentPolicy() {
    }

    public static boolean isCandidateSpawn(String spawnReason) {
        return spawnReason != null && CANDIDATE_SPAWNS.contains(spawnReason);
    }

    public static boolean shouldAttempt(
            boolean alreadyAssigned, boolean candidate, Long retryAfterMillis, long nowMillis
    ) {
        return !alreadyAssigned && candidate
                && (retryAfterMillis == null || retryAfterMillis <= nowMillis);
    }
}
