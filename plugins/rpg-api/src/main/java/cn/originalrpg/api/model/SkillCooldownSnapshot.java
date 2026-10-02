package cn.originalrpg.api.model;

/** A learned skill and the wall-clock instant at which it can next be cast. */
public record SkillCooldownSnapshot(String skillId, long readyAtEpochMillis) {
    public static final String EMPTY_SLOT_ID = "__empty__";

    public SkillCooldownSnapshot {
        if (skillId == null || skillId.isBlank()) throw new IllegalArgumentException("skillId is required");
        if (readyAtEpochMillis < 0L) throw new IllegalArgumentException("readyAtEpochMillis cannot be negative");
    }

    public long remainingSeconds(long nowEpochMillis) {
        return Math.max(0L, (readyAtEpochMillis - nowEpochMillis + 999L) / 1000L);
    }

    public boolean ready(long nowEpochMillis) {
        return readyAtEpochMillis <= nowEpochMillis;
    }

    public static SkillCooldownSnapshot empty() {
        return new SkillCooldownSnapshot(EMPTY_SLOT_ID, 0L);
    }

    public boolean emptySlot() {
        return EMPTY_SLOT_ID.equals(skillId);
    }
}
