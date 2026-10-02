package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.Optional;

/** Result of a single, serialized hidden-path activation attempt. */
public record HiddenPathActivationResult(
        Status status,
        Optional<HiddenPathProfile> profile,
        Optional<HiddenPathEligibility> eligibility
) {
    public HiddenPathActivationResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(eligibility, "eligibility");
    }

    public enum Status {
        ACTIVATED,
        ALREADY_ACTIVE,
        REQUIREMENTS_NOT_MET,
        CAPACITY_FULL,
        INVALID
    }
}
