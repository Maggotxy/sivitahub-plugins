package cn.originalrpg.guide;

import cn.originalrpg.guide.testing.DeploymentEnvironmentFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-plugin release gate for every code-owned player action exposed by Guide. */
final class GuidePlayerActionContractTest {
    private static final Pattern COMMAND_ACTION = Pattern.compile("\\\"command:([^\\\"]+)\\\"");

    @Test
    @Tag(DeploymentEnvironmentFixtures.TAG)
    void everyGuideCommandActionHasARegisteredOrdinaryPlayerEntry() throws IOException {
        Path root = repositoryRoot();
        Map<String, Boolean> commandAccess = registeredCommands(root);
        Set<String> actions = new HashSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("plugins/rpg-guide/src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                COMMAND_ACTION.matcher(source).results()
                        .map(match -> match.group(1).strip().split("\\s+", 2)[0])
                        .forEach(actions::add);
            }
        }

        assertFalse(actions.isEmpty());
        for (String command : actions) {
            assertTrue(commandAccess.containsKey(command),
                    "Guide exposes an unregistered command: /" + command);
            assertTrue(commandAccess.get(command),
                    "Guide exposes a command ordinary players cannot use: /" + command);
        }
    }

    @Test
    void everyGuideNpcTargetExistsInTheHubAndActionsAreNotDuplicated() throws IOException {
        Path root = repositoryRoot();
        String hubRoles = Files.readString(root.resolve(
                "plugins/rpg-hub/src/main/java/cn/originalrpg/hub/HubRole.java"),
                StandardCharsets.UTF_8);
        for (GuideNpcTarget target : GuideNpcTarget.ordered()) {
            assertTrue(hubRoles.contains("(\"" + target.id() + "\""),
                    "Guide NPC has no real HubRole: " + target.id());
            assertEquals(target.visitAction(), "command:" + target.navigationCommand().substring(1));
        }

        String guide = Files.readString(root.resolve(
                "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideCommand.java"),
                StandardCharsets.UTF_8);
        assertFalse(Pattern.compile("(\\\"command:[^\\\"]+\\\")\\s*,\\s*\\1")
                        .matcher(guide).find(),
                "A Guide card must not run the same action for left and right click");
    }

    @Test
    void gameplayHudHasOnlyTheLiveBetterHudModes() throws IOException {
        Path root = repositoryRoot();
        String guide = Files.readString(root.resolve(
                "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideCommand.java"),
                StandardCharsets.UTF_8);
        assertFalse(guide.contains("command:guidehud auto"));
        assertTrue(guide.contains("command:guidehud \" + id"));
        assertFalse(Files.exists(root.resolve(
                "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideSidebar.java")));

        Map<?, ?> plugin = yaml(root.resolve("plugins/rpg-guide/src/main/resources/plugin.yml"));
        Map<?, ?> guideHud = map(map(plugin, "commands"), "guidehud");
        assertEquals("/guidehud [on|off]", guideHud.get("usage"));
    }

    private static Map<String, Boolean> registeredCommands(Path root) throws IOException {
        Map<String, Boolean> commands = new HashMap<>();
        try (Stream<Path> files = Files.walk(root.resolve("plugins"))) {
            for (Path descriptor : files.filter(path -> path.endsWith("src/main/resources/plugin.yml"))
                    .toList()) {
                addCommands(yaml(descriptor), commands);
            }
        }
        try (Stream<Path> jars = Files.list(root.resolve("server/runtime/plugins"))) {
            for (Path jar : jars.filter(path -> path.getFileName().toString().endsWith(".jar")).toList()) {
                try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(jar.toFile())) {
                    var entry = archive.getEntry("plugin.yml");
                    if (entry == null) continue;
                    LoaderOptions options = new LoaderOptions();
                    options.setAllowDuplicateKeys(false);
                    try (var input = archive.getInputStream(entry)) {
                        Object value = new Yaml(new SafeConstructor(options)).load(input);
                        if (value instanceof Map<?, ?> plugin) addCommands(plugin, commands);
                    }
                }
            }
        }
        return Map.copyOf(commands);
    }

    private static void addCommands(Map<?, ?> plugin, Map<String, Boolean> commands) {
        Object rawCommands = plugin.get("commands");
        if (!(rawCommands instanceof Map<?, ?> commandMap)) return;
        Object rawPermissions = plugin.get("permissions");
        Map<?, ?> permissions = rawPermissions instanceof Map<?, ?> values ? values : Map.of();
        for (Map.Entry<?, ?> entry : commandMap.entrySet()) {
            boolean ordinary = true;
            if (entry.getValue() instanceof Map<?, ?> descriptor && descriptor.get("permission") != null) {
                Object permission = permissions.get(String.valueOf(descriptor.get("permission")));
                ordinary = permission instanceof Map<?, ?> details
                        && Boolean.TRUE.equals(details.get("default"));
            }
            commands.merge(String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT),
                    ordinary, Boolean::logicalOr);
        }
    }

    private static Map<?, ?> yaml(Path path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (var input = Files.newInputStream(path)) {
            Object value = new Yaml(new SafeConstructor(options)).load(input);
            assertNotNull(value, path.toString());
            return (Map<?, ?>) value;
        }
    }

    private static Map<?, ?> map(Map<?, ?> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, "Expected mapping at " + key);
        return (Map<?, ?>) value;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
