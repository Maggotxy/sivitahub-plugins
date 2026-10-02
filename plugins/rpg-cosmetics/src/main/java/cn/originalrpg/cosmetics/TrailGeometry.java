package cn.originalrpg.cosmetics;

import java.util.ArrayList;
import java.util.List;

/** Pure flight-oriented geometry. Bukkit locations and particles stay in the main-thread renderer. */
final class TrailGeometry {
    private static final double MIN_DIRECTION_LENGTH = 1.0E-6;

    private TrailGeometry() { }

    static List<AuraGeometry.Offset> points(
            TrailStyle.Shape shape, int requested, double phase, double directionX, double directionY,
            double directionZ
    ) {
        int count = Math.clamp(requested, 0, 8);
        if (count == 0 || shape == TrailStyle.Shape.NONE) return List.of();
        double length = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (length < MIN_DIRECTION_LENGTH) return List.of();
        double forwardX = directionX / length;
        double forwardY = directionY / length;
        double forwardZ = directionZ / length;
        double horizontal = Math.hypot(forwardX, forwardZ);
        double rightX = horizontal < MIN_DIRECTION_LENGTH ? 1.0 : -forwardZ / horizontal;
        double rightZ = horizontal < MIN_DIRECTION_LENGTH ? 0.0 : forwardX / horizontal;

        ArrayList<AuraGeometry.Offset> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            points.add(point(shape, index, count, phase,
                    forwardX, forwardY, forwardZ, rightX, rightZ));
        }
        return List.copyOf(points);
    }

    private static AuraGeometry.Offset point(
            TrailStyle.Shape shape, int index, int count, double phase,
            double forwardX, double forwardY, double forwardZ, double rightX, double rightZ
    ) {
        return switch (shape) {
            case WAKE -> behind(forwardX, forwardY, forwardZ, 0.28 + index * 0.34,
                    0.0, rightX, rightZ, 0.08 * Math.sin(phase + index));
            case WINGS -> {
                int layer = index / 2;
                double side = index % 2 == 0 ? -1.0 : 1.0;
                double width = 0.48 + layer * 0.27;
                double distance = 0.18 + layer * 0.22;
                yield behind(forwardX, forwardY, forwardZ, distance,
                        side * width, rightX, rightZ, 0.08 + 0.05 * Math.sin(phase + layer));
            }
            case COMET -> {
                double fraction = count == 1 ? 0.0 : (double) index / (count - 1);
                double wobble = Math.sin(phase * 1.4 + index * 1.7) * 0.09 * fraction;
                yield behind(forwardX, forwardY, forwardZ, 0.25 + fraction * 1.35,
                        wobble, rightX, rightZ, 0.06 * Math.cos(phase + index));
            }
            case NONE -> new AuraGeometry.Offset(0.0, 0.0, 0.0);
        };
    }

    private static AuraGeometry.Offset behind(
            double forwardX, double forwardY, double forwardZ, double distance,
            double lateral, double rightX, double rightZ, double vertical
    ) {
        return new AuraGeometry.Offset(
                -forwardX * distance + rightX * lateral,
                -forwardY * distance + vertical,
                -forwardZ * distance + rightZ * lateral);
    }
}
