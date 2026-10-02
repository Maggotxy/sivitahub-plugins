package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HubNpcFacingPolicyTest {
    @Test
    void suppressesSubPixelIdleAndTrackingJitter() {
        assertFalse(HubNpcFacingPolicy.shouldRotate(45.0F, 0.0F, 45.0F, 0.0F));
        assertFalse(HubNpcFacingPolicy.shouldRotate(45.0F, 0.0F, 46.9F, -1.9F));
        assertTrue(HubNpcFacingPolicy.shouldRotate(45.0F, 0.0F, 47.0F, 0.0F));
        assertTrue(HubNpcFacingPolicy.shouldRotate(45.0F, 0.0F, 45.0F, -2.0F));
    }

    @Test
    void yawComparisonUsesTheShortestPathAcrossTheWrapBoundary() {
        assertEquals(2.0F, HubNpcFacingPolicy.angularDistance(179.0F, -179.0F));
        assertEquals(1.0F, HubNpcFacingPolicy.angularDistance(359.5F, 0.5F));
        assertTrue(HubNpcFacingPolicy.shouldRotate(179.0F, 0.0F, -179.0F, 0.0F));
        assertFalse(HubNpcFacingPolicy.shouldRotate(359.5F, 0.0F, 0.5F, 0.0F));
    }

    @Test
    void refusesInvalidTargetsButRepairsInvalidStoredRotation() {
        assertFalse(HubNpcFacingPolicy.shouldRotate(0.0F, 0.0F, Float.NaN, 0.0F));
        assertFalse(HubNpcFacingPolicy.shouldRotate(0.0F, 0.0F, 0.0F, Float.POSITIVE_INFINITY));
        assertTrue(HubNpcFacingPolicy.shouldRotate(Float.NaN, 0.0F, 0.0F, 0.0F));
    }
}
