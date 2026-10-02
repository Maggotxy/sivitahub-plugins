package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WildernessTravelRulesTest {
    @Test
    void acceptsTheReviewedPregeneratedFrontierNeighborhood() {
        // Overworld border: center 7,0; diameter 3600. The farthest configured
        // RTP candidate still leaves room for its complete 3x3 neighborhood.
        assertTrue(WildernessTravelRules.neighborhoodInsideBorder(
                100, 0, 1, 7.0, 0.0, 3_600.0));
        assertTrue(WildernessTravelRules.neighborhoodInsideBorder(
                -100, 0, 1, 7.0, 0.0, 3_600.0));
    }

    @Test
    void rejectsAFeetPositionWhoseNeighborChunkCrossesTheBorder() {
        assertFalse(WildernessTravelRules.neighborhoodInsideBorder(
                111, 0, 1, 7.0, 0.0, 3_600.0));
        assertFalse(WildernessTravelRules.neighborhoodInsideBorder(
                0, -112, 1, 7.0, 0.0, 3_600.0));
    }

    @Test
    void rejectsInvalidBorderInputsAndRadius() {
        assertFalse(WildernessTravelRules.neighborhoodInsideBorder(
                0, 0, -1, 0.0, 0.0, 3_600.0));
        assertFalse(WildernessTravelRules.neighborhoodInsideBorder(
                0, 0, 1, Double.NaN, 0.0, 3_600.0));
        assertFalse(WildernessTravelRules.neighborhoodInsideBorder(
                0, 0, 1, 0.0, 0.0, 0.0));
    }
}
