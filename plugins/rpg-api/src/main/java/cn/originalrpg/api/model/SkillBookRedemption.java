package cn.originalrpg.api.model;

import java.util.Objects;

public record SkillBookRedemption(
        Status status,
        int pointsGranted,
        int usesInLimitGroup,
        PlayerProfile profile
) {
    public SkillBookRedemption {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(profile, "profile");
        if (pointsGranted < 0 || usesInLimitGroup < 0) {
            throw new IllegalArgumentException("Skill-book counters cannot be negative");
        }
    }

    public enum Status {
        SUCCESS,
        DUPLICATE,
        LIMIT_REACHED,
        POINT_LIMIT
    }
}
