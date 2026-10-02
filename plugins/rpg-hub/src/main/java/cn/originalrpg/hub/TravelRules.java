package cn.originalrpg.hub;

final class TravelRules {
    private TravelRules() {}

    static boolean withinTolerance(double deltaX, double deltaY, double deltaZ, double toleranceSquared) {
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)
                || !Double.isFinite(toleranceSquared) || toleranceSquared < 0.0) return false;
        return square(deltaX) + square(deltaY) + square(deltaZ) <= toleranceSquared;
    }

    private static double square(double value) {
        return value * value;
    }
}
