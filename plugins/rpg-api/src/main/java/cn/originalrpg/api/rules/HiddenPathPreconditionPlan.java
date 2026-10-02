package cn.originalrpg.api.rules;

import cn.originalrpg.api.model.HiddenPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 路线前置的「来源清单」：一条隐藏路线到底要读哪些配置键，由这里唯一决定。
 *
 * <p>为什么需要它：可达性判定（{@link HiddenPathReachability}）与可达性测试
 * （{@code HiddenRouteReachabilityTest}）必须看同一份前置集合。若测试自己手写
 * 前置清单，配置新增一条前置而门禁没跟上，矛盾就会漏检 —— 那正是 G-106 的
 * 成因形态。因此判定的输入集合不是测试里抄的，而是这里从 {@link HiddenPathRules}
 * 推导出来的。
 *
 * <p>每个 slot 只描述「去哪个配置段、取哪个键、算哪一类 requirement」，
 * 不碰任何 Bukkit 类型，所以 rpg-api 侧可以独立断言「清单是否完整」。
 */
public final class HiddenPathPreconditionPlan {
    private HiddenPathPreconditionPlan() {
    }

    /** 前置取自哪个配置段。 */
    public enum Source {
        /** 技能门：{@code skills.<id>.minimum-ascension} + {@code minimum-level}。 */
        SKILL,
        /** 副本门：{@code dungeons.<id>.minimum-effective-level}（绝对有效等级）。 */
        DUNGEON
    }

    /**
     * 一条前置的取值位置。
     *
     * @param slot        稳定标识（技能 id / 副本 id / 技能书 id），用于报错定位
     * @param lookupKey   配置里的查找 key（技能 id 或副本 id）
     * @param source      去哪个配置段取值
     * @param requirement 归类的 requirement code
     */
    public record Slot(String slot, String lookupKey, Source source,
                       HiddenPathReachability.Requirement requirement) {
        public Slot {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(lookupKey, "lookupKey");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(requirement, "requirement");
        }
    }

    /**
     * 一条路线的全部前置来源。
     *
     * <p>技能书没有自己的门，它的门属于掉落它的副本；所以这里只记
     * {@code lookupKey = 技能书 id}，由装配侧（rpg-skills）用
     * {@code rewards.skill-book.id} 反查归属副本。
     */
    public static List<Slot> slots(HiddenPath path) {
        Objects.requireNonNull(path, "path");
        List<Slot> slots = new ArrayList<>(3);
        slots.add(new Slot(HiddenPathRules.requiredSkill(path),
                HiddenPathRules.requiredSkill(path),
                Source.SKILL,
                HiddenPathReachability.Requirement.SKILL));
        slots.add(new Slot(HiddenPathRules.requiredDungeon(path),
                HiddenPathRules.requiredDungeon(path),
                Source.DUNGEON,
                HiddenPathReachability.Requirement.HIDDEN_DUNGEON));
        slots.add(new Slot(HiddenPathRules.requiredSkillBook(path),
                HiddenPathRules.requiredSkillBook(path),
                Source.DUNGEON,
                HiddenPathReachability.Requirement.SKILL_BOOK));
        return List.copyOf(slots);
    }

    /** 每条路线的前置来源，键顺序 = {@link HiddenPath} 的声明顺序。 */
    public static Map<HiddenPath, List<Slot>> all() {
        Map<HiddenPath, List<Slot>> plan = new LinkedHashMap<>();
        for (HiddenPath path : HiddenPath.values()) plan.put(path, slots(path));
        return Map.copyOf(plan);
    }
}
