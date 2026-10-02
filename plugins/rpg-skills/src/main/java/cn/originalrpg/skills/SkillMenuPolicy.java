package cn.originalrpg.skills;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.SkillProgress;

import java.util.Collection;

/** Keeps the pre-class experience separate from the real class skill tree. */
final class SkillMenuPolicy {
    enum Mode {
        CHOOSE_CLASS,
        SKILL_TREE
    }

    enum Gate {
        CLASS,
        ASCENSION,
        LEVEL,
        BRANCH,
        PREREQUISITE,
        ATTRIBUTE,
        POINTS
    }

    private SkillMenuPolicy() {
    }

    static Mode mode(Archetype archetype) {
        return archetype == null || archetype == Archetype.UNCHOSEN
                ? Mode.CHOOSE_CLASS : Mode.SKILL_TREE;
    }

    static int remainingPoints(int earnedPoints, Collection<SkillProgress> learnedSkills) {
        long spent = learnedSkills == null ? 0L : learnedSkills.stream()
                .mapToLong(progress -> Math.max(0, progress.pointCost()))
                .sum();
        return (int) Math.clamp((long) Math.max(0, earnedPoints) - spent, 0L, Integer.MAX_VALUE);
    }

    static String action(boolean learned, boolean borrowed, String lockReason, String skillId) {
        return action(learned, borrowed, lockReason, skillId, 1);
    }

    static String action(
            boolean learned, boolean borrowed, String lockReason, String skillId, int currentLevel
    ) {
        if (skillId == null || skillId.isBlank()) return null;
        if (learned) return "skill " + skillId + " " + Math.max(1, currentLevel);
        if (borrowed) return "bind " + skillId;
        return lockReason == null ? "learn " + skillId : null;
    }

    static String action(boolean learned, String lockReason, String skillId) {
        return action(learned, false, lockReason, skillId);
    }

    static boolean keepsMenuOpen(String action) {
        return action != null && (action.startsWith("learn ") || action.startsWith("bind ")
                || action.startsWith("skill ") || action.startsWith("page:"));
    }

    /**
     * A locked node leads to the system that can actually remove its gate.
     * Prerequisites stay in the tree because the required node is already visible there.
     */
    static String routeFor(Gate gate) {
        if (gate == null) return null;
        return switch (gate) {
            case CLASS, ASCENSION, BRANCH, ATTRIBUTE -> "command:class";
            case LEVEL, POINTS -> "command:mobguide";
            case PREREQUISITE -> null;
        };
    }

    /** Prefer a skill that can be learned now over a merely visible future node. */
    static int objectivePriority(Gate gate) {
        if (gate == null) return 0;
        return switch (gate) {
            case PREREQUISITE -> 1;
            case ATTRIBUTE -> 2;
            case POINTS -> 3;
            case LEVEL -> 4;
            case ASCENSION, BRANCH -> 5;
            case CLASS -> 6;
        };
    }
}
