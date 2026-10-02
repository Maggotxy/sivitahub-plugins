package cn.originalrpg.story;

final class EscortRules {
    enum FollowState {
        FOLLOWING,
        WAITING,
        FAIL
    }

    private EscortRules() {
    }

    static FollowState evaluate(
            double distance,
            double softDistance,
            double hardDistance,
            int hardDistanceTicks,
            int hardTimeoutTicks
    ) {
        if (!Double.isFinite(distance) || distance > hardDistance && hardDistanceTicks >= hardTimeoutTicks) {
            return FollowState.FAIL;
        }
        if (distance > softDistance) return FollowState.WAITING;
        return FollowState.FOLLOWING;
    }

    static float progress(int waypoint, int routeSize) {
        if (routeSize < 2) throw new IllegalArgumentException("routeSize must be at least 2");
        return Math.clamp((float) (waypoint - 1) / (routeSize - 1), 0.0F, 1.0F);
    }
}
