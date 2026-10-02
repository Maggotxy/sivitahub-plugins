package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdaptiveAssignmentPolicyTest {
    @Test
    void onlyProvenNaturalWorldSourcesBecomeRecoveryCandidates() {
        for (String reason : new String[]{
                "NATURAL", "REINFORCEMENTS", "PATROL", "RAID", "VILLAGE_INVASION"}) {
            assertTrue(AdaptiveAssignmentPolicy.isCandidateSpawn(reason), reason);
        }
        for (String reason : new String[]{
                "SPAWNER", "TRIAL_SPAWNER", "CUSTOM", "COMMAND", "DEFAULT", "EGG"}) {
            assertFalse(AdaptiveAssignmentPolicy.isCandidateSpawn(reason), reason);
        }
        assertFalse(AdaptiveAssignmentPolicy.isCandidateSpawn(null));
    }

    @Test
    void assignedOrUnprovenMobsNeverRetry() {
        assertFalse(AdaptiveAssignmentPolicy.shouldAttempt(true, true, null, 2_000L));
        assertFalse(AdaptiveAssignmentPolicy.shouldAttempt(false, false, null, 2_000L));
    }

    @Test
    void candidateRetriesOnlyAfterItsCooldown() {
        assertTrue(AdaptiveAssignmentPolicy.shouldAttempt(false, true, null, 2_000L));
        assertFalse(AdaptiveAssignmentPolicy.shouldAttempt(false, true, 2_001L, 2_000L));
        assertTrue(AdaptiveAssignmentPolicy.shouldAttempt(false, true, 2_000L, 2_000L));
        assertTrue(AdaptiveAssignmentPolicy.shouldAttempt(false, true, 1_999L, 2_000L));
    }
}
