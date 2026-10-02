package cn.originalrpg.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrailGeometryTest {
    @Test
    void everyShapeHonorsItsBoundedPointBudget() {
        for (TrailStyle.Shape shape : TrailStyle.Shape.values()) {
            int expected = shape == TrailStyle.Shape.NONE ? 0 : 8;
            assertEquals(expected, TrailGeometry.points(shape, 100, 0.5, 1.0, 0.0, 0.0).size());
        }
        assertTrue(TrailGeometry.points(TrailStyle.Shape.COMET, 4, 0.0, 0.0, 0.0, 0.0).isEmpty());
    }

    @Test
    void wingPairsStaySeparatedAndBehindFlightDirection() {
        List<AuraGeometry.Offset> points = TrailGeometry.points(
                TrailStyle.Shape.WINGS, 4, 0.0, 0.0, 0.0, 1.0);
        assertEquals(4, points.size());
        assertTrue(Math.abs(points.get(0).x() - points.get(1).x()) > 0.9);
        assertTrue(points.stream().allMatch(point -> point.z() < 0.0));
    }

    @Test
    void styleIdsAndVoucherMappingAreStable() {
        assertEquals(TrailStyle.WINGS, TrailStyle.parse("WiNgS").orElseThrow());
        assertEquals(TrailStyle.COMET, TrailStyle.forVoucher("elytra_comet").orElseThrow());
        assertTrue(TrailStyle.forVoucher("admin-rainbow").isEmpty());
    }
}
