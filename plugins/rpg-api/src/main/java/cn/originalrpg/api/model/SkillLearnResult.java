package cn.originalrpg.api.model;

public record SkillLearnResult(Status status, SkillProgress progress, int spentPoints) {
    public enum Status {
        SUCCESS,
        ALREADY_LEARNED,
        INSUFFICIENT_POINTS,
        INVALID
    }
}
