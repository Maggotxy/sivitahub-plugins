package cn.originalrpg.api.model;

import java.util.Objects;
import java.util.Optional;

public record SkillProgress(String skillId, int level, int pointCost, Optional<String> variant) {
    public SkillProgress {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(variant, "variant");
        if (level < 1 || pointCost < 0) throw new IllegalArgumentException("Invalid skill progress");
    }
}
