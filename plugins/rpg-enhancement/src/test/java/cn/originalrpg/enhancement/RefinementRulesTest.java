package cn.originalrpg.enhancement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefinementRulesTest {
    @Test
    void luckyBonusIsVisibleAndCannotBypassProbabilityBounds() {
        assertEquals(0.65, RefinementRules.successRate(0.65, 0.12, false));
        assertEquals(0.77, RefinementRules.successRate(0.65, 0.12, true));
        assertEquals(1.0, RefinementRules.successRate(0.95, 0.20, true));
        assertEquals(0.0, RefinementRules.successRate(-0.20, 0.12, false));
    }

    @Test
    void onlyRevealedSlotButtonsCanBeSelected() {
        assertEquals(0, RefinementRules.slotIndexForButton(10, 3));
        assertEquals(2, RefinementRules.slotIndexForButton(12, 3));
        assertEquals(-1, RefinementRules.slotIndexForButton(13, 3));
        assertEquals(-1, RefinementRules.slotIndexForButton(31, 8));
        assertTrue(RefinementRules.validSelection(7, 8));
        assertFalse(RefinementRules.validSelection(8, 8));
    }

    @Test
    void invalidConfiguredRollRangeFailsClosed() {
        RefinementRules.validateRollRange(-5, 18);
        assertThrows(IllegalArgumentException.class,
                () -> RefinementRules.validateRollRange(19, 18));
    }
}
