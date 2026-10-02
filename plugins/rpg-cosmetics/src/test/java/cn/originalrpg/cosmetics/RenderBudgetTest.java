package cn.originalrpg.cosmetics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RenderBudgetTest {
    @Test
    void budgetNeverAllowsMorePacketsThanTheConfiguredPassLimit() {
        RenderBudget budget = new RenderBudget(3);
        assertTrue(budget.take());
        assertTrue(budget.take());
        assertTrue(budget.take());
        assertFalse(budget.take());
        assertFalse(budget.hasRemaining());
        assertEquals(0, budget.remaining());
    }

    @Test
    void negativeLimitsAreClosed() {
        RenderBudget budget = new RenderBudget(-100);
        assertFalse(budget.take());
        assertEquals(0, budget.remaining());
    }

    @Test
    void childCapsPreventOneVisualCategoryFromStarvingTheSharedBudget() {
        RenderBudget shared = new RenderBudget(5);
        RenderBudget trails = shared.child(2);
        assertTrue(trails.take());
        assertTrue(trails.take());
        assertFalse(trails.take());
        assertEquals(3, shared.remaining());
        assertTrue(shared.take());
    }
}
