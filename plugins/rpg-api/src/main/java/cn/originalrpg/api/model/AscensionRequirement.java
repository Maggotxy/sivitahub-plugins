package cn.originalrpg.api.model;

/** Immutable, server-owned requirements for one ascension transition. */
public record AscensionRequirement(
        int targetAscension,
        int equipmentScore,
        int learnedSkills,
        long dungeonClears,
        int distinctDungeons,
        long meritCost,
        boolean journeyRequired,
        boolean previousBranchRequired
) {
    public AscensionRequirement {
        if (targetAscension < 1 || targetAscension > 3 || equipmentScore < 0
                || learnedSkills < 0 || dungeonClears < 0 || distinctDungeons < 0
                || distinctDungeons > dungeonClears || meritCost < 0) {
            throw new IllegalArgumentException("Invalid ascension requirement");
        }
    }
}
