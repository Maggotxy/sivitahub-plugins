package cn.originalrpg.api.model;

import java.time.Duration;
import java.util.Objects;

/** Core-owned timing policy; callers cannot shorten it by supplying command arguments. */
public record CommunityLifecyclePolicy(
        Duration ownerInactivity,
        Duration candidateMembership,
        Duration successionCooling,
        Duration dissolutionCooling
) {
    public CommunityLifecyclePolicy {
        positive(ownerInactivity, "ownerInactivity");
        positive(candidateMembership, "candidateMembership");
        positive(successionCooling, "successionCooling");
        positive(dissolutionCooling, "dissolutionCooling");
    }

    public static CommunityLifecyclePolicy defaults() {
        return new CommunityLifecyclePolicy(Duration.ofDays(30), Duration.ofDays(7),
                Duration.ofHours(72), Duration.ofHours(72));
    }

    public Duration coolingFor(CommunityLifecycleAction action) {
        return action == CommunityLifecycleAction.SUCCESSION ? successionCooling : dissolutionCooling;
    }

    private static void positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most 3650 days");
        }
    }
}
