package cn.originalrpg.api.model;

import java.util.Objects;

/** Authoritative eligibility or settlement result returned by the compound ascension transaction. */
public record AscensionResult(
        Status status,
        PlayerProfile profile,
        AscensionRequirement requirement,
        AscensionEvidence evidence,
        int learnedSkills,
        long dungeonClears,
        int distinctDungeons,
        long merit,
        boolean previousBranchSelected
) {
    public AscensionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(evidence, "evidence");
        if (learnedSkills < 0 || dungeonClears < 0 || distinctDungeons < 0 || merit < 0) {
            throw new IllegalArgumentException("Negative ascension observations");
        }
        if (status != Status.MAXIMUM_REACHED) Objects.requireNonNull(requirement, "requirement");
    }

    public boolean eligible() {
        return status == Status.READY || status == Status.SUCCESS;
    }

    public enum Status {
        READY,
        SUCCESS,
        INVALID,
        STALE_REQUEST,
        MAXIMUM_REACHED,
        CHAPTER_LOCKED,
        LEVEL_REQUIRED,
        JOURNEY_REQUIRED,
        PREVIOUS_BRANCH_REQUIRED,
        SKILLS_REQUIRED,
        DUNGEONS_REQUIRED,
        EQUIPMENT_REQUIRED,
        MERIT_REQUIRED
    }
}
