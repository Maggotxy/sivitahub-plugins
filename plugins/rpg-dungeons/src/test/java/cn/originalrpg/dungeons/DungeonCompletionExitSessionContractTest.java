package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonCompletionExitSessionContractTest {
    @Test
    void delayedCompletionExitCannotAffectAReusedWorldOrNewPlayerSession() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java"),
                StandardCharsets.UTF_8);
        int completion = source.indexOf("private void completeDungeonAfterDurableIntent");
        int delayed = source.indexOf("Bukkit.getScheduler().runTaskLater", completion);
        int generation = source.indexOf("DungeonSessionRules.isCurrentSession", delayed);
        int membership = source.indexOf("intent.worldName.equals(playerInstances.get(playerId))", generation);
        int currentWorld = source.indexOf("player.getWorld().getName().equals(intent.worldName)", membership);
        int exit = source.indexOf("exitHandler.accept(player)", currentWorld);

        assertTrue(completion >= 0 && delayed > completion && generation > delayed
                        && membership > generation && currentWorld > membership && exit > currentWorld,
                "an old completion timer must prove session generation, membership and current world before exit");
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
