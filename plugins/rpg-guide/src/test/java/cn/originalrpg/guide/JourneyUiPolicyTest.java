package cn.originalrpg.guide;

import cn.originalrpg.api.model.JourneyStep;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JourneyUiPolicyTest {
    private static final List<JourneyStep> ORDER = List.of(
            JourneyStep.HUB, JourneyStep.CLASS, JourneyStep.ATTRIBUTE, JourneyStep.EXPLORE);

    @Test
    void recommendsFirstMissingStepWithoutDiscardingOutOfOrderProgress() {
        Set<JourneyStep> completed = EnumSet.of(JourneyStep.HUB, JourneyStep.EXPLORE);

        JourneyStep next = JourneyUiPolicy.next(ORDER, completed);

        assertEquals(JourneyStep.CLASS, next);
        assertEquals(JourneyUiPolicy.State.COMPLETED,
                JourneyUiPolicy.state(JourneyStep.EXPLORE, completed, next));
        assertEquals(JourneyUiPolicy.State.CURRENT,
                JourneyUiPolicy.state(JourneyStep.CLASS, completed, next));
        assertEquals(JourneyUiPolicy.State.LATER,
                JourneyUiPolicy.state(JourneyStep.ATTRIBUTE, completed, next));
    }

    @Test
    void noRecommendationRemainsAfterEveryStepIsComplete() {
        assertNull(JourneyUiPolicy.next(ORDER, EnumSet.copyOf(ORDER)));
    }
}
