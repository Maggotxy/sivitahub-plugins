package cn.originalrpg.community;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CommunityAsyncLifecycleContractTest {
    @Test
    void callbacksRequireTheExactAuthenticatedLoginSession() throws Exception {
        String plugin = source("RpgCommunityPlugin.java");
        String command = source("CommunityCommand.java");
        String account = source("AccountCommand.java");

        assertTrue(plugin.contains("getServer().getPlayer(player.getUniqueId()) == player"));
        assertTrue(plugin.contains("new AuthenticatedSession(event.player(), sessionToken)"));
        assertTrue(plugin.contains("session.player() != player"));
        assertTrue(plugin.contains("authenticatedPlayers.remove(playerId, session)"));
        assertTrue(plugin.contains("sessionToken.equals(session.token())"));
        assertFalse(plugin.contains("authenticatedPlayers.remove(playerId);"));
        assertTrue(command.contains("UUID sessionToken = start(player,"));
        assertTrue(command.contains("plugin.isAuthenticated(player, sessionToken)"));
        assertTrue(command.contains("private final Map<UUID, OperationLease> operations"));
        assertTrue(command.contains("operations.putIfAbsent(player.getUniqueId(), new OperationLease(player, sessionToken))"));
        assertTrue(command.contains("operations.remove(playerId, lease)"));
        assertFalse(command.contains("operations.remove(player.getUniqueId())"));
        assertTrue(account.contains("UUID sessionToken = plugin.sessionToken(player)"));
        assertTrue(account.contains("plugin.isAuthenticated(player, sessionToken)"));
        assertTrue(account.contains("private final Map<UUID, IssueLease> issuedAt"));
        assertTrue(account.contains("issuedAt.remove(playerId, lease)"));
    }

    @Test
    void logoutCannotReopenAnAlreadySubmittedMutationGate() throws Exception {
        String command = source("CommunityCommand.java");
        assertTrue(command.contains("Submitted database work owns its gate until the callback settles"));
        assertFalse(command.contains("operations.remove(playerId);"));
        assertTrue(command.contains("operations.remove(playerId, lease)"));
    }

    @Test
    void callbacksDoNotScheduleOrRunAfterPluginDisable() throws Exception {
        String plugin = source("RpgCommunityPlugin.java");
        assertTrue(plugin.contains("if (!isEnabled()) return;"));
        assertTrue(plugin.contains("if (isEnabled()) action.run();"));
        assertTrue(plugin.contains("catch (IllegalPluginAccessException ignored)"));
    }

    private static String source(String file) throws Exception {
        return Files.readString(repositoryRoot().resolve(
                "plugins/rpg-community/src/main/java/cn/originalrpg/community/" + file));
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
