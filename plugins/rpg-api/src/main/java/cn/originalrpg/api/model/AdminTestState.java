package cn.originalrpg.api.model;

import java.util.Objects;

/** Read-only result of an audited administrator test-mode operation. */
public record AdminTestState(boolean active, PlayerProfile profile, int learnedSkills) {
    public AdminTestState {
        Objects.requireNonNull(profile, "profile");
        if (learnedSkills < 0) throw new IllegalArgumentException("learnedSkills cannot be negative");
    }
}
