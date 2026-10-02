package cn.originalrpg.api.model;

import java.util.List;
import java.util.Objects;

/**
 * Read-only eligibility feedback. Requirement identifiers are stable internal
 * codes; presentation layers translate them instead of exposing database text.
 */
public record HiddenPathEligibility(
        HiddenPath path,
        boolean eligible,
        int holders,
        int capacity,
        List<String> missingRequirements
) {
    public HiddenPathEligibility {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(missingRequirements, "missingRequirements");
        missingRequirements = List.copyOf(missingRequirements);
        if (holders < 0 || capacity < 1 || holders > capacity) {
            throw new IllegalArgumentException("Invalid hidden-path capacity");
        }
    }
}
