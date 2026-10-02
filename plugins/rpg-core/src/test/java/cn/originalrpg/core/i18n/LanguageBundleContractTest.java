package cn.originalrpg.core.i18n;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageBundleContractTest {
    private static final Pattern STRING_LITERAL = Pattern.compile("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");
    private static final Pattern MESSAGE_KEY = Pattern.compile("[a-z][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.?");

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

        for (String locale : List.of("zh_cn", "en_us")) {
            String translations = Files.readString(repository.resolve(
                    "resource-pack/assets/originalrpg/lang/" + locale + ".json"),
                    StandardCharsets.UTF_8);
            assertTrue(translations.contains("\"sivita.quick.quests\""),
                    "Missing Quick Actions task label in " + locale);
            assertTrue(translations.contains(locale.equals("zh_cn")
                            ? "\"sivita.quick.find\": \"当前目标导航\""
                            : "\"sivita.quick.find\": \"Navigate Current Objective\""),
                    "Current navigation must not promise walking when combat goals reopen the action card: "
                            + locale);
        }

        Map<?, ?> plugin = loadYaml(repository.resolve("plugins/rpg-guide/src/main/resources/plugin.yml"));
        assertTrue(((Map<?, ?>) plugin.get("commands")).containsKey("tasks"),
                "The trusted /tasks router must be registered by RpgGuide");
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
    void literalAndPrefixMessageReferencesResolveInBothLocales() throws IOException {
        Set<String> chineseKeys = flatten(loadBundle("zh_cn"));
        Set<String> englishKeys = flatten(loadBundle("en_us"));
        Path repository = findRepositoryRoot();
        List<String> missing = new ArrayList<>();

        try (Stream<Path> files = Files.walk(repository.resolve("plugins"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String argument : secondArguments(source, "messages.text(")) {
                    validateReferences(repository, file, argument, chineseKeys, englishKeys, missing);
                }
                for (String argument : secondArguments(source, "messages.lines(")) {
                    validateReferences(repository, file, argument, chineseKeys, englishKeys, missing);
                }
            }
        }

        assertTrue(missing.isEmpty(), "Unresolved language references:\n" + String.join("\n", missing));
    }

    @Test
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

    private static void validateReferences(
            Path repository,
            Path file,
            String argument,
            Set<String> chineseKeys,
            Set<String> englishKeys,
            List<String> missing
    ) {
        Matcher matcher = STRING_LITERAL.matcher(argument);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!MESSAGE_KEY.matcher(key).matches()) continue;
            boolean resolved = key.endsWith(".")
                    ? chineseKeys.stream().anyMatch(candidate -> candidate.startsWith(key))
                            && englishKeys.stream().anyMatch(candidate -> candidate.startsWith(key))
                    : chineseKeys.contains(key) && englishKeys.contains(key);
            if (!resolved) missing.add(repository.relativize(file) + " -> " + key);
        }
    }

    private static List<String> secondArguments(String source, String call) {
        List<String> arguments = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int callStart = source.indexOf(call, searchFrom);
            if (callStart < 0) return arguments;
            int openParenthesis = callStart + call.length() - 1;
            int argumentStart = openParenthesis + 1;
            int secondStart = -1;
            int nesting = 0;
            boolean inString = false;
            boolean inCharacter = false;
            boolean escaped = false;
            int index;
            for (index = argumentStart; index < source.length(); index++) {
                char character = source.charAt(index);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if ((inString || inCharacter) && character == '\\') {
                    escaped = true;
                    continue;
                }
                if (!inCharacter && character == '"') {
                    inString = !inString;
                    continue;
                }
                if (!inString && character == '\'') {
                    inCharacter = !inCharacter;
                    continue;
                }
                if (inString || inCharacter) continue;
                if (character == '(' || character == '[' || character == '{') {
                    nesting++;
                } else if (character == ')' || character == ']' || character == '}') {
                    if (character == ')' && nesting == 0) {
                        if (secondStart >= 0) arguments.add(source.substring(secondStart, index));
                        break;
                    }
                    nesting--;
                } else if (character == ',' && nesting == 0) {
                    if (secondStart < 0) {
                        secondStart = index + 1;
                    } else {
                        arguments.add(source.substring(secondStart, index));
                        break;
                    }
                }
            }
            searchFrom = Math.max(index + 1, callStart + call.length());
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

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertNotNull(current, "Cannot locate repository root from user.dir");
        return current;
    }
}
