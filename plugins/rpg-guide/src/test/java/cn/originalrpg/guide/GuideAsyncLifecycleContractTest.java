package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuideAsyncLifecycleContractTest {
    @Test
    void guideUiAndStarterCallbacksRejectOldConnectionsAndShutdown() throws Exception {
        String command = source("GuideCommand.java");
        assertTrue(command.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertSafeSync(command);

        String tracker = source("GuideTracker.java");
        assertTrue(tracker.contains("profiles.get(player.getUniqueId()).whenComplete((profile, error) -> sync("));
        assertTrue(tracker.contains(".whenComplete((result, error) -> sync("));
        assertSafeSync(tracker);

        String welcome = source("WelcomeExperience.java");
        assertTrue(welcome.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertTrue(welcome.contains("if (ready(responder))"));
        assertSafeSync(welcome);

        String starter = source("StarterKitService.java");
        assertTrue(starter.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertTrue(starter.contains("if (currentSession(responder)"));
        assertSafeSync(starter);
    }

    private static void assertSafeSync(String source) {
        assertTrue(source.contains("if (!plugin.isEnabled()) return;"));
        assertTrue(source.contains("if (plugin.isEnabled()) action.run();"));
        assertTrue(source.contains("catch (IllegalStateException ignored)"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(repositoryRoot().resolve(
                "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/" + name),
                StandardCharsets.UTF_8);
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
