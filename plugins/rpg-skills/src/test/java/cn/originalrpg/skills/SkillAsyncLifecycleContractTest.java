package cn.originalrpg.skills;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkillAsyncLifecycleContractTest {
    @Test
    void skillCallbacksAndPendingOperationsAreConnectionAndShutdownScoped() throws Exception {
        String command = source("SkillsCommand.java");
        assertTrue(command.contains("Bukkit.getPlayer(player.getUniqueId()) != player"));
        assertTrue(command.contains("authenticatedPlayers.get(player.getUniqueId()) != player"));
        assertTrue(command.contains("authenticatedPlayers.remove(playerId, player)"));
        assertTrue(command.contains("!authenticatedPlayers.containsKey(playerId)) skills.evict(playerId)"));
        assertTrue(command.contains("Map<UUID, Player> learningPlayers"));
        assertTrue(command.contains("operations.remove(playerId, player)")
                || command.contains("learningPlayers.remove(playerId, player)"));
        assertSafeSync(command);

        String books = source("SkillBookListener.java");
        assertTrue(books.contains("Map<UUID, PendingBookUse> pendingPlayers"));
        assertTrue(books.contains("Bukkit.getPlayer(player.getUniqueId()) != player"));
        assertTrue(books.contains("pendingPlayers.remove(player.getUniqueId(), pending)"));
        assertTrue(books.contains("AuthenticatedPlayerRevokedEvent"));
        assertSafeSync(books);
    }

    private static void assertSafeSync(String source) {
        assertTrue(source.contains("if (!plugin.isEnabled()) return;"));
        assertTrue(source.contains("if (plugin.isEnabled()) action.run();"));
        assertTrue(source.contains("catch (IllegalPluginAccessException | IllegalStateException ignored)"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(repositoryRoot().resolve(
                "plugins/rpg-skills/src/main/java/cn/originalrpg/skills/" + name),
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
