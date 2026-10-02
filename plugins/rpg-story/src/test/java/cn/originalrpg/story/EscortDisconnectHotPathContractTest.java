package cn.originalrpg.story;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EscortDisconnectHotPathContractTest {
    @Test
    void authenticationCleanupNeverResolvesBetonQuestClasses() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-story/src/main/java/cn/originalrpg/story/EscortManager.java"),
                StandardCharsets.UTF_8);
        int disconnect = source.indexOf("private void disconnectSession(AuthenticatedSession authenticated)");
        int nextMethod = source.indexOf("private void onMain", disconnect);
        String hotPath = source.substring(disconnect, nextMethod);

        assertTrue(disconnect >= 0 && nextMethod > disconnect
                        && hotPath.contains("sessions.remove(authenticated.player().getUniqueId(), session)")
                        && !hotPath.contains("tags(") && !hotPath.contains("betonQuest"),
                "PlayerQuit must clean only its in-memory actor and never enter BetonQuest");
    }

    @Test
    void escortManagerOwnsBothAuthenticationBoundaries() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-story/src/main/java/cn/originalrpg/story/EscortManager.java"),
                StandardCharsets.UTF_8);
        int revoke = source.indexOf("void onAuthenticationRevoked(AuthenticatedPlayerRevokedEvent event)");
        int revokedDisconnect = source.indexOf("clearAuthenticated(event.player())", revoke);
        int quit = source.indexOf("void onQuit(PlayerQuitEvent event)", revokedDisconnect);
        int disconnect = source.indexOf("clearAuthenticated(event.getPlayer())", quit);

        assertTrue(revoke >= 0 && revokedDisconnect > revoke && quit > revokedDisconnect && disconnect > quit,
                "both disconnect boundaries must avoid normal quest cancellation persistence");
    }

    @Test
    void pluginShutdownNeverResolvesBetonQuestClasses() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-story/src/main/java/cn/originalrpg/story/EscortManager.java"),
                StandardCharsets.UTF_8);
        int close = source.indexOf("public void close()");
        int nextMethod = source.indexOf("private void tick(int interval)", close);
        String shutdownPath = source.substring(close, nextMethod);

        assertTrue(close >= 0 && nextMethod > close
                        && shutdownPath.contains("cleanup(session, player)")
                        && !shutdownPath.contains("tags(") && !shutdownPath.contains("betonQuest"),
                "plugin shutdown must clean only in-memory actors and preserve resumable tags");
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
