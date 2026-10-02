package cn.originalrpg.api.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable view of a player's one-time system journey. */
public record JourneyProgress(List<JourneyStep> orderedSteps, Set<JourneyStep> completedSteps) {
    public JourneyProgress {
        Objects.requireNonNull(orderedSteps, "orderedSteps");
        Objects.requireNonNull(completedSteps, "completedSteps");
        if (orderedSteps.isEmpty()) throw new IllegalArgumentException("Journey must contain at least one step");
        if (Set.copyOf(orderedSteps).size() != orderedSteps.size()) {
            throw new IllegalArgumentException("Journey steps must be unique");
        }
        if (!orderedSteps.containsAll(completedSteps)) {
            throw new IllegalArgumentException("Completed journey step is not part of this journey");
        }
        orderedSteps = List.copyOf(orderedSteps);
        completedSteps = completedSteps.isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(completedSteps));
    }

    public int completedCount() {
        return completedSteps.size();
    }

    public int totalCount() {
        return orderedSteps.size();
    }

    public boolean complete() {
        return completedSteps.size() == orderedSteps.size();
    }

    public Optional<JourneyStep> nextStep() {
        return orderedSteps.stream().filter(step -> !completedSteps.contains(step)).findFirst();
    }

    public boolean completed(JourneyStep step) {
        return completedSteps.contains(Objects.requireNonNull(step, "step"));
    }
}
