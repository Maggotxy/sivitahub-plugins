package cn.originalrpg.guide;

import cn.originalrpg.api.model.JourneyStep;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure ordering policy shared by the journey menu and the live hotbar compass. */
final class JourneyUiPolicy {
    enum State {
        COMPLETED,
        CURRENT,
        LATER
    }

    private JourneyUiPolicy() {
    }

    static JourneyStep next(List<JourneyStep> ordered, Set<JourneyStep> completed) {
        Objects.requireNonNull(ordered, "ordered");
        Objects.requireNonNull(completed, "completed");
        return ordered.stream().filter(step -> !completed.contains(step)).findFirst().orElse(null);
    }

    static State state(JourneyStep step, Set<JourneyStep> completed, JourneyStep next) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(completed, "completed");
        return state(step, completed.contains(step), next);
    }

    static State state(JourneyStep step, boolean completed, JourneyStep next) {
        Objects.requireNonNull(step, "step");
        if (completed) return State.COMPLETED;
        return step == next ? State.CURRENT : State.LATER;
    }
}
