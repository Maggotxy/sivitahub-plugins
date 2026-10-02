package cn.originalrpg.guide;

import cn.originalrpg.api.model.SkillCooldownSnapshot;

import java.util.Comparator;
import java.util.List;

/** Width and priority guard for the single-line TAB skill summary. */
final class TabHudPolicy {
    static final int MAXIMUM_SKILLS = 2;

    private TabHudPolicy() {
    }

    static int maximumSkills(int configured) {
        return Math.clamp(configured, 1, MAXIMUM_SKILLS);
    }

    /**
     * Cooling skills are the actionable information a player opens TAB to
     * check, so they take precedence over ready skills. Among cooling skills,
     * the one that becomes ready first is shown first. Java's stable sort
     * preserves configured progression order for otherwise-equal ready skills.
     */
    static List<SkillCooldownSnapshot> visibleSkills(
            List<SkillCooldownSnapshot> learned,
            long nowEpochMillis,
            int configuredMaximum
    ) {
        if (learned == null || learned.isEmpty()) return List.of();
        int maximum = maximumSkills(configuredMaximum);
        return learned.stream()
                .sorted(Comparator
                        .comparing((SkillCooldownSnapshot entry) -> entry.ready(nowEpochMillis))
                        .thenComparingLong(entry -> entry.ready(nowEpochMillis)
                                ? 0L : entry.readyAtEpochMillis()))
                .limit(maximum)
                .toList();
    }
}
