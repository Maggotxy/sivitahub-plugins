package cn.originalrpg.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CombatTagRulesTest {
    @Test
    void deadlineAndRemainingSecondsRoundUpSafely() {
        long deadline = CombatTagRules.deadline(10_000L, 12L);
        assertEquals(22_000L, deadline);
        assertEquals(12L, CombatTagRules.remainingSeconds(10_000L, deadline));
        assertEquals(1L, CombatTagRules.remainingSeconds(21_999L, deadline));
        assertEquals(0L, CombatTagRules.remainingSeconds(22_000L, deadline));
    }
}
