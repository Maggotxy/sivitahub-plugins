package cn.originalrpg.skills;

/** Pure load-shedding policy for all time-based skill mechanics. */
final class SkillSequenceRules {
    private SkillSequenceRules() {
    }

    static LoadProfile profile(double tps, int activeSequences, int pressureAt, int criticalAt) {
        double safeTps = Double.isFinite(tps) ? Math.clamp(tps, 0.0, 20.0) : 20.0;
        int active = Math.max(0, activeSequences);
        if (safeTps < 16.0 || active >= Math.max(pressureAt + 1, criticalAt)) {
            return LoadProfile.CRITICAL;
        }
        if (safeTps < 18.5 || active >= Math.max(1, pressureAt)) {
            return LoadProfile.PRESSURE;
        }
        return LoadProfile.NORMAL;
    }

    static int activeLimit(LoadProfile profile, int normal, int pressure, int critical) {
        return switch (profile) {
            case NORMAL -> Math.clamp(normal, 16, 128);
            case PRESSURE -> Math.clamp(pressure, 8, Math.max(8, normal));
            case CRITICAL -> Math.clamp(critical, 2, Math.max(2, pressure));
        };
    }

    static int workBudget(LoadProfile profile, int normal, int pressure, int critical) {
        return switch (profile) {
            case NORMAL -> Math.clamp(normal, 8, 128);
            case PRESSURE -> Math.clamp(pressure, 4, Math.max(4, normal));
            case CRITICAL -> Math.clamp(critical, 1, Math.max(1, pressure));
        };
    }

    static int gameplayActiveLimit(int configured) {
        return Math.clamp(configured, 64, 512);
    }

    enum LoadProfile {
        NORMAL,
        PRESSURE,
        CRITICAL
    }
}
