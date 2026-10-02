package cn.originalrpg.story;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StoryAsyncLifecycleContractTest {
    @Test
    void escortsRewardsAndDialogClicksBelongToOneAuthenticatedGeneration() throws Exception {
        Path root = repositoryRoot();
        String manager = Files.readString(root.resolve(
                "plugins/rpg-story/src/main/java/cn/originalrpg/story/EscortManager.java"), StandardCharsets.UTF_8);
        String command = Files.readString(root.resolve(
                "plugins/rpg-story/src/main/java/cn/originalrpg/story/StoryCommand.java"), StandardCharsets.UTF_8);

        assertTrue(manager.contains("new AuthenticatedSession(player, UUID.randomUUID())"));
        assertTrue(manager.contains("private final Map<UUID, RewardLease> rewardsInFlight"));
        assertTrue(manager.contains("rewardsInFlight.remove(playerId, lease)"));
        assertTrue(manager.contains("isAuthenticated(lease.player(), lease.sessionToken())"));
        assertTrue(manager.contains("catch (IllegalPluginAccessException | IllegalStateException ignored)"));
        assertTrue(command.contains("escorts.runAuthenticated(responder, sessionToken, action);"));
        assertTrue(manager.contains("if (isAuthenticated(player, sessionToken)) action.accept(player);"));
        assertFalse(command.contains("if (responder.isOnline()) action.accept(responder);"));
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
