package cn.originalrpg.story;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoryStartPolicyTest {
    @Test
    void remoteOrCrossWorldRequestNeverProducesASpawnDecision() {
        assertEquals(StoryStartPolicy.Action.RETURN_TO_HUB,
                StoryStartPolicy.decide(false, 0.0, 24.0));
        assertEquals(StoryStartPolicy.Action.RETURN_TO_HUB,
                StoryStartPolicy.decide(true, 24.01 * 24.01, 24.0));
        assertEquals(StoryStartPolicy.Action.RETURN_TO_HUB,
                StoryStartPolicy.decide(true, Double.NaN, 24.0));
    }

    @Test
    void playerNearTheFirstWaypointMaySpawnTheEcho() {
        assertEquals(StoryStartPolicy.Action.SPAWN_ECHO,
                StoryStartPolicy.decide(true, 24.0 * 24.0, 24.0));
    }

    @Test
    void hubArrivalCannotPretendToReachAnEchoAtTheFarthestConfiguredWaypoint() {
        // Arrival is (0.5, 25.5); the far forge waypoint is
        // (23.5, -22.5), over 53 blocks away and beyond the 30-block hard
        // follow radius. The old echo must be reclaimed before a hub return.
        double arrivalToFarthestSquared = 23.0 * 23.0 + 48.0 * 48.0;
        assertEquals(StoryStartPolicy.Action.RETURN_TO_HUB,
                StoryStartPolicy.decide(true, arrivalToFarthestSquared, 24.0));
    }

    @Test
    void malformedDistanceCannotDisableTheBound() {
        assertEquals(StoryStartPolicy.DEFAULT_START_DISTANCE,
                StoryStartPolicy.sanitiseDistance(Double.NaN));
        assertEquals(StoryStartPolicy.MINIMUM_START_DISTANCE,
                StoryStartPolicy.sanitiseDistance(-100.0));
        assertEquals(StoryStartPolicy.MAXIMUM_START_DISTANCE,
                StoryStartPolicy.sanitiseDistance(10_000.0));
    }
}
