package cn.originalrpg.api.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PityRulesTest {
    @Test
    void randomSuccessResetsFailuresBeforeTheGuarantee() {
        PityRules.Outcome outcome = PityRules.attempt(3, 10, 0.25, 0.10);

        assertTrue(outcome.dropped());
        assertFalse(outcome.guaranteed());
        assertEquals(0, outcome.failures());
    }

    @Test
    void finalAllowedFailureTurnsIntoAGuaranteedDrop() {
        PityRules.Outcome outcome = PityRules.attempt(9, 10, 0.0, 0.99);

        assertTrue(outcome.dropped());
        assertTrue(outcome.guaranteed());
        assertEquals(0, outcome.failures());
    }

    @Test
    void failedAttemptAdvancesExactlyOnce() {
        PityRules.Outcome outcome = PityRules.attempt(4, 10, 0.20, 0.20);

        assertFalse(outcome.dropped());
        assertFalse(outcome.guaranteed());
        assertEquals(5, outcome.failures());
    }

    @Test
    void invalidProbabilityOrCounterFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> PityRules.attempt(-1, 10, 0.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> PityRules.attempt(0, 0, 0.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> PityRules.attempt(0, 10, 1.1, 0.5));
        assertThrows(IllegalArgumentException.class, () -> PityRules.attempt(0, 10, 0.1, 1.0));
    }
}
