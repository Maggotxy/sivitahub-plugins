package cn.originalrpg.skills;

import cn.originalrpg.api.model.HiddenPath;
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
 * 隐藏路线可达性的「配置装配」侧：把 RpgSkills / RpgClasses / RpgDungeons 的真实
 * 配置读成 {@link HiddenPathReachability.Precondition}，再交给 rpg-api 的规则判定。
 *
 * <p>G-106 就是这里能一次拦住的那类矛盾：血契要求已学 {@code vanished-horizon}，
 * 而该技能挂在刺客 stage-3 分支 {@code unseen} 上，二转玩家永远学不到。
 *
 * <p>装配规则：
 * <ul>
 *   <li>技能门：{@code skills.<id>.minimum-ascension} + {@code skills.<id>.minimum-level}
 *       （已由 {@code validateCatalog} 强制与本分支 stage 一致）。</li>
 *   <li>隐藏副本门 / 门票来源门：{@code dungeons.<id>.minimum-effective-level}（绝对有效等级，
 *       属 rpg-dungeons 的 softdepend，缺失时跳过并记录）。</li>
 *   <li>技能书门：取掉落该技能书的副本门（{@code dungeons.<id>.rewards.skill-book.id}）。</li>
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

    private static List<HiddenPathReachability.Precondition> preconditions(
            HiddenPath path, ConfigurationSection skillRoot, ConfigurationSection dungeonRoot,
            List<String> skipped) {
        List<HiddenPathReachability.Precondition> preconditions = new ArrayList<>();
        String skillId = HiddenPathRules.requiredSkill(path);
        ConfigurationSection skill = skillRoot.getConfigurationSection(skillId);
        if (skill == null) {
            skipped.add("skills." + skillId);
        } else {
            preconditions.add(new HiddenPathReachability.Precondition(
                    skillId,
                    skill.getInt("minimum-ascension", 0),
                    skill.getInt("minimum-level", 1),
                    HiddenPathReachability.Requirement.SKILL));
        }
        addDungeon(HiddenPathRules.requiredDungeon(path),
                HiddenPathReachability.Requirement.HIDDEN_DUNGEON, dungeonRoot, preconditions, skipped);
        // 技能书不是副本 id，而是某个副本 rewards.skill-book.id 的掉落；门的归属是那个副本。
        String book = HiddenPathRules.requiredSkillBook(path);
        String bookDungeon = dungeonDroppingSkillBook(book, dungeonRoot);
        if (bookDungeon == null) {
            skipped.add(dungeonRoot == null
                    ? "skill-book " + book + "（RpgDungeons 未安装）"
                    : "skill-book " + book);
        } else {
            addDungeon(bookDungeon, HiddenPathReachability.Requirement.SKILL_BOOK,
                    dungeonRoot, preconditions, skipped);
        }
        return preconditions;
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

    /**
     * 副本门在配置里是绝对有效等级，这里把它换算成「该等级所属转数 + 本地等级」，
     * 以便与技能门走同一条天梯比较。
     */
    private static void addDungeon(
            String id, HiddenPathReachability.Requirement requirement,
            ConfigurationSection dungeonRoot, List<HiddenPathReachability.Precondition> preconditions,
            List<String> skipped) {
        if (dungeonRoot == null) {
            skipped.add("dungeons." + id + "（RpgDungeons 未安装）");
            return;
        }
        ConfigurationSection dungeon = dungeonRoot.getConfigurationSection(id);
        if (dungeon == null) {
            skipped.add("dungeons." + id);
            return;
        }
        int absolute = dungeon.getInt("minimum-effective-level", 1);
        preconditions.add(new HiddenPathReachability.Precondition(
                id,
                HiddenPathReachability.ascensionOf(absolute),
                localOf(absolute),
                requirement));
    }

    private static int localOf(int effectiveLevel) {
        return effectiveLevel - HiddenPathReachability.ascensionOf(effectiveLevel)
                * ProgressionRules.LEVELS_PER_ASCENSION;
    }
}
