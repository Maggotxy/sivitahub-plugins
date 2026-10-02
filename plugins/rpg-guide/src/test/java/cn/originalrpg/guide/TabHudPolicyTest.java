package cn.originalrpg.guide;

import cn.originalrpg.api.model.SkillCooldownSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TabHudPolicyTest {
    @Test
    void clampsSingleLineSkillSummaryToTwoEntries() {
        assertEquals(1, TabHudPolicy.maximumSkills(0));
        assertEquals(2, TabHudPolicy.maximumSkills(2));
        assertEquals(2, TabHudPolicy.maximumSkills(4));
        assertEquals(2, TabHudPolicy.maximumSkills(99));
    }

    @Test
    void prioritizesCoolingSkillsThenTheSoonestReady() {
        long now = 10_000L;
        List<SkillCooldownSnapshot> selected = TabHudPolicy.visibleSkills(List.of(
                new SkillCooldownSnapshot("heal", 0L),
                new SkillCooldownSnapshot("renewal", 30_000L),
                new SkillCooldownSnapshot("sanctuary", 20_000L),
                new SkillCooldownSnapshot("fate-seal", 0L)
        ), now, 2);

        assertEquals(List.of("sanctuary", "renewal"),
                selected.stream().map(SkillCooldownSnapshot::skillId).toList());
    }

    @Test
    void retainsConfiguredOrderWhenEveryVisibleSkillIsReady() {
        List<SkillCooldownSnapshot> selected = TabHudPolicy.visibleSkills(List.of(
                new SkillCooldownSnapshot("power-shot", 0L),
                new SkillCooldownSnapshot("volley", 0L),
                new SkillCooldownSnapshot("piercing-shot", 0L)
        ), 10_000L, 2);

        assertEquals(List.of("power-shot", "volley"),
                selected.stream().map(SkillCooldownSnapshot::skillId).toList());
    }

    @Test
    void handlesEmptySnapshotsWithoutAllocatingPlaceholderEntries() {
        assertEquals(List.of(), TabHudPolicy.visibleSkills(null, 10_000L, 2));
        assertEquals(List.of(), TabHudPolicy.visibleSkills(List.of(), 10_000L, 2));
    }
}
