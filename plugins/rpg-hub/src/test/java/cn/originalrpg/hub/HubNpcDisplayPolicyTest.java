package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HubNpcDisplayPolicyTest {
    @Test
    void usesACompactSingleLineDisplayEnvelope() {
        assertEquals(4.0F, HubNpcDisplayPolicy.LABEL_DISPLAY_WIDTH);
        assertEquals(0.55F, HubNpcDisplayPolicy.SINGLE_LINE_DISPLAY_HEIGHT);
        assertTrue(HubNpcDisplayPolicy.SINGLE_LINE_DISPLAY_HEIGHT < 1.0F);
    }

    @Test
    void convertsHumanBlockDistanceToPaperMultiplier() {
        HubNpcDisplayPolicy policy = HubNpcDisplayPolicy.sanitise(2.72, 280, 0.72, 20.0, 100);

        assertEquals(0.3125F, policy.viewRangeMultiplier());
        assertTrue(policy.viewRangeMultiplier() < 1.0F,
                "a hub label must never out-track its mannequin");
    }

    @Test
    void clampsTheHistoricTwentyMultiplierMisconfiguration() {
        HubNpcDisplayPolicy policy = HubNpcDisplayPolicy.sanitise(
                Double.POSITIVE_INFINITY, 20_000, 8.0, 1_280.0, 1);

        assertEquals(2.72, policy.labelHeight());
        assertEquals(360, policy.lineWidthPixels());
        assertEquals(0.90F, policy.scale());
        assertEquals(0.375F, policy.viewRangeMultiplier());
        assertEquals(40, policy.reconcileTicks());
    }

    @Test
    void sanitisesNonFiniteAndNegativeValues() {
        HubNpcDisplayPolicy policy = HubNpcDisplayPolicy.sanitise(
                Double.NaN, -1, Double.NaN, Double.NaN, Integer.MAX_VALUE);

        assertEquals(2.72, policy.labelHeight());
        assertEquals(180, policy.lineWidthPixels());
        assertEquals(0.72F, policy.scale());
        assertEquals(0.3125F, policy.viewRangeMultiplier());
        assertEquals(400, policy.reconcileTicks());
    }
}
