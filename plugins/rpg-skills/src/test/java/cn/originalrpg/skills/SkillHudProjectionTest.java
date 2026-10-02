package cn.originalrpg.skills;

import cn.originalrpg.api.model.SkillCooldownSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillHudProjectionTest {
    @Test
    void keepsFocusFirstThenPrioritizesActiveCooldowns() {
        List<SkillCooldownSnapshot> result = SkillHudProjection.select(List.of(
                new SkillCooldownSnapshot("ready", 0L),
                new SkillCooldownSnapshot("long", 9_000L),
                new SkillCooldownSnapshot("focus", 3_000L),
                new SkillCooldownSnapshot("short", 2_000L)
        ), "focus", 1_000L, 3);

        assertEquals(List.of("focus", "long", "short"),
                result.stream().map(SkillCooldownSnapshot::skillId).toList());
    }

    @Test
    void returnsOnlyConfiguredMaximum() {
        List<SkillCooldownSnapshot> result = SkillHudProjection.select(List.of(
                new SkillCooldownSnapshot("a", 0L),
                new SkillCooldownSnapshot("b", 0L)
        ), "", 1_000L, 1);

        assertEquals(1, result.size());
    }
}
