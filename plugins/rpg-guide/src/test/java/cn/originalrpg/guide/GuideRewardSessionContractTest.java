package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class GuideRewardSessionContractTest {
    @Test
    void staleRewardCallbackLeavesCompletionForTheCurrentLoginToReplay() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideTracker.java"),
                StandardCharsets.UTF_8);
        int award = source.indexOf("private void award");
        int callback = source.indexOf(".whenComplete", award);
        int guard = source.indexOf("if (!ready(player)) return", callback);
        int completed = source.indexOf("getPersistentDataContainer().set(completedKey", guard);
        int save = source.indexOf("player.saveData()", completed);
        int identity = source.indexOf("Bukkit.getPlayer(player.getUniqueId()) == player", save);

        assertTrue(award >= 0 && callback > award && guard > callback
                        && completed > guard && save > completed,
                "guide completion PDC may be persisted only by the current authenticated login");
        assertTrue(identity > save,
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
