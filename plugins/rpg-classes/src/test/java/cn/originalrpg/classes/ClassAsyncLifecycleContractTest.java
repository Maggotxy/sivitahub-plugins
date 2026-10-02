package cn.originalrpg.classes;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class ClassAsyncLifecycleContractTest {
    @Test
    void asynchronousClassUiRejectsOldConnectionsAndPluginShutdown() throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-classes/src/main/java/cn/originalrpg/classes/RpgClassesPlugin.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertTrue(source.contains("new AuthenticatedSession(player, UUID.randomUUID())"));
        assertTrue(source.contains("private final Map<UUID, OperationLease> profileMutations"));
        assertTrue(source.contains("private final Map<UUID, OperationLease> ascensionMutations"));
        assertTrue(source.contains("operations.remove(playerId, lease)"));
        assertTrue(source.contains("if (!isReady(player, lease.sessionToken())) return;"));
        assertTrue(source.contains("if (!isReady(player, sessionToken)) return;"));
        assertTrue(source.contains("authenticatedPlayers.remove(playerId, session)"));
        assertFalse(source.contains("profileMutations.remove(playerId);"));
        assertFalse(source.contains("ascensionMutations.remove(playerId);"));
        assertFalse(source.contains("reopenAfterClassChoice.remove(playerId);"));
        assertTrue(source.contains("if (!isEnabled()) return;"));
        assertTrue(source.contains("if (isEnabled()) action.run();"));
        assertTrue(source.contains("catch (IllegalStateException ignored)"));

        String hidden = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-classes/src/main/java/cn/originalrpg/classes/HiddenPathEffects.java"),
                StandardCharsets.UTF_8);
        assertTrue(hidden.contains(".whenComplete((result, failure) -> sync("));
        assertTrue(hidden.contains("paths.path(playerId).whenComplete((path, failure) -> sync("));
        assertTrue(hidden.contains("if (!plugin.isEnabled()) return;"));
        assertTrue(hidden.contains("if (plugin.isEnabled()) action.run();"));
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
