package cn.originalrpg.integrations;

import cn.originalrpg.api.rules.ProgressionRules;

/**
 * 成长占位符取值。全部经 {@link ProgressionRules} 计算，
 * 不在本类里复制任何等级/经验公式。
 */
final class ProgressionPlaceholders {
    private ProgressionPlaceholders() {
    }

    /** 返回占位符文本；未知参数返回 null 由调用方走原有兜底。 */
    static String value(RpgSnapshot snapshot, String parameter) {
        return switch (parameter) {
            case "effective_level" ->
                    Integer.toString(ProgressionRules.effectiveLevel(snapshot.ascension(), snapshot.level()));
            case "effective_level_cap" -> Integer.toString(ProgressionRules.effectiveLevelCap());
            case "experience" -> Long.toString(snapshot.experience());
            case "experience_stage_budget" ->
                    Long.toString(ProgressionRules.totalExperienceForAscension(snapshot.ascension()));
            case "experience_stage_spent" -> Long.toString(ProgressionRules.stageExperienceSpent(
                    snapshot.ascension(), snapshot.level(), snapshot.experience()));
            case "experience_stage_remaining" -> Long.toString(ProgressionRules.stageExperienceRemaining(
                    snapshot.ascension(), snapshot.level(), snapshot.experience()));
            case "experience_total" -> Long.toString(ProgressionRules.totalExperienceToEffective(
                    ProgressionRules.effectiveLevel(snapshot.ascension(), snapshot.level())));
            default -> null;
        };
    }
}
