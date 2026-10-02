package cn.originalrpg.story;

/**
 * Pure location gate for spawning a private hub-tour actor.
 *
 * <p>The actor always begins at the first configured hub waypoint. Spawning it
 * while its player is in another world or too far away creates an invisible
 * session that can only time out. Remote requests therefore return through the
 * existing {@code /hub} command instead of creating any Citizens state.</p>
 */
final class StoryStartPolicy {
    static final double DEFAULT_START_DISTANCE = 24.0;
    static final double MINIMUM_START_DISTANCE = 8.0;
    static final double MAXIMUM_START_DISTANCE = 48.0;

    enum Action {
        SPAWN_ECHO,
        RETURN_TO_HUB
    }

    private StoryStartPolicy() {
    }

    static Action decide(boolean sameWorld, double distanceSquared, double configuredDistance) {
        if (!sameWorld || !Double.isFinite(distanceSquared) || distanceSquared < 0.0) {
            return Action.RETURN_TO_HUB;
        }
        double maximumDistance = sanitiseDistance(configuredDistance);
        return distanceSquared <= maximumDistance * maximumDistance
                ? Action.SPAWN_ECHO : Action.RETURN_TO_HUB;
    }

    static double sanitiseDistance(double configuredDistance) {
        double value = Double.isFinite(configuredDistance)
                ? configuredDistance : DEFAULT_START_DISTANCE;
        return Math.clamp(value, MINIMUM_START_DISTANCE, MAXIMUM_START_DISTANCE);
    }
}
