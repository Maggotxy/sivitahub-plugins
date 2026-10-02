package cn.originalrpg.skills;

import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.rules.HiddenPathReachability;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G-106 行为级回归（rpg-skills 侧）：把本模块真实 config.yml 的 {@code skills} 段
 * 与 rpg-dungeons 真实 config.yml 的 {@code dungeons} 段装配成前置集合，
 * 真的跑一遍 {@link HiddenRouteReachability#inspect} 并断言结果。
 *
 * <p>同时用真实技能配置反向构造 G-106 形态（把 3 转技能当成 2 转路线的前置），
 * 断言规则层会具名报出该矛盾 —— 这一类断言一次性防住所有「前置比路线更晚开放」。
 */
class HiddenRouteReachabilityTest {
    private static final Path DUNGEONS_CONFIG =
            Path.of("..", "rpg-dungeons", "src", "main", "resources", "config.yml");

    private static ConfigurationSection skillsRoot() {
        try (InputStream input = HiddenRouteReachabilityTest.class.getClassLoader()
                .getResourceAsStream("config.yml")) {
            assertTrue(input != null, "rpg-skills config.yml 必须在测试 classpath 上");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            ConfigurationSection skills = yaml.getConfigurationSection("skills");
            assertTrue(skills != null, "config.yml 必须有 skills 段");
            return skills;
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException error) {
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

    @Test
    void shippedConfigContradictionIsDetectedAndNamed() {
        // G-106 未裁决前，真实配置的矛盾必须被检测到并具名 —— 这是本任务要求的
        // 「一次防住所有同类配置矛盾」的行为级断言。
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skillsRoot(), dungeonsRoot());
        assertFalse(report.reachable());
        assertTrue(report.skipped().isEmpty(), "真实配置下不应有跳过的前置：" + report.skipped());
        assertEquals(Set.of(HiddenPath.BLOODPACT), Set.copyOf(report.contradictions().keySet()));
        assertEquals(Set.of("vanished-horizon"),
                report.contradictions().get(HiddenPath.BLOODPACT)
                        .get(HiddenPathReachability.Requirement.SKILL));
        String description = report.describe();
        assertTrue(description.contains("vanished-horizon"), description);
        assertTrue(description.contains("BLOODPACT"), description);
    }

    @Test
    void talismanRouteIsReachableAgainstRealConfig() {
        // 符箓路线的前置全部在 2 转可达：同一次装配里只有血契被判不可达。
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skillsRoot(), dungeonsRoot());
        assertFalse(report.contradictions().containsKey(HiddenPath.TALISMAN));
    }

    @Test
    void realConfigReadsVanishedHorizonAtStageThree() {
        // 矛盾的两侧机械化取证：路线规则门 = 2（docs/GAME_DESIGN.md:106「二转」），
        // 而配置里 vanished-horizon 的 minimum-ascension = 3（挂 stage-3 的 unseen 分支）。
        ConfigurationSection skill = skillsRoot().getConfigurationSection("vanished-horizon");
        assertTrue(skill != null);
        assertEquals(3, skill.getInt("minimum-ascension", 0));
        assertEquals("unseen", skill.getString("branch", ""));
        assertEquals(20, skill.getInt("minimum-level", 1));
        assertEquals(2, cn.originalrpg.api.rules.HiddenPathRules.minimumAscension(HiddenPath.BLOODPACT));
    }

    @Test
    void absentDungeonPluginIsSkippedNotFatal() {
        // RpgDungeons 是 softdepend：未安装时技能门仍要判定，其余前置记录为跳过。
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skillsRoot(), null);
        assertFalse(report.skipped().isEmpty());
        assertTrue(report.skipped().stream().anyMatch(entry -> entry.contains("storm-vault-hidden")),
                report.skipped().toString());
        // 副本缺失只跳过对应前置，技能门仍被判定：BLOODPACT 仍是 SKILL 矛盾。
        assertEquals(Set.of("vanished-horizon"),
                report.contradictions().get(HiddenPath.BLOODPACT)
                        .get(HiddenPathReachability.Requirement.SKILL));
        assertFalse(report.contradictions().containsKey(HiddenPath.TALISMAN));
    }

    @Test
    void bloodpactSkillOpensLaterThanItsRouteGate() {
        // 直接断言矛盾：技能门（3）严格晚于路线门（2）⇒ 永久不可达。
        ConfigurationSection skills = skillsRoot();
        ConfigurationSection skill = skills.getConfigurationSection(
                cn.originalrpg.api.rules.HiddenPathRules.requiredSkill(HiddenPath.BLOODPACT));
        assertTrue(skill != null);
        assertTrue(skill.getInt("minimum-ascension", 0)
                > cn.originalrpg.api.rules.HiddenPathRules.minimumAscension(HiddenPath.BLOODPACT));
    }

    @Test
    void repairedSkillGateWouldMakeTheRouteReachable() {
        // 若把技能门降到与路线一致（判定的“配置错”修法），同一装配即无矛盾。
        ConfigurationSection skills = skillsRoot();
        skills.set("vanished-horizon.minimum-ascension", 2);
        HiddenRouteReachability.Report report = HiddenRouteReachability.inspect(skills, dungeonsRoot());
        assertTrue(report.reachable(), report.describe());
    }

    @Test
    void gateRejectsTheRouteWhenItsRealSkillOpensLaterThanTheRoute() {
        // 反向验证：真实技能 vanished-horizon 是 3 转。把它当成符箓（门 = 2）的前置，
        // 就是 G-106 的形态，inspect/规则层必须报出矛盾并具名到技能。
        ConfigurationSection skill = skillsRoot().getConfigurationSection("vanished-horizon");
        assertTrue(skill != null);
        Map<HiddenPathReachability.Requirement, Set<String>> details =
                HiddenPathReachability.unattainableDetails(HiddenPath.TALISMAN,
                        java.util.List.of(new HiddenPathReachability.Precondition(
                                "vanished-horizon",
                                skill.getInt("minimum-ascension", 0),
                                skill.getInt("minimum-level", 1),
                                HiddenPathReachability.Requirement.SKILL)));
        assertFalse(details.isEmpty());
        assertEquals(Set.of("vanished-horizon"),
                details.get(HiddenPathReachability.Requirement.SKILL));
        String description = HiddenPathReachability.describe(HiddenPath.TALISMAN, details);
        assertTrue(description.contains("vanished-horizon"), description);
        assertTrue(description.contains("TALISMAN"), description);
    }

}
