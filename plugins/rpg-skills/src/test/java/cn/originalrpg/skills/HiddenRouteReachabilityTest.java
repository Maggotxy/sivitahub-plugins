package cn.originalrpg.skills;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.rules.HiddenPathPreconditionPlan;
import cn.originalrpg.api.rules.HiddenPathReachability;
import cn.originalrpg.api.rules.HiddenPathRules;
import cn.originalrpg.api.rules.ProgressionRules;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隐藏路线可达性回归（G-106）：把本模块真实 config.yml 的 {@code skills} 段与
 * rpg-dungeons 真实 config.yml 的 {@code dungeons} 段装配成前置集合，真的跑一遍
 * {@link HiddenRouteReachability#inspect} 并断言它对每条路线给出的结论。
 *
 * <p>判定的输入集合取自 {@link HiddenPathPreconditionPlan}（生产代码），不是本测试
 * 手写的清单 —— 否则新增一条前置而门禁没跟上，就会漏掉下一例 G-106。
 */
class HiddenRouteReachabilityTest {
    private static final Path DUNGEONS_CONFIG =
            Path.of("..", "rpg-dungeons", "src", "main", "resources", "config.yml");

    private static ConfigurationSection skillsRoot() {
        try (InputStream input = HiddenRouteReachabilityTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertTrue(input != null, "rpg-skills config.yml 必须在测试 classpath 上");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            ConfigurationSection skills = yaml.getConfigurationSection("skills");
            assertTrue(skills != null, "config.yml 必须有 skills 段");
            return skills;
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) {
            throw new AssertionError(error);
        }
    }

    private static ConfigurationSection dungeonsRoot() {
        File file = DUNGEONS_CONFIG.toFile();
        assertTrue(file.isFile(), "rpg-dungeons config.yml 必须存在于 " + file.getAbsolutePath());
        ConfigurationSection dungeons = YamlConfiguration.loadConfiguration(file)
                .getConfigurationSection("dungeons");
        assertTrue(dungeons != null, "rpg-dungeons config.yml 必须有 dungeons 段");
        return dungeons;
    }

    /**
     * 任务要求的那条断言：对每个隐藏路径，逐条核对它的全部前置条件是否都能在该路径
     * 自身要求的转数下满足；不能的就是具名的不可达前置。
     *
     * <p>逐条前置核对而不是只看一个总布尔值：任一条前置越界都会指名道姓地报出来，
     * 且一次覆盖所有路线与所有前置类型（技能 / 隐藏副本 / 技能书）。
     *
     * <p>断言的是「核对是完备且正确的」，不是「shipped 一定可达」：shipped 配置当前
     * 确实带 G-106（血契前置 vanished-horizon 是 3 转），这里必须把它抓出来。
     */
    @Test
    void everyHiddenPathPreconditionIsAuditedAtThePathsOwnAscension() {
        ConfigurationSection skills = skillsRoot();
        ConfigurationSection dungeons = dungeonsRoot();
        List<String> unreachable = new ArrayList<>();
        int checked = 0;
        for (HiddenPath path : HiddenPath.values()) {
            int routeAscension = HiddenPathRules.minimumAscension(path);
            int ceiling = ProgressionRules.effectiveLevel(routeAscension, ProgressionRules.LOCAL_LEVEL_CAP);
            for (HiddenPathPreconditionPlan.Slot slot : HiddenPathPreconditionPlan.slots(path)) {
                int gate = absoluteGate(slot, skills, dungeons);
                checked++;
                if (gate > ceiling) {
                    unreachable.add(path + " 要求 " + routeAscension + " 转（有效等级上限 " + ceiling
                            + "），但其 " + slot.requirement() + " 前置 " + slot.slot()
                            + " 到有效等级 " + gate + " 才开放");
                }
            }
            assertTrue(ceiling > 0, path + " 的路线门必须为正");
        }
        assertEquals(HiddenPath.values().length * 3, checked,
                "每条路线必须被逐条核对三类前置（技能/隐藏副本/技能书）：" + checked);
        // shipped 配置的 G-106：只允许这一个前置不可达，且必须被具名。
        assertEquals(List.of("BLOODPACT 要求 2 转（有效等级上限 90），但其 SKILL 前置 "
                        + "vanished-horizon 到有效等级 110 才开放"),
                unreachable, "不可达前置集合变了：修好 G-106 后本断言应改为空集");
    }

    /** 门禁结论与逐条核对必须一致：真实配置当前被判为不可达（G-106 未修）。 */
    @Test
    void shippedConfigIsReportedUnreachableUntilTheCrossModuleFixLands() {
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skillsRoot(), dungeonsRoot());
        assertFalse(report.reachable(), "G-106 未修前必须被判为不可达，而不是静默通过");
        assertTrue(report.skipped().isEmpty(), "真实配置下不应有跳过的前置：" + report.skipped());
        assertEquals(Set.of(HiddenPath.BLOODPACT), Set.copyOf(report.contradictions().keySet()));
        assertEquals(Set.of(HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT)),
                report.contradictions().get(HiddenPath.BLOODPACT)
                        .get(HiddenPathReachability.Requirement.SKILL));
    }

    /** G-106 的跨模块修法：技能门与它挂的分支 stage 一起降到 2，即无不可达前置。 */
    @Test
    void loweringTheSkillGateTogetherWithItsBranchStageMakesEveryRouteReachable() {
        ConfigurationSection skills = skillsRoot();
        skills.set(HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT) + ".minimum-ascension", 2);
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skills, dungeonsRoot());
        assertTrue(report.reachable(), report.describe());
    }

    /** 判定必须真的读了配置，而不是恒返回可达：把技能门抬到 3 转即被具名报出。 */
    @Test
    void raisingTheBloodpactSkillGateToOneAscensionLaterIsDetectedAndNamed() {
        ConfigurationSection skills = skillsRoot();
        skills.set(HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT) + ".minimum-ascension", 3);
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skills, dungeonsRoot());
        assertFalse(report.reachable());
        String description = report.describe();
        assertTrue(description.contains("BLOODPACT"), description);
        assertTrue(description.contains(HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT)), description);
        assertEquals(Set.of("vanished-horizon"),
                report.contradictions().get(HiddenPath.BLOODPACT)
                        .get(HiddenPathReachability.Requirement.SKILL));
    }

    /**
     * 路线要求的技能必须真实存在于技能目录里，且它的门不会比路线门更晚 ——
     * 这就是 G-106 的判据。当前血契失败于此，断言把这个失败固定住。
     */
    @Test
    void requiredSkillsExistAndTheirGatesAreAuditedAgainstTheRouteGate() {
        ConfigurationSection skills = skillsRoot();
        for (HiddenPath path : HiddenPath.values()) {
            String skillId = HiddenPathRules.requiredSkill(path);
            ConfigurationSection skill = skills.getConfigurationSection(skillId);
            assertTrue(skill != null, path + " 要求的技能必须在 config.yml 里：" + skillId);
            int routeGate = ProgressionRules.effectiveLevel(
                    HiddenPathRules.minimumAscension(path), skill.getInt("minimum-level", 1));
            int skillGate = ProgressionRules.effectiveLevel(
                    skill.getInt("minimum-ascension", 0), skill.getInt("minimum-level", 1));
            if (path == HiddenPath.TALISMAN) {
                assertTrue(skillGate <= routeGate,
                        skillId + " 必须不晚于路线门开放：" + skillGate + " vs " + routeGate);
            } else {
                assertTrue(skillGate > routeGate,
                        "G-106 已被修好，请把本分支改为断言可达并把 fail-on-contradiction 置 true");
                assertEquals(3, skill.getInt("minimum-ascension", -1), skillId);
            }
        }
    }

    /** 符箓路线的三类前置在同一装配里全部可达（血契是唯一被门禁针对的路线）。 */
    @Test
    void talismanRouteIsReachableAgainstRealConfig() {
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skillsRoot(), dungeonsRoot());
        assertFalse(report.contradictions().containsKey(HiddenPath.TALISMAN));
    }

    /** RpgDungeons 是 softdepend：未安装时技能门仍要判定，其余前置记录为跳过且不误判。 */
    @Test
    void absentDungeonPluginIsSkippedNotFatal() {
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skillsRoot(), null);
        assertFalse(report.skipped().isEmpty());
        assertTrue(report.skipped().stream().anyMatch(entry -> entry.contains("storm-vault-hidden")),
                report.skipped().toString());
        assertTrue(report.skipped().stream().anyMatch(entry -> entry.contains("silent-thunder")),
                report.skipped().toString());
        // 副本缺失只跳过副本类前置；技能门仍被判定 —— 因此结论与装齐副本时一致（G-106）。
        assertEquals(Set.of(HiddenPath.BLOODPACT), Set.copyOf(report.contradictions().keySet()));
        assertTrue(report.contradictions().get(HiddenPath.BLOODPACT)
                .containsKey(HiddenPathReachability.Requirement.SKILL), report.describe());
        assertFalse(report.contradictions().containsKey(HiddenPath.TALISMAN));
    }

    /** 技能书的门归属掉落它的副本，而不是技能书自己：改了那个副本的门就该被检出。 */
    @Test
    void skillBookGateFollowsTheDungeonThatDropsTheBook() {
        ConfigurationSection dungeons = dungeonsRoot();
        dungeons.set("storm-vault-hidden.minimum-effective-level", 120);
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skillsRoot(), dungeons);
        assertFalse(report.reachable());
        assertEquals(Set.of("storm-vault-hidden"),
                report.contradictions().get(HiddenPath.BLOODPACT)
                        .get(HiddenPathReachability.Requirement.SKILL_BOOK));
    }

    /** 前置清单必须覆盖路线的全部三类前置，否则门禁会漏检。 */
    @Test
    void preconditionPlanCoversEveryRouteAndPreconditionKind() {
        for (HiddenPath path : HiddenPath.values()) {
            List<HiddenPathPreconditionPlan.Slot> slots = HiddenPathPreconditionPlan.slots(path);
            assertEquals(3, slots.size(), path + " 必须声明三类前置");
            Map<HiddenPathReachability.Requirement, String> byRequirement =
                    new EnumMap<>(HiddenPathReachability.Requirement.class);
            slots.forEach(slot -> byRequirement.put(slot.requirement(), slot.slot()));
            assertEquals(HiddenPathRules.requiredSkill(path),
                    byRequirement.get(HiddenPathReachability.Requirement.SKILL));
            assertEquals(HiddenPathRules.requiredDungeon(path),
                    byRequirement.get(HiddenPathReachability.Requirement.HIDDEN_DUNGEON));
            assertEquals(HiddenPathRules.requiredSkillBook(path),
                    byRequirement.get(HiddenPathReachability.Requirement.SKILL_BOOK));
        }
    }

    /** 清单是防御性拷贝：调用方改不动它，避免测试与判定看不同的集合。 */
    @Test
    void preconditionPlanIsImmutable() {
        List<HiddenPathPreconditionPlan.Slot> slots = HiddenPathPreconditionPlan.slots(HiddenPath.BLOODPACT);
        assertThrows(UnsupportedOperationException.class,
                () -> slots.add(new HiddenPathPreconditionPlan.Slot(
                        "x", "x", HiddenPathPreconditionPlan.Source.SKILL,
                        HiddenPathReachability.Requirement.SKILL)));
    }

    /**
     * 开关与结论必须自洽：fail-on-contradiction=true 只有在真实配置无不可达前置时才允许。
     * 这里真的读一遍 config.yml 的开关值，再用同一份真实配置跑一次判定 —— 两者一旦
     * 不一致（开了 fail-fast 但配置有矛盾 = 服务器起不来；没开 fail-fast 但配置已修好
     * = 门禁形同虚设），本测试就红。
     */
    @Test
    void shippedFailFastSwitchAgreesWithTheRealConfigVerdict() {
        boolean enabled;
        boolean failFast;
        try (InputStream input = HiddenRouteReachabilityTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertTrue(input != null);
            YamlConfiguration root = new YamlConfiguration();
            root.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            enabled = root.getBoolean("hidden-route-reachability.enabled", false);
            failFast = root.getBoolean("hidden-route-reachability.fail-on-contradiction", false);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException error) {
            throw new AssertionError(error);
        }
        assertTrue(enabled, "可达性自检必须开启");
        boolean reachable = HiddenRouteReachability.inspect(skillsRoot(), dungeonsRoot()).reachable();
        assertEquals(reachable, failFast,
                reachable
                        ? "配置已可达，请把 fail-on-contradiction 置 true，让同类矛盾今后直接挡住启动"
                        : "配置仍带 G-106，fail-on-contradiction 必须保持 false，否则服务器无法启动");
    }

    /**
     * fail-fast 的判据就是「矛盾非空」：同一份真实配置，修掉那一条前置后必须变成可达，
     * 加回来必须又变不可达。门禁不是恒真也不是恒假，它真的跟着配置走。
     */
    @Test
    void reachabilityVerdictTracksTheSkillGate() {
        ConfigurationSection skills = skillsRoot();
        ConfigurationSection dungeons = dungeonsRoot();
        String skillId = HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT);

        assertFalse(HiddenRouteReachability.inspect(skills, dungeons).reachable(),
                "shipped 配置带 G-106，必须被判不可达");

        skills.set(skillId + ".minimum-ascension", 2);
        HiddenRouteReachability.Report fixed = HiddenRouteReachability.inspect(skills, dungeons);
        assertTrue(fixed.reachable(), fixed.describe());

        skills.set(skillId + ".minimum-ascension", 3);
        HiddenRouteReachability.Report broken = HiddenRouteReachability.inspect(skills, dungeons);
        assertFalse(broken.reachable(), "矛盾必须被检出，否则 fail-fast 是空门禁");
        assertTrue(broken.describe().contains(skillId), broken.describe());
    }

    /** 装配侧取到的绝对有效等级门，与配置里写的键一致。 */
    private static int absoluteGate(HiddenPathPreconditionPlan.Slot slot,
                                    ConfigurationSection skills, ConfigurationSection dungeons) {
        if (slot.source() == HiddenPathPreconditionPlan.Source.SKILL) {
            ConfigurationSection skill = skills.getConfigurationSection(slot.lookupKey());
            assertTrue(skill != null, "skills." + slot.lookupKey());
            return ProgressionRules.effectiveLevel(
                    skill.getInt("minimum-ascension", 0), skill.getInt("minimum-level", 1));
        }
        String dungeonId = slot.lookupKey();
        if (slot.requirement() == HiddenPathReachability.Requirement.SKILL_BOOK) {
            dungeonId = null;
            for (String id : dungeons.getKeys(false)) {
                if (slot.lookupKey().equals(dungeons.getString(id + ".rewards.skill-book.id", ""))) {
                    dungeonId = id;
                    break;
                }
            }
            assertTrue(dungeonId != null, "技能书必须由某个副本掉落：" + slot.lookupKey());
        }
        ConfigurationSection dungeon = dungeons.getConfigurationSection(dungeonId);
        assertTrue(dungeon != null, "dungeons." + dungeonId);
        return dungeon.getInt("minimum-effective-level", 1);
    }
}
