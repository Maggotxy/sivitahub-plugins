package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HubGeometryTest {
    @Test
    void circularProtectionUsesInclusiveBoundaryWithoutOverflow() {
        assertTrue(HubGeometry.inside(100, -50, 60, 160, -50));
        assertTrue(HubGeometry.inside(100, -50, 60, 100, 10));
        assertFalse(HubGeometry.inside(100, -50, 60, 161, -50));
        assertFalse(HubGeometry.inside(Integer.MAX_VALUE, Integer.MIN_VALUE, 60,
                Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void ringAndGatesRemainDeterministic() {
        assertTrue(HubGeometry.onRing(30, 34, 32, 0));
        assertFalse(HubGeometry.onRing(30, 34, 20, 0));
        assertTrue(HubGeometry.gateOpening(52, 0, 52));
        assertTrue(HubGeometry.gateOpening(52, -52, 3));
        assertFalse(HubGeometry.gateOpening(52, 30, 30));
    }
}
