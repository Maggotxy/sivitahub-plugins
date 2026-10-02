package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReferenceCounterTest {
    @Test
    void overlappingTravelMayNotReleaseAnotherRequestsTicket() {
        ReferenceCounter<String> references = new ReferenceCounter<>();

        assertTrue(references.retain("chunk"));
        assertFalse(references.retain("chunk"));
        assertFalse(references.release("chunk"));
        assertTrue(references.retained().contains("chunk"));
        assertTrue(references.release("chunk"));
        assertTrue(references.retained().isEmpty());
        assertFalse(references.release("chunk"));
    }
}
