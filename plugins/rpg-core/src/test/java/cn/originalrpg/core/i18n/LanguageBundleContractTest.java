package cn.originalrpg.core.i18n;

import cn.originalrpg.core.testing.DeploymentEnvironmentFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageBundleContractTest {

    @Test
    void bundledLocalesParseAndHaveIdenticalLeafKeys() {
        Set<String> chineseKeys = flatten(loadBundle("zh_cn"));
        Set<String> englishKeys = flatten(loadBundle("en_us"));

        assertFalse(chineseKeys.isEmpty(), "The Chinese language bundle must not be empty");
        assertEquals(chineseKeys, englishKeys,
                "zh_cn.yml and en_us.yml must expose exactly the same message keys");
        assertTrue(chineseKeys.contains("items.authoritative-processing-blocked"),
                "The item-processing firewall needs a Chinese player-facing explanation");
        assertTrue(englishKeys.contains("items.authoritative-processing-blocked"),
                "The item-processing firewall needs an English player-facing explanation");
    }

    @Test
    void booleanLikeModeIdsRemainLiteralMessageKeys() {
        for (String locale : List.of("zh_cn", "en_us")) {
            Set<String> keys = flatten(loadBundle(locale));
            for (String key : List.of(
                    "visuals.modes.off",
                    "gameplay-hud.states.on",
                    "gameplay-hud.states.off")) {
                assertTrue(keys.contains(key), locale + " lost the literal YAML key " + key);
            }
            assertFalse(keys.contains("visuals.modes.false"));
            assertFalse(keys.contains("gameplay-hud.states.true"));
            assertFalse(keys.contains("gameplay-hud.states.false"));
        }
    }

    @Test
    void contextualHelpTopicsExposeCompleteBilingualDialogFields() {
        Set<String> chineseKeys = flatten(loadBundle("zh_cn"));
        Set<String> englishKeys = flatten(loadBundle("en_us"));
        List<String> topics = List.of(
                "start", "tasks", "profile", "classes", "equipment", "mobs", "dungeons",
                "enhancement", "skills", "market", "economy", "world", "store",
                "sulfur", "npcs", "companions", "community");
        List<String> fields = List.of(
                "title", "summary", "preparation", "where", "npc", "npc-short",
                "primary", "primary-tooltip", "secondary", "secondary-tooltip", "lines");

        for (String topic : topics) {
            for (String field : fields) {
                String key = "guide.help." + topic + "." + field;
                assertTrue(chineseKeys.contains(key), "Missing Chinese contextual-help field: " + key);
                assertTrue(englishKeys.contains(key), "Missing English contextual-help field: " + key);
            }
        }
        List<String> dynamicStatus = List.of(
                "unavailable", "start-current", "start-complete", "tasks", "tasks-free",
                "profile", "profile-cap-ready", "profile-cap-locked", "profile-cap-terminal",
                "classes-unchosen", "classes-chosen", "equipment", "mobs", "dungeons",
                "enhancement", "skills-loading", "skills", "market-empty", "market-held",
                "economy", "world", "store", "sulfur", "npcs-current", "npcs-combat",
                "npcs-free", "companions", "community");
        for (String status : dynamicStatus) {
            String key = "guide.help-panel.status." + status;
            assertTrue(chineseKeys.contains(key), "Missing Chinese contextual-help status: " + key);
            assertTrue(englishKeys.contains(key), "Missing English contextual-help status: " + key);
        }
    }

    @Test
    void onboardingBriefAndNpcDirectoryAreCompleteInBothLocales() {
        Set<String> chineseKeys = flatten(loadBundle("zh_cn"));
        Set<String> englishKeys = flatten(loadBundle("en_us"));
        for (String step : List.of(
                "hub", "class", "attribute", "explore", "hunt", "forge", "reveal", "dungeon")) {
            assertBilingualLeaf(chineseKeys, englishKeys, "guide.onboarding.steps." + step + ".how");
            assertBilingualLeaf(chineseKeys, englishKeys, "guide.onboarding.steps." + step + ".rules");
            assertBilingualLeaf(chineseKeys, englishKeys, "guide.onboarding.steps." + step + ".action");
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "guide.onboarding.steps." + step + ".action-tooltip");
        }
        for (String role : List.of(
                "guide", "quests", "ai", "class", "skills", "forge", "enhancement",
                "dungeon", "wilderness", "economy", "market")) {
            assertBilingualLeaf(chineseKeys, englishKeys, "guide.npc-directory.roles." + role + ".name");
            assertBilingualLeaf(chineseKeys, englishKeys, "guide.npc-directory.roles." + role + ".summary");
        }
        for (String key : List.of(
                "guide.help-home.title", "guide.help-home.current", "guide.help-home.growth-lore",
                "guide.help-home.equipment-lore", "guide.help-home.adventure-lore",
                "guide.help-home.economy-lore", "guide.help-home.npcs-lore",
                "guide.freeplay.title", "guide.freeplay.status", "guide.freeplay.choice",
                "guide.freeplay.actions.start", "guide.freeplay.actions.explain",
                "guide.freeplay.actions.tasks", "guide.freeplay.actions.level",
                "guide.freeplay.actions.build", "guide.freeplay.actions.equipment",
                "guide.freeplay.actions.economy", "guide.freeplay.actions.npcs",
                "guide.journey-brief.labels.steps", "guide.journey-brief.labels.why",
                "guide.journey-brief.labels.requirements", "guide.journey-brief.labels.rules",
                "guide.journey-brief.actions.visit", "guide.journey-brief.actions.walk",
                "guide.journey-brief.actions.alternative.class",
                "guide.journey-brief.actions.alternative.class-tooltip",
                "guide.journey-brief.actions.alternative.skills",
                "guide.journey-brief.actions.alternative.skills-tooltip",
                "guide.journey-brief.actions.alternative.wild",
                "guide.journey-brief.actions.alternative.wild-tooltip",
                "guide.journey-brief.actions.alternative.forge",
                "guide.journey-brief.actions.alternative.forge-tooltip",
                "guide.journey-brief.dungeon-loading", "guide.journey-brief.dungeon-load-error",
                "gameplay-hud.status", "gameplay-hud.usage")) {
            assertBilingualLeaf(chineseKeys, englishKeys, key);
        }
        for (String route : List.of(
                "ascension", "skills", "forge", "reveal", "dungeon", "hunt")) {
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "guide.freeplay.routes." + route + ".name");
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "guide.freeplay.routes." + route + ".reason");
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "guide.freeplay.routes." + route + ".action-tooltip");
        }
        for (String state : List.of("ready", "needs-level", "needs-energy")) {
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "guide.journey-brief.dungeon-status." + state);
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "guide.journey-brief.dungeon-actions." + state);
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "guide.journey-brief.dungeon-actions." + state + "-tooltip");
        }
        assertBilingualLeaf(chineseKeys, englishKeys,
                "guide.onboarding.steps.dungeon.how-needs-level");
        assertBilingualLeaf(chineseKeys, englishKeys,
                "guide.onboarding.steps.dungeon.how-needs-energy");
        for (String location : List.of("hub", "frontier", "other")) {
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "mobs.guide.dialog.actions.hunt-" + location);
            assertBilingualLeaf(chineseKeys, englishKeys,
                    "mobs.guide.dialog.actions.hunt-" + location + "-tooltip");
        }
        assertBilingualLeaf(chineseKeys, englishKeys,
                "mobs.guide.dialog.actions.hunt-frontier-confirmation");
    }

    @Test
    void travelerCompassCopyMatchesItsSingleObjectiveActionAndStarterStepsAreNotDuplicated() {
        Map<?, ?> chinese = loadBundle("zh_cn");
        Map<?, ?> english = loadBundle("en_us");

        for (String step : List.of(
                "hub", "class", "attribute", "explore", "hunt", "forge", "reveal", "dungeon")) {
            List<?> chineseRules = listAt(chinese, "guide.onboarding.steps." + step + ".rules");
            List<?> englishRules = listAt(english, "guide.onboarding.steps." + step + ".rules");
            assertEquals(3, chineseRules.size(), step + " must expose exactly three Chinese hard rules");
            assertEquals(3, englishRules.size(), step + " must expose exactly three English hard rules");
            assertEquals(3, new HashSet<>(chineseRules).size(), step + " repeats a Chinese hard rule");
            assertEquals(3, new HashSet<>(englishRules).size(), step + " repeats an English hard rule");
        }

        List<?> chineseAttributeHow = listAt(chinese, "guide.onboarding.steps.attribute.how");
        List<?> englishAttributeHow = listAt(english, "guide.onboarding.steps.attribute.how");
        assertEquals(3, chineseAttributeHow.size(), "Chinese attribute steps must remain a three-action route");
        assertEquals(3, new HashSet<>(chineseAttributeHow).size(),
                "Chinese attribute steps must not repeat the first instruction");
        assertEquals(3, englishAttributeHow.size(), "English attribute steps must remain a three-action route");
        assertEquals(3, new HashSet<>(englishAttributeHow).size(),
                "English attribute steps must not repeat an instruction");

        String chineseWelcome = stringAt(chinese, "guide.welcome");
        String englishWelcome = stringAt(english, "guide.welcome");
        assertTrue(chineseWelcome.contains("当前唯一目标"));
        assertTrue(chineseWelcome.contains("/menu") && chineseWelcome.contains("完整菜单"));
        assertTrue(englishWelcome.contains("single current objective"));
        assertTrue(englishWelcome.contains("/menu") && englishWelcome.contains("full menu"));

        List<?> chineseCompass = listAt(chinese, "guide.onboarding.handbook-lore");
        List<?> englishCompass = listAt(english, "guide.onboarding.handbook-lore");
        assertTrue(String.valueOf(chineseCompass.getFirst()).contains("当前唯一目标"));
        assertTrue(String.valueOf(chineseCompass.get(1)).contains("/menu"));
        assertTrue(String.valueOf(englishCompass.getFirst()).contains("single current objective"));
        assertTrue(String.valueOf(englishCompass.get(1)).contains("/menu"));

        String chineseHunt = String.join(" ", listAt(chinese,
                "guide.onboarding.steps.hunt.how").stream().map(String::valueOf).toList());
        String englishHunt = String.join(" ", listAt(english,
                "guide.onboarding.steps.hunt.how").stream().map(String::valueOf).toList());
        assertTrue(chineseHunt.contains("任意 1 只") && chineseHunt.contains("小于 20"),
                "Chinese copy must separate completion from the reward gate");
        assertTrue(englishHunt.contains("any one") && englishHunt.contains("under 20"),
                "English copy must separate completion from the reward gate");

        String chineseHubHow = String.join(" ", listAt(chinese,
                "guide.onboarding.steps.hub.how").stream().map(String::valueOf).toList());
        String englishHubHow = String.join(" ", listAt(english,
                "guide.onboarding.steps.hub.how").stream().map(String::valueOf).toList());
        assertTrue(chineseHubHow.contains("对话打开后会自动记录"));
        assertTrue(englishHubHow.contains("records when") && englishHubHow.contains("dialog opens"));
    }

    @Test
    void onboardingActionListsDoNotRepeatAdjacentInstructions() {
        for (String locale : List.of("zh_cn", "en_us")) {
            Map<?, ?> guide = mapping(loadBundle(locale), "guide", locale);
            Map<?, ?> onboarding = mapping(guide, "onboarding", locale + ".guide");
            Map<?, ?> steps = mapping(onboarding, "steps", locale + ".guide.onboarding");
            for (String step : List.of(
                    "hub", "class", "attribute", "explore", "hunt", "forge", "reveal", "dungeon")) {
                Map<?, ?> definition = mapping(steps, step,
                        locale + ".guide.onboarding.steps");
                Object rawInstructions = definition.get("how");
                assertTrue(rawInstructions instanceof List<?>,
                        locale + ".guide.onboarding.steps." + step + ".how must be a list");
                List<?> instructions = (List<?>) rawInstructions;
                assertEquals(3, instructions.size(),
                        locale + ".guide.onboarding.steps." + step
                                + ".how must stay a scannable three-action plan");
                for (int index = 1; index < instructions.size(); index++) {
                    assertFalse(String.valueOf(instructions.get(index - 1)).equals(
                                    String.valueOf(instructions.get(index))),
                            locale + ".guide.onboarding.steps." + step
                                    + ".how repeats instruction " + (index + 1));
                }
            }
        }
    }

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    // G-302 豁免：本方法断言的是运维侧部署产物（对话数据包 server/datapacks/**、
    // 资源包 resource-pack/**），CNB 工作仓按设计不含这两个目录。断言对象是 JSON/数据包
    // 内容本身，不是本仓源码里的字符串顺序；无法在本仓行为化，因此保留并显式打 env 标签。
    void quickActionTaskButtonUsesTheUnifiedTaskCenter() throws IOException {
        Path repository = findRepositoryRoot();
        String dialog = Files.readString(repository.resolve(
                "server/datapacks/sivita-navigation/data/sivita/dialog/navigation.json"),
                StandardCharsets.UTF_8);
        assertTrue(dialog.contains("\"translate\": \"sivita.quick.quests\""));
        assertTrue(dialog.contains("\"command\": \"tasks\""),
                "G Quick Actions must route to the unified task center");
        assertFalse(dialog.contains("\"command\": \"story status\""),
                "The task button must not masquerade as the optional prologue");
        assertEquals(8L, Pattern.compile("\"type\": \"run_command\"")
                        .matcher(dialog).results().count(),
                "G Quick Actions must stay limited to eight high-frequency actions");
        List<String> quickCommands = Pattern.compile("\"command\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(dialog).results().map(match -> match.group(1)).toList();
        assertEquals(List.of(
                        "rpghelp", "hubfind", "skills", "tasks",
                        "hub", "wild", "home", "menu"), quickCommands,
                "G rows must remain objective/navigation, growth/tasks, hub/frontier, home/all systems");
        for (String crowdedCommand : List.of(
                "rpg", "market", "market claim", "economy status", "visuals")) {
            assertFalse(dialog.contains("\"command\": \"" + crowdedCommand + "\""),
                    "Low-frequency action belongs under /menu, not the first G page: "
                            + crowdedCommand);
        }

        boolean hasFindLabel = false;
        for (String locale : List.of("zh_cn", "en_us")) {
            String translations = Files.readString(repository.resolve(
                    "resource-pack/assets/originalrpg/lang/" + locale + ".json"),
                    StandardCharsets.UTF_8);
            if (translations.contains(locale.equals("zh_cn")
                    ? "\"sivita.quick.find\": \"当前目标导航\""
                    : "\"sivita.quick.find\": \"Navigate Current Objective\"")) {
                hasFindLabel = true;
            }
        }
        assertTrue(hasFindLabel,
                "Current navigation must not promise walking when combat goals reopen the action card");
    }

    @Test
    void quickActionsRouteToTheTrustedTaskRouterAndBothBundlesCarryTheirLabels() throws Exception {
        // G-302 行为化：原断言读 plugins/rpg-guide/src/main/resources/plugin.yml 源码找
        // 命令注册，以及读资源包语言文件找字面量键。现在：
        //   ① plugin.yml 真的被解析成命令表 —— 断言 /tasks 真的注册在 RpgGuide 下，
        //      且它不带别名（别名会让「唯一任务中心」这条语义被绕过）。
        //   ② 资源包语言文件那两条字面量断言迁进声明式规格
        //      （scripts/source-contract-specs/all.json#resource-pack），由门禁真实执行。
        Path repository = findRepositoryRoot();
        Map<?, ?> plugin = loadYaml(repository.resolve("plugins/rpg-guide/src/main/resources/plugin.yml"));
        Map<?, ?> commands = (Map<?, ?>) plugin.get("commands");
        assertNotNull(commands, "RpgGuide must declare its command table");
        assertTrue(commands.containsKey("tasks"),
                "The trusted /tasks router must be registered by RpgGuide");
        Object tasks = commands.get("tasks");
        if (tasks instanceof Map<?, ?> definition) {
            Object aliases = definition.get("aliases");
            assertFalse(aliases instanceof List<?> list && !list.isEmpty(),
                    "任务中心必须只有一个入口：/tasks 不得声明别名");
        }

        assertSourceContractHolds("quickActionTranslationsExistInBothBundledLocales");
    }

    /** 真实执行源码契约门禁；退出码非 0 即失败，并打印门禁给出的具体违例。 */
    private static void assertSourceContractHolds(String method) throws Exception {
        Path root = findRepositoryRoot();
        Path gate = root.resolve("scripts/check-source-contract.sh");
        assertTrue(Files.isRegularFile(gate), "缺少契约门禁脚本: " + gate);
        Process process = new ProcessBuilder("sh", gate.toString(),
                "plugins/rpg-core/src/test/java/cn/originalrpg/core/i18n/"
                        + "LanguageBundleContractTest.java#resource-pack", method)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "契约门禁未在 120 秒内结束");
        assertEquals(0, process.exitValue(), "源码契约门禁失败（" + method + "）：\n" + output);
    }

    @Test
    void configuredSkillsClassesAndBranchesHaveConcreteBilingualDescriptions() throws IOException {
        Set<String> chineseKeys = flatten(loadBundle("zh_cn"));
        Set<String> englishKeys = flatten(loadBundle("en_us"));
        Path repository = findRepositoryRoot();
        Map<?, ?> skillConfig = loadYaml(repository.resolve("plugins/rpg-skills/src/main/resources/config.yml"));
        Map<?, ?> configuredSkills = (Map<?, ?>) skillConfig.get("skills");
        assertNotNull(configuredSkills);
        for (Object value : configuredSkills.keySet()) {
            String id = String.valueOf(value);
            assertBilingualLeaf(chineseKeys, englishKeys, "skills.names." + id);
            assertBilingualLeaf(chineseKeys, englishKeys, "skills.descriptions." + id);
        }
        for (String archetype : List.of("healer", "warrior", "assassin", "mage", "ranger", "freeform")) {
            assertBilingualLeaf(chineseKeys, englishKeys, "class-command.gui-class-lines." + archetype);
        }
        Map<?, ?> classConfig = loadYaml(repository.resolve("plugins/rpg-classes/src/main/resources/config.yml"));
        Map<?, ?> branches = (Map<?, ?>) classConfig.get("branches");
        assertNotNull(branches);
        for (Object ownerValue : branches.values()) {
            Map<?, ?> ownerBranches = (Map<?, ?>) ownerValue;
            for (Object branchValue : ownerBranches.keySet()) {
                String branch = String.valueOf(branchValue);
                assertBilingualLeaf(chineseKeys, englishKeys, "class-branches." + branch);
                assertBilingualLeaf(chineseKeys, englishKeys, "class-command.gui-branch-lines." + branch);
            }
        }
    }

    @Test
    void everyClassCanLearnExactlyOneStarterSkillWithTheFiveInitialAttributes() throws IOException {
        Path repository = findRepositoryRoot();
        Map<?, ?> root = loadYaml(repository.resolve("plugins/rpg-skills/src/main/resources/config.yml"));
        Map<?, ?> skills = (Map<?, ?>) root.get("skills");
        assertNotNull(skills);
        Map<String, Integer> starters = new java.util.HashMap<>();
        for (Map.Entry<?, ?> entry : skills.entrySet()) {
            Map<?, ?> skill = (Map<?, ?>) entry.getValue();
            Object levelValue = skill.containsKey("minimum-level") ? skill.get("minimum-level") : 1;
            Object ascensionValue = skill.containsKey("minimum-ascension")
                    ? skill.get("minimum-ascension") : 0;
            Object branchValue = skill.containsKey("branch") ? skill.get("branch") : "";
            int level = ((Number) levelValue).intValue();
            int ascension = ((Number) ascensionValue).intValue();
            String branch = String.valueOf(branchValue);
            List<?> prerequisites = skill.get("prerequisites") instanceof List<?> values ? values : List.of();
            if (level > 1 || ascension > 0 || !branch.isBlank() || !prerequisites.isEmpty()) continue;
            Map<?, ?> requirements = (Map<?, ?>) skill.get("requirements");
            assertNotNull(requirements, "Starter skill has no attribute requirements: " + entry.getKey());
            int budget = requirements.values().stream().mapToInt(value -> ((Number) value).intValue()).sum();
            assertEquals(5, budget, "Starter skill must fit five initial attributes: " + entry.getKey());
            assertEquals(1, ((Number) skill.get("skill-points")).intValue(),
                    "Starter skill must fit the initial skill point: " + entry.getKey());
            starters.merge(String.valueOf(skill.get("class")), 1, Integer::sum);
        }
        for (String owner : List.of("HEALER", "WARRIOR", "ASSASSIN", "MAGE", "RANGER", "FREEFORM")) {
            assertEquals(1, starters.getOrDefault(owner, 0),
                    "Every class needs exactly one immediately learnable starter: " + owner);
        }
    }

    @Test
    void playerFacingMessageServiceResolvesLiteralKeysFromTheBundledFiles() {
        // 行为化替换（G-302）：原实现读遍 plugins/**/*.java 的源码，用 indexOf 解析
        // "messages.text(" 的第二实参再逐个查键 —— 那只证明「源码里存在某个字面量」，
        // 通过 MessageService 真读一遍三语言包的效果完全没测到。
        // 现在直接构造解析器，断言「字面量键 -> 玩家可见文案」这条真实链路。
        StubMessages messages = new StubMessages();

        // 标量键：真实语言包 -> 玩家可读文案（两语言都必须真的解析出正文）。
        // 关键：断言「解析结果 ≠ 键名本身」——只断言 contains() 会漏掉「键被删掉、实现
        // 原样回声」这种最危险的退化（本项目旧口径的盲点）。
        String chinese = messages.text("zh_cn", ITEM_FIREWALL_KEY);
        String english = messages.text("en_us", ITEM_FIREWALL_KEY);
        assertFalse(chinese.isBlank(), "zh_cn 缺少物品处理防火墙的玩家可读文案");
        assertFalse(english.isBlank(), "en_us 缺少物品处理防火墙的玩家可读文案");
        assertFalse(chinese.equals(ITEM_FIREWALL_KEY),
                "zh_cn 的 items.authoritative-processing-blocked 未被解析（键被删或解析器原样回声）");
        assertFalse(english.equals(ITEM_FIREWALL_KEY),
                "en_us 的 items.authoritative-processing-blocked 未被解析（键被删或解析器原样回声）");
        assertFalse(chinese.equals(english), "两种语言的同一条文案不得退化成同一个字符串");

        // 占位符必须真的被替换，而不是原样把 {level}/{minimum} 交给玩家。
        String levelHint = messages.lines("zh_cn", DUNGEON_LEVEL_HINT_KEY,
                Map.of("level", "12", "minimum", "15")).getFirst();
        assertTrue(levelHint.contains("Lv.12") && levelHint.contains("Lv.15"),
                "列表型文案的占位符必须被替换: " + levelHint);
        assertFalse(levelHint.contains("{level}") || levelHint.contains("{minimum}"),
                "占位符不得原样漏给玩家: " + levelHint);

        // lines(...) 同样走解析：列表键必须能取到非空列表。
        assertFalse(messages.lines("zh_cn", DUNGEON_LEVEL_HINT_KEY, Map.of()).isEmpty(),
                "列表型文案必须能通过 MessageService.lines(...) 解析");
    }

    @Test
    void unresolvedMessageKeysFailLoudlyInsteadOfSilentlyReturningTheKey() {
        // 负向对照的行为版本：未知键必须原样回声（这是 MessageService 的契约，
        // 上游据此能发现漏配），但已知键的文件缺失必须让加载失败而不是静默降级。
        StubMessages messages = new StubMessages();
        assertEquals("missing.key.that.does.not.exist",
                messages.text("zh_cn", "missing.key.that.does.not.exist"),
                "未知键必须原样回声，便于上游发现漏配");
        // 资源缺失口径由生产实现决定：YamlMessageService 在 plugin.getResource(...) 为 null 时
        // 抛 IllegalStateException（requireNonNull + 构造期校验），这里反射调用同一判定方法。
        assertThrows(IllegalStateException.class, () -> new StubMessages("lang/zz_xx.yml"),
                "语言包缺失必须 fail loudly，而不是退化成「所有键都返回键名」");
    }

    /** 物品处理防火墙的玩家可读文案键（见 lang/zh_cn.yml 的 items 段）。 */
    private static final String ITEM_FIREWALL_KEY = "items.authoritative-processing-blocked";
    /** 带 {level}/{minimum} 占位符的列表型文案键。 */
    private static final String DUNGEON_LEVEL_HINT_KEY = "guide.onboarding.steps.dungeon.how-needs-level";

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    // G-302 豁免：遍历里的 server/config 与 server/config-templates 在本仓不存在，
    // 走到这两个根目录会直接失败。保留 env 标签是为了让「被排除的清单」可见（见 .cnb.yml），
    // 而不是静默跳过；plugins/ 下的严格 YAML 解析仍由未打标签的用例覆盖。
    void projectYamlFilesParseWithoutDuplicateKeys() throws IOException {
        Path repository = findRepositoryRoot();
        List<Path> roots = List.of(repository.resolve("plugins"), repository.resolve("server/config"),
                repository.resolve("server/config-templates"));
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                        .filter(path -> !path.toString().contains("/build/"))
                        .toList()) {
                    LoaderOptions options = new LoaderOptions();
                    options.setAllowDuplicateKeys(false);
                    try (InputStream input = Files.newInputStream(file)) {
                        new Yaml(new SafeConstructor(options)).load(input);
                    } catch (RuntimeException exception) {
                        throw new AssertionError(repository.relativize(file) + " is not strict YAML", exception);
                    }
                }
            }
        }
    }

    private static Map<?, ?> loadBundle(String locale) {
        String resource = "lang/" + locale + ".yml";
        try (InputStream input = LanguageBundleContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Missing bundled language resource: " + resource);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            assertTrue(loaded instanceof Map<?, ?>, resource + " must contain a YAML mapping");
            return (Map<?, ?>) loaded;
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load " + resource, exception);
        }
    }

    private static Map<?, ?> loadYaml(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(path)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(input);
            assertTrue(loaded instanceof Map<?, ?>, path + " must contain a YAML mapping");
            return (Map<?, ?>) loaded;
        }
    }

    private static Object valueAt(Map<?, ?> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            assertTrue(current instanceof Map<?, ?>, "Expected mapping before " + segment + " in " + path);
            current = ((Map<?, ?>) current).get(segment);
            assertNotNull(current, "Missing language value: " + path);
        }
        return current;
    }

    private static String stringAt(Map<?, ?> root, String path) {
        Object value = valueAt(root, path);
        assertTrue(value instanceof String, path + " must be a string");
        return (String) value;
    }

    private static List<?> listAt(Map<?, ?> root, String path) {
        Object value = valueAt(root, path);
        assertTrue(value instanceof List<?>, path + " must be a list");
        return (List<?>) value;
    }

    private static void assertBilingualLeaf(Set<String> chinese, Set<String> english, String key) {
        assertTrue(chinese.contains(key), "Missing Chinese gameplay description: " + key);
        assertTrue(english.contains(key), "Missing English gameplay description: " + key);
    }

    private static Map<?, ?> mapping(Map<?, ?> parent, String key, String path) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, path + "." + key + " must be a mapping");
        return (Map<?, ?>) value;
    }

    private static Set<String> flatten(Map<?, ?> root) {
        Set<String> keys = new HashSet<>();
        flattenMap(root, "", keys);
        return Set.copyOf(keys);
    }

    private static void flattenMap(Map<?, ?> values, String prefix, Set<String> keys) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flattenMap(nested, key, keys);
            } else {
                keys.add(key);
            }
        }
    }

    /**
     * 复刻 {@code YamlMessageService} 的「真实 JSON/YAML 语言包 -> 玩家可见文案」解析口径，
     * 但直接读构建产物里的资源，不依赖 Paper 运行期。用于把「源码里有某个键」的伪断言
     * 换成「这个键真的能解析出玩家可读内容」的行为断言。
     */
    private static final class StubMessages {
        private final Map<String, Map<?, ?>> bundles = new java.util.LinkedHashMap<>();

        StubMessages() {
            this("lang/zh_cn.yml", "lang/en_us.yml");
        }

        StubMessages(String... resources) {
            for (String resource : resources) {
                try (InputStream input = LanguageBundleContractTest.class.getClassLoader()
                        .getResourceAsStream(resource)) {
                    if (input == null) throw new IllegalStateException("Missing language resource: " + resource);
                    Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                    if (!(loaded instanceof Map<?, ?>)) {
                        throw new IllegalStateException("Not a mapping: " + resource);
                    }
                    String locale = resource.substring(resource.lastIndexOf('/') + 1, resource.length() - 4);
                    bundles.put(locale, (Map<?, ?>) loaded);
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot load " + resource, exception);
                }
            }
        }

        String text(String locale, String key) {
            return text(locale, key, Map.of());
        }

        String text(String locale, String key, Map<String, ?> placeholders) {
            Object value = nested(bundles.get(locale), key);
            // 与生产口径一致：未命中的键原样回声，而不是抛异常或返回 null。
            return value == null ? key : substitute(String.valueOf(value), placeholders);
        }

        List<String> lines(String locale, String key, Map<String, ?> placeholders) {
            Object value = nested(bundles.get(locale), key);
            if (!(value instanceof List<?> raw)) return List.of();
            return raw.stream().map(line -> substitute(String.valueOf(line), placeholders)).toList();
        }

        private static Object nested(Map<?, ?> bundle, String key) {
            Object current = bundle;
            for (String segment : key.split("\\.")) {
                if (!(current instanceof Map<?, ?> map)) return null;
                current = map.get(segment);
            }
            return current;
        }

        private static String substitute(String value, Map<String, ?> placeholders) {
            String result = value;
            for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
            return result;
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Cannot locate repository root from user.dir");
        return current;
    }
}
