package cn.originalrpg.hub;

import java.util.UUID;

/** Pure geometry and ownership rules for client-side hub navigation state. */
final class HubWayfindingPolicy {
    private static final double FALLBACK_INTERACTION_RANGE = 3.0;
    private static final double INTERACTION_MARGIN = 0.35;
    private static final double MAXIMUM_ARRIVAL_DISTANCE = 2.5;
    private static final double MINIMUM_ARRIVAL_DISTANCE = 0.25;
    private static final double TARGET_EPSILON = 0.000_001;

    private HubWayfindingPolicy() {
    }

    static double arrivalDistance(double configuredDistance, double entityInteractionRange) {
        double reach = Double.isFinite(entityInteractionRange) && entityInteractionRange > 0.0
                ? entityInteractionRange : FALLBACK_INTERACTION_RANGE;
        double maximum = Math.max(MINIMUM_ARRIVAL_DISTANCE,
                Math.min(MAXIMUM_ARRIVAL_DISTANCE, reach - INTERACTION_MARGIN));
        double requested = Double.isFinite(configuredDistance) && configuredDistance > 0.0
                ? configuredDistance : MAXIMUM_ARRIVAL_DISTANCE;
        return Math.clamp(requested, MINIMUM_ARRIVAL_DISTANCE, maximum);
    }

    static boolean ownsCompassTarget(
            UUID currentWorld, double currentX, double currentZ,
            UUID navigationWorld, double navigationX, double navigationZ
    ) {
        if (currentWorld == null || navigationWorld == null || !currentWorld.equals(navigationWorld)) return false;
        if (!Double.isFinite(currentX) || !Double.isFinite(currentZ)
                || !Double.isFinite(navigationX) || !Double.isFinite(navigationZ)) return false;
        return Math.abs(currentX - navigationX) <= TARGET_EPSILON
                && Math.abs(currentZ - navigationZ) <= TARGET_EPSILON;
    }
}
