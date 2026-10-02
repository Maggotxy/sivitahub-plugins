package cn.originalrpg.skills;

import cn.originalrpg.api.model.SkillCooldownSnapshot;

import java.util.Comparator;
import java.util.List;

/** Pure selection policy shared by the short ActionBar renderer and its tests. */
final class SkillHudProjection {
    private SkillHudProjection() {
    }

    static List<SkillCooldownSnapshot> select(
            List<SkillCooldownSnapshot> learned,
            String focusSkillId,
            long nowEpochMillis,
            int maximum
    ) {
        if (learned == null || learned.isEmpty() || maximum <= 0) return List.of();
        String focus = focusSkillId == null ? "" : focusSkillId;
        return learned.stream()
                .sorted(Comparator
                        .comparing((SkillCooldownSnapshot entry) -> !entry.skillId().equals(focus))
                        .thenComparing(entry -> entry.ready(nowEpochMillis))
                        .thenComparing(Comparator.comparingLong(
                                SkillCooldownSnapshot::readyAtEpochMillis).reversed())
                        .thenComparing(SkillCooldownSnapshot::skillId))
                .limit(maximum)
                .toList();
    }
}
