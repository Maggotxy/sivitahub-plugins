package cn.originalrpg.skills;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.rules.HiddenPathPreconditionPlan;
import cn.originalrpg.api.rules.HiddenPathReachability;
import cn.originalrpg.api.rules.HiddenPathRules;
import cn.originalrpg.api.rules.ProgressionRules;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 隐藏路线可达性的「配置装配」侧：把 RpgSkills / RpgDungeons 的真实配置读成
 * {@link HiddenPathReachability.Precondition}，再交给 rpg-api 的规则判定。
 *
 * <p>G-106 就是这里能一次拦住的那类矛盾：血契要求已学 {@code vanished-horizon}，
 * 而该技能挂在刺客 stage-3 分支 {@code unseen} 上，二转玩家永远学不到。
 *
 * <p>「读哪些键」不在这里决定，而是照 {@link HiddenPathPreconditionPlan#slots} 逐个取：
 * 判定与回归测试共用同一份来源清单，因此新增前置不会只改一处。
 *
 * <ul>
 *   <li>技能门：{@code skills.<id>.minimum-ascension} + {@code skills.<id>.minimum-level}
 *       （已由 {@code validateCatalog} 强制与本分支 stage 一致）。</li>
 *   <li>副本门 / 技能书门：{@code dungeons.<id>.minimum-effective-level}（绝对有效等级），
 *       属 rpg-dungeons 的 softdepend，缺失时跳过并记录，不阻塞启动。</li>
 * </ul>
 */
public final class HiddenRouteReachability {
    private HiddenRouteReachability() {
    }

    /** 一次装配的产物：每条路线无法满足的 requirement → 越界前置 id。 */
    public record Report(
            Map<HiddenPath, Map<HiddenPathReachability.Requirement, Set<String>>> contradictions,
            List<String> skipped
    ) {
        public Report {
            Objects.requireNonNull(contradictions, "contradictions");
            skipped = List.copyOf(skipped);
        }

        public boolean reachable() {
            return contradictions.isEmpty();
        }

        public String describe() {
            StringBuilder text = new StringBuilder();
            contradictions.forEach((path, blockers) ->
                    text.append(HiddenPathReachability.describe(path, blockers)).append('\n'));
            if (!skipped.isEmpty()) {
                text.append("跳过（配置缺失）：").append(skipped).append('\n');
            }
            return text.toString();
        }
    }

    /**
     * @param skillRoot   RpgSkills 的 {@code skills} 段
     * @param dungeonRoot RpgDungeons 的 {@code dungeons} 段；可为 null（插件未安装）
     */
    public static Report inspect(ConfigurationSection skillRoot, ConfigurationSection dungeonRoot) {
        Objects.requireNonNull(skillRoot, "skillRoot");
        Map<HiddenPath, Map<HiddenPathReachability.Requirement, Set<String>>> contradictions =
                new java.util.EnumMap<>(HiddenPath.class);
        List<String> skipped = new ArrayList<>();
        for (HiddenPath path : HiddenPath.values()) {
            List<HiddenPathReachability.Precondition> preconditions =
                    preconditions(path, skillRoot, dungeonRoot, skipped);
            Map<HiddenPathReachability.Requirement, Set<String>> blockers =
                    HiddenPathReachability.unattainableDetails(path, preconditions);
            if (!blockers.isEmpty()) contradictions.put(path, blockers);
        }
        return new Report(contradictions, skipped);
    }

    /** 按来源清单逐个装配；缺哪个键就记哪个键，不猜。 */
    private static List<HiddenPathReachability.Precondition> preconditions(
            HiddenPath path, ConfigurationSection skillRoot, ConfigurationSection dungeonRoot,
            List<String> skipped) {
        List<HiddenPathReachability.Precondition> preconditions = new ArrayList<>();
        for (HiddenPathPreconditionPlan.Slot slot : HiddenPathPreconditionPlan.slots(path)) {
            switch (slot.source()) {
                case SKILL -> addSkill(slot, skillRoot, preconditions, skipped);
                case DUNGEON -> addDungeon(slot, dungeonRoot, preconditions, skipped);
            }
        }
        return preconditions;
    }

    private static void addSkill(
            HiddenPathPreconditionPlan.Slot slot, ConfigurationSection skillRoot,
            List<HiddenPathReachability.Precondition> preconditions, List<String> skipped) {
        ConfigurationSection skill = skillRoot.getConfigurationSection(slot.lookupKey());
        if (skill == null) {
            skipped.add("skills." + slot.lookupKey());
            return;
        }
        preconditions.add(new HiddenPathReachability.Precondition(
                slot.slot(),
                skill.getInt("minimum-ascension", 0),
                skill.getInt("minimum-level", 1),
                slot.requirement()));
    }

    /**
     * 副本门在配置里是绝对有效等级，这里把它换算成「该等级所属转数 + 本地等级」，
     * 以便与技能门走同一条天梯比较。
     *
     * <p>{@code SKILL_BOOK} 这类 slot 的 lookupKey 是技能书 id，不是副本 id：
     * 先按 {@code rewards.skill-book.id} 反查归属副本，门的归属才是那个副本。
     */
    private static void addDungeon(
            HiddenPathPreconditionPlan.Slot slot, ConfigurationSection dungeonRoot,
            List<HiddenPathReachability.Precondition> preconditions, List<String> skipped) {
        String dungeonId = slot.lookupKey();
        if (slot.requirement() == HiddenPathReachability.Requirement.SKILL_BOOK) {
            dungeonId = dungeonDroppingSkillBook(slot.lookupKey(), dungeonRoot);
            if (dungeonId == null) {
                skipped.add(dungeonRoot == null
                        ? "skill-book " + slot.lookupKey() + "（RpgDungeons 未安装）"
                        : "skill-book " + slot.lookupKey());
                return;
            }
        }
        if (dungeonRoot == null) {
            skipped.add("dungeons." + dungeonId + "（RpgDungeons 未安装）");
            return;
        }
        ConfigurationSection dungeon = dungeonRoot.getConfigurationSection(dungeonId);
        if (dungeon == null) {
            skipped.add("dungeons." + dungeonId);
            return;
        }
        int absolute = dungeon.getInt("minimum-effective-level", 1);
        preconditions.add(new HiddenPathReachability.Precondition(
                dungeonId,
                HiddenPathReachability.ascensionOf(absolute),
                localOf(absolute),
                slot.requirement()));
    }

    /** 找出掉落指定技能书的副本 id；找不到返回 null。 */
    private static String dungeonDroppingSkillBook(String book, ConfigurationSection dungeonRoot) {
        if (dungeonRoot == null) return null;
        for (String id : dungeonRoot.getKeys(false)) {
            String dropped = dungeonRoot.getString(id + ".rewards.skill-book.id", "");
            if (book.equals(dropped)) return id;
        }
        return null;
    }

    private static int localOf(int effectiveLevel) {
        return effectiveLevel - HiddenPathReachability.ascensionOf(effectiveLevel)
                * ProgressionRules.LEVELS_PER_ASCENSION;
    }
}
