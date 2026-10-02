package cn.originalrpg.api.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyProgressTest {
    @Test
    void reportsFirstIncompleteStepEvenWhenLaterPlayWasAlreadyObserved() {
        JourneyProgress progress = new JourneyProgress(
                List.of(JourneyStep.HUB, JourneyStep.CLASS, JourneyStep.EXPLORE),
                Set.of(JourneyStep.HUB, JourneyStep.EXPLORE));

        assertEquals(2, progress.completedCount());
        assertEquals(JourneyStep.CLASS, progress.nextStep().orElseThrow());
        assertFalse(progress.complete());
    }

    @Test
    void reportsCompletionWithoutASecondProgressionTrack() {
        List<JourneyStep> steps = List.of(JourneyStep.HUB, JourneyStep.CLASS);
        JourneyProgress progress = new JourneyProgress(steps, Set.copyOf(steps));

        assertTrue(progress.complete());
        assertTrue(progress.nextStep().isEmpty());
    }

    @Test
    void rejectsUnknownCompletedSteps() {
        assertThrows(IllegalArgumentException.class, () -> new JourneyProgress(
                List.of(JourneyStep.HUB), Set.of(JourneyStep.DUNGEON)));
    }
}
