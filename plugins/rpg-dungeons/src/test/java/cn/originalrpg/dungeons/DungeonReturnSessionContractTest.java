package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonReturnSessionContractTest {
    @Test
    void staleAsyncTeleportCannotClearANewerLoginsReturnPoint() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonCommand.java"),
                StandardCharsets.UTF_8);
        int callback = source.indexOf("player.teleportAsync(destination).whenComplete");
        int sessionGuard = source.indexOf("if (!currentSession(player)) return", callback);
        int remove = source.indexOf("getPersistentDataContainer().remove(returnLocation)", sessionGuard);
        int save = source.indexOf("player.saveData()", remove);
        int release = source.indexOf("manager.releasePlayer", save);
        int identity = source.indexOf("Bukkit.getPlayer(player.getUniqueId()) == player", release);

        assertTrue(callback >= 0 && sessionGuard > callback && remove > sessionGuard
                        && save > remove && release > save,
                "the current login must be proven before return state is cleared or persisted");
        assertTrue(identity > release,
                "isOnline alone cannot distinguish an old Player handle after quick reconnect");
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
