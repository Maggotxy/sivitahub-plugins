package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.HiddenPath;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 隐藏路线的「可达性」判定：路线要求的一切前置（必须已学的技能、隐藏副本、
 * 门票来源、技能书领悟）都必须能在路线自身开放之前或同时满足。
 *
 * <p>前置的真实数值分散在 rpg-skills / rpg-classes / rpg-dungeons 的配置里，
 * 所以调用方把 {@link Precondition} 传进来，{@link #unattainable} 只做一件事：
 * 把每个前置门换算到同一条绝对有效等级天梯（{@link ProgressionRules#effectiveLevel}），
 * 再与路线自身的门比较。任何「前置比路线更晚开放」的组合都会被具名报出，
 * 这就是 G-106 那一类配置矛盾的通用检测。
 *
 * <p>这不是仿真：不猜经验曲线、不猜金币产出，只看作者写死的硬门。
 */
public final class HiddenPathReachability {
    private HiddenPathReachability() {
    }

    /** 前置在 eligibility 里的 requirement code，与 {@code HiddenPathEligibility.missingRequirements()} 对齐。 */
    public enum Requirement {
        /** 路线要求的技能未学（{@link HiddenPathRules#requiredSkill}）。 */
        SKILL,
        /** 隐藏副本回执缺失。 */
        HIDDEN_DUNGEON,
        /** 技能书未领悟。 */
        SKILL_BOOK,
        /** 隐藏门票来源（普通副本）未通关。 */
        TICKET_SOURCE
    }

    /**
     * 一个前置的硬门。
     *
     * @param id              前置标识（技能 id / 副本 id / 技能书 id），用于报错定位
     * @param ascension       该前置最早开放的转数
     * @param localLevel      该前置在 {@code ascension} 那一转下的本地等级
     * @param requirement     归类的 requirement code
     */
    public record Precondition(String id, int ascension, int localLevel, Requirement requirement) {
        public Precondition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(requirement, "requirement");
            if (ascension < 0 || ascension > ProgressionRules.MAX_ASCENSION) {
                throw new IllegalArgumentException("ascension must be between 0 and "
                        + ProgressionRules.MAX_ASCENSION + ": " + ascension);
            }
            if (localLevel < 1 || localLevel > ProgressionRules.LOCAL_LEVEL_CAP) {
                throw new IllegalArgumentException("localLevel must be between 1 and "
                        + ProgressionRules.LOCAL_LEVEL_CAP + ": " + localLevel);
            }
        }

        /** 该前置的绝对有效等级门。 */
        public int absoluteGate() {
            return ProgressionRules.effectiveLevel(ascension, localLevel);
        }
    }

    /**
     * 路线自身能到的最高绝对有效等级：路线门那一转的本地满级。
     * 玩家可以在路线门那一转的任意本地等级激活路线，所以比较基准取该转顶端。
     */
    public static int routeCeiling(HiddenPath path) {
        Objects.requireNonNull(path, "path");
        return ProgressionRules.effectiveLevel(
                HiddenPathRules.minimumAscension(path), ProgressionRules.LOCAL_LEVEL_CAP);
    }

    /**
     * 无法满足的前置 requirement codes。空集 = 路线自身与其全部前置的硬门一致。
     *
     * <p>判定规则只有一条：前置的绝对有效等级门 &gt; 路线的绝对有效等级上限 ⇒
     * 该前置永远比路线更晚开放 ⇒ 路线永久不可达。
     */
    public static Set<Requirement> unattainable(HiddenPath path, List<Precondition> preconditions) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(preconditions, "preconditions");
        int ceiling = routeCeiling(path);
        Set<Requirement> blockers = new LinkedHashSet<>();
        for (Precondition precondition : preconditions) {
            if (precondition.absoluteGate() > ceiling) {
                blockers.add(precondition.requirement());
            }
        }
        return Set.copyOf(blockers);
    }

    /** 无法满足的前置明细，键 = requirement code，值 = 该 code 下所有越界前置的 id。 */
    public static Map<Requirement, Set<String>> unattainableDetails(
            HiddenPath path, List<Precondition> preconditions) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(preconditions, "preconditions");
        int ceiling = routeCeiling(path);
        Map<Requirement, Set<String>> blockers = new java.util.EnumMap<>(Requirement.class);
        for (Precondition precondition : preconditions) {
            if (precondition.absoluteGate() > ceiling) {
                blockers.computeIfAbsent(precondition.requirement(), key -> new LinkedHashSet<>())
                        .add(precondition.id());
            }
        }
        return Map.copyOf(blockers);
    }

    /**
     * 有效等级所属转数。1..30 → 0，31..60 → 1，…，91..120 → 3。
     */
    public static int ascensionOf(int effectiveLevel) {
        if (effectiveLevel < 1 || effectiveLevel > ProgressionRules.effectiveLevelCap()) {
            throw new IllegalArgumentException("effectiveLevel must be between 1 and "
                    + ProgressionRules.effectiveLevelCap() + ": " + effectiveLevel);
        }
        return Math.min(ProgressionRules.MAX_ASCENSION,
                (effectiveLevel - 1) / ProgressionRules.LEVELS_PER_ASCENSION);
    }

    /** 启动失败用的可读描述。 */
    public static String describe(HiddenPath path, Map<Requirement, Set<String>> blockers) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(blockers, "blockers");
        StringBuilder text = new StringBuilder("隐藏路线 ")
                .append(path)
                .append(" 要求的最小转数为 ")
                .append(HiddenPathRules.minimumAscension(path))
                .append("（有效等级上限 ")
                .append(routeCeiling(path))
                .append("），但以下前置开放更晚，路线永久不可达：");
        blockers.forEach((requirement, ids) ->
                text.append(' ').append(requirement).append('=').append(ids));
        return text.toString();
    }
}
