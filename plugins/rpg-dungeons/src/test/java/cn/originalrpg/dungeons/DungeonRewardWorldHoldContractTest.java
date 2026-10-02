package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonRewardWorldHoldContractTest {
    @Test
    void emptyInstanceCannotRecycleBeforeItsRewardIntentIsDurableAndClosed() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java"),
                StandardCharsets.UTF_8);

        int releasePlayer = source.indexOf("void releasePlayer(UUID playerId, World previousWorld)");
        int rewardHold = source.indexOf("if (!hasInstanceHold(instance)) releaseInstance(previousWorld)",
                releasePlayer);
        int timeout = source.indexOf("private void timeoutSession(String worldName, UUID sessionId)");
        int timeoutHold = source.indexOf(
                "if (world != null && !hasInstanceHold(worldName)) releaseInstance(world)", timeout);
        int durableCallback = source.indexOf("rewardIo.persist(intent.batch).whenComplete");
        int completion = source.indexOf("completeDungeonAfterDurableIntent(intent)", durableCallback);
        int removeHold = source.indexOf("pendingRewardIntents.remove(intent.batch.runId(), intent)", completion);
        int releaseVacant = source.indexOf("releaseRewardHeldInstanceIfVacant(intent.worldName)", removeHold);

        assertTrue(releasePlayer >= 0 && rewardHold > releasePlayer,
                "the final member leaving must not recycle a world with a pending reward intent");
        assertTrue(timeout >= 0 && timeoutHold > timeout,
                "the session timeout path must obey the same reward recycle hold");
        assertTrue(durableCallback >= 0 && completion > durableCallback
                        && removeHold > completion && releaseVacant > removeHold,
                "old session effects must finish before its recycle hold is removed and the world is released");
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
