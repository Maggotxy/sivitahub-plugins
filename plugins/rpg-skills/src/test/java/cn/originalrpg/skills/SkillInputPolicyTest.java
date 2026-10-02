package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkillInputPolicyTest {
    @Test
    void secondPressInsideWindowCyclesInsteadOfCasting() {
        assertTrue(SkillInputPolicy.isDoublePress(1_000L, 1_260L, 260L));
        assertFalse(SkillInputPolicy.isDoublePress(1_000L, 1_261L, 260L));
        assertFalse(SkillInputPolicy.isDoublePress(1_000L, 999L, 260L));
    }

    @Test
    void castDelayCoversTheWholeDoublePressWindow() {
        assertEquals(6L, SkillInputPolicy.delayTicks(260L));
        assertEquals(4L, SkillInputPolicy.delayTicks(180L));
    }
}
