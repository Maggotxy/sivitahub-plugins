package cn.originalrpg.api.model;

/**
 * Authoritative outcome of one skill-level upgrade attempt.
 *
 * <p>{@link Status#STALE_LEVEL} is deliberately distinct from a maximum-level
 * result: callers submit the level they rendered to the player, so replaying a
 * stale GUI click cannot buy two levels.</p>
 */
public record SkillUpgradeResult(Status status, SkillProgress progress, int spentPoints) {
    public enum Status {
        SUCCESS,
        NOT_LEARNED,
        MAX_LEVEL,
        STALE_LEVEL,
        INSUFFICIENT_POINTS,
        INVALID
    }
}
