package cn.originalrpg.skills;

/** Bounded boss resistance for skill-applied crowd control and damage-over-time effects. */
final class SkillCrowdControlRules {
    private SkillCrowdControlRules() {
    }

    static EffectPlan plan(boolean boss, int durationTicks, int amplifier) {
        return plan(boss, false, durationTicks, amplifier);
    }

    static EffectPlan plan(boolean boss, boolean player, int durationTicks, int amplifier) {
        int duration = Math.clamp(durationTicks, 1, 20 * 60);
        int level = Math.clamp(amplifier, 0, 9);
        if (!boss && !player) return new EffectPlan(duration, level);
        return new EffectPlan(Math.max(1, (duration + 1) / 2), Math.max(0, level - 1));
    }

    record EffectPlan(int durationTicks, int amplifier) {
    }
}
