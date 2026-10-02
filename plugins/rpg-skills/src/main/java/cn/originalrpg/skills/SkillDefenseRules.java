package cn.originalrpg.skills;

/** Pure mitigation boundaries for authored warrior stances. */
final class SkillDefenseRules {
    private SkillDefenseRules() {
    }

    static double reduction(Mode mode, boolean pvp) {
        return switch (mode) {
            case SHIELD_WALL -> pvp ? 0.35 : 0.62;
            case FORTRESS -> pvp ? 0.25 : 0.45;
            case UNBROKEN_LINE -> pvp ? 0.15 : 0.30;
        };
    }

    static double apply(double damage, Mode mode, boolean pvp) {
        double safe = Double.isFinite(damage) ? Math.max(0.0, damage) : 0.0;
        return safe * (1.0 - reduction(mode, pvp));
    }

    enum Mode {
        SHIELD_WALL,
        FORTRESS,
        UNBROKEN_LINE
    }
}
