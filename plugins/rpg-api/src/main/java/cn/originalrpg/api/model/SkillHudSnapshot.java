package cn.originalrpg.api.model;

import java.util.List;

/**
 * Synchronous, memory-only skill state for UI consumers. Implementations must
 * never perform persistence work while serving this snapshot.
 */
public record SkillHudSnapshot(
        List<SkillCooldownSnapshot> learnedSkills,
        int mana,
        int manaCap,
        long refreshedAtEpochMillis
) {
    public SkillHudSnapshot {
        learnedSkills = List.copyOf(learnedSkills == null ? List.of() : learnedSkills);
        if (mana < 0 || manaCap < 0 || mana > manaCap) {
            throw new IllegalArgumentException("mana must be between zero and manaCap");
        }
        if (refreshedAtEpochMillis < 0L) throw new IllegalArgumentException("refreshedAtEpochMillis cannot be negative");
    }
}
