package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MobGuideAsyncLifecycleContractTest {
    @Test
    void profileLoadsAndDialogClicksBelongToOneAuthenticatedGeneration() throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/MobGuideCommand.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("new AuthenticatedSession(event.player(), UUID.randomUUID())"));
        assertTrue(source.contains("if (!isAuthenticated(player, sessionToken)) return;"));
        assertTrue(source.contains("if (!isAuthenticated(responder, sessionToken)) return;"));
        assertTrue(source.contains("if (isAuthenticated(responder, sessionToken)) responder.sendMessage"));
        assertTrue(source.contains("catch (IllegalPluginAccessException | IllegalStateException ignored)"));
        assertFalse(source.contains("if (!player.isOnline()) return;"));
        assertFalse(source.contains("if (responder.isOnline())"));
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
