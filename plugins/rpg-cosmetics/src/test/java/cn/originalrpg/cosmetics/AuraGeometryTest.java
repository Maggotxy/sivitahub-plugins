package cn.originalrpg.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuraGeometryTest {
    @Test
    void everyShapeHonorsTheBoundedPointBudget() {
        for (AuraType.Shape shape : AuraType.Shape.values()) {
            int expected = shape == AuraType.Shape.NONE ? 0 : 12;
            assertEquals(expected, AuraGeometry.points(shape, 1000, 0.75).size());
            assertEquals(0, AuraGeometry.points(shape, 0, 0.75).size());
        }
    }

    @Test
    void doubleHelixProducesTwoSeparatedStrandsInsideAuraBounds() {
        List<AuraGeometry.Offset> points = AuraGeometry.points(AuraType.Shape.DOUBLE_HELIX, 6, 0.0);
        assertEquals(6, points.size());
        assertTrue(distance(points.get(0), points.get(1)) > 0.9);
        assertTrue(points.stream().allMatch(point -> point.y() >= 0.0 && point.y() <= 1.8));
        assertTrue(points.stream().allMatch(point -> Math.hypot(point.x(), point.z()) <= 0.57));
    }

    @Test
    void auraIdsAreStableAndUnknownValuesAreRejected() {
        assertEquals(AuraType.PRISMATIC, AuraType.parse("PrIsMaTiC").orElseThrow());
        assertTrue(AuraType.parse("rainbow-admin-bypass").isEmpty());
    }

    private double distance(AuraGeometry.Offset first, AuraGeometry.Offset second) {
        return Math.sqrt(Math.pow(first.x() - second.x(), 2)
                + Math.pow(first.y() - second.y(), 2)
                + Math.pow(first.z() - second.z(), 2));
    }
}
