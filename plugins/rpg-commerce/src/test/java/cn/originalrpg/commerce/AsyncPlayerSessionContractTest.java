package cn.originalrpg.commerce;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncPlayerSessionContractTest {
    @Test
    void asyncUiCallbacksUseExactCurrentPlayerHandle() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-commerce/src/main/java/cn/originalrpg/commerce/RpgCommercePlugin.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
        assertTrue(source.contains("AuthenticatedPlayerReadyEvent"));
        assertTrue(source.contains("AuthenticatedPlayerRevokedEvent"));
        assertTrue(source.contains("private final Map<UUID, PlayerSession> authenticatedPlayers"));
        assertTrue(source.contains("private final Map<UUID, OperationLease> purchaseInFlight"));
        assertTrue(source.contains("purchaseInFlight.putIfAbsent(player.getUniqueId(),"));
        assertTrue(source.contains("purchaseInFlight.remove(playerId, lease)"));
        assertTrue(source.contains("authenticatedPlayers.remove(player.getUniqueId(), session)"));
        assertFalse(source.contains("purchaseInFlight.remove(player.getUniqueId())"));
        assertTrue(count(source, "currentSession(player)") >= 11);
        assertFalse(source.contains("if (!player.isOnline()) return;"));
        assertTrue(source.contains("if (!isEnabled()) return;"));
        assertTrue(source.contains("catch (IllegalStateException ignored)"));
        assertTrue(source.contains("public void onDisable()"));
        assertTrue(source.contains("authenticatedPlayers.clear();"));
        assertTrue(source.contains("purchaseInFlight.clear();"));
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int index = value.indexOf(needle); index >= 0; index = value.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
