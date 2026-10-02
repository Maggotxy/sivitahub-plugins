package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HomeTravelTest {
    @Test
    void warmupMovementUsesThreeDimensionalTolerance() {
        assertTrue(TravelRules.withinTolerance(0.2, 0.1, 0.0, 0.35 * 0.35));
        assertFalse(TravelRules.withinTolerance(0.4, 0.0, 0.0, 0.35 * 0.35));
        assertFalse(TravelRules.withinTolerance(Double.NaN, 0.0, 0.0, 0.35 * 0.35));
    }
}
