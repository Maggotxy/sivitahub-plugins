package cn.originalrpg.enhancement;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MutationAsyncLifecycleContractTest {
    @Test
    void everyMutationCallbackUsesPluginAndExactLoginGuards() throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-enhancement/src/main/java/cn/originalrpg/enhancement/MutationCommand.java"));
        assertTrue(source.contains("skills.skill(player.getUniqueId(), skill).whenComplete((progress, lookupError) ->\n                sync("));
        assertTrue(source.contains("skills.settleMutation(request).whenComplete((result, error) ->\n                sync("));
        assertTrue(source.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertTrue(source.contains("if (!plugin.isEnabled()) return"));
        assertTrue(source.contains("if (plugin.isEnabled()) action.run()"));
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
