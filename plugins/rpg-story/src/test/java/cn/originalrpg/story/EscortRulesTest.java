package cn.originalrpg.story;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EscortRulesTest {
    @Test
    void followsInsideSoftRadius() {
        assertEquals(EscortRules.FollowState.FOLLOWING, EscortRules.evaluate(8.0, 14.0, 30.0, 0, 240));
    }

    @Test
    void waitsBeforeFailingASeparatedPlayer() {
        assertEquals(EscortRules.FollowState.WAITING, EscortRules.evaluate(32.0, 14.0, 30.0, 200, 240));
    }

    @Test
    void failsOnlyAfterBoundedSeparation() {
        assertEquals(EscortRules.FollowState.FAIL, EscortRules.evaluate(32.0, 14.0, 30.0, 240, 240));
        assertEquals(EscortRules.FollowState.FAIL, EscortRules.evaluate(Double.NaN, 14.0, 30.0, 0, 240));
    }

    @Test
    void progressCountsCompletedSegmentsRatherThanTargetIndex() {
        assertEquals(0.0F, EscortRules.progress(1, 5));
        assertEquals(0.25F, EscortRules.progress(2, 5));
        assertEquals(0.75F, EscortRules.progress(4, 5));
        assertEquals(1.0F, EscortRules.progress(5, 5));
        assertThrows(IllegalArgumentException.class, () -> EscortRules.progress(1, 1));
    }
}
