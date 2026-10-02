package cn.originalrpg.cosmetics;

import java.util.ArrayList;
import java.util.List;

/** Small immutable point sets; packet delivery and Bukkit access stay outside this pure geometry layer. */
final class AuraGeometry {
    record Offset(double x, double y, double z) { }

    private AuraGeometry() { }

    static List<Offset> points(AuraType.Shape shape, int requested, double phase) {
        int count = Math.clamp(requested, 0, 12);
        if (count == 0 || shape == AuraType.Shape.NONE) return List.of();
        ArrayList<Offset> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double fraction = count == 1 ? 0.5 : (double) index / count;
            points.add(point(shape, index, count, fraction, phase));
        }
        return List.copyOf(points);
    }

    private static Offset point(
            AuraType.Shape shape, int index, int count, double fraction, double phase
    ) {
        return switch (shape) {
            case HELIX -> {
                double angle = phase + Math.PI * 4.0 * fraction;
                yield polar(angle, 0.48, 0.15 + fraction * 1.65);
            }
            case PULSE -> {
                double angle = phase + Math.PI * 2.0 * fraction;
                double radius = 0.62 + 0.12 * Math.sin(phase * 1.4);
                yield polar(angle, radius, 0.12 + 0.04 * Math.sin(angle * 2.0));
            }
            case ORBIT -> {
                double angle = phase + Math.PI * 2.0 * fraction;
                yield polar(angle, 0.68, 0.78 + 0.25 * Math.sin(angle * 2.0));
            }
            case WAVE -> {
                double angle = phase + Math.PI * 2.0 * fraction;
                yield polar(angle, 0.72, 0.22 + 0.16 * Math.sin(angle * 2.0 + phase));
            }
            case DOUBLE_HELIX -> {
                int strands = 2;
                int layer = index / strands;
                int layers = Math.max(1, (count + strands - 1) / strands);
                double height = layers == 1 ? 0.85 : 0.18 + 1.55 * layer / Math.max(1, layers - 1);
                double angle = phase + Math.PI * (index % strands) + Math.PI * 2.0 * layer / layers;
                yield polar(angle, 0.56, height);
            }
            case NONE -> new Offset(0.0, 0.0, 0.0);
        };
    }

    private static Offset polar(double angle, double radius, double y) {
        return new Offset(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }
}
