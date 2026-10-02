package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonCompletionRecoveryContractTest {
    @Test
    void seasonEvidenceIsJournaledBeforeRewardAndRemovedOnlyAfterBothSettle() throws IOException {
        Path root = repositoryRoot();
        String manager = Files.readString(root.resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java"),
                StandardCharsets.UTF_8);
        String journal = Files.readString(root.resolve(
                "plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonRewardJournal.java"),
                StandardCharsets.UTF_8);

        int evidence = manager.indexOf("Optional<SeasonExpeditionCompletion> seasonCompletion");
        int batch = manager.indexOf("new DungeonRewardBatch(runId, rewardRequests", evidence);
        int persist = manager.indexOf("rewardIo.persist(intent.batch)", batch);
        int rewards = manager.indexOf("settleReward(intent.batch", persist);
        int settleEvidence = manager.indexOf("settleCompletionEvidence(batch", rewards);
        int remove = manager.indexOf("rewardIo.remove(batch)", settleEvidence);

        assertTrue(evidence >= 0 && batch > evidence && persist > batch && rewards > persist);
        assertTrue(settleEvidence > rewards && remove > settleEvidence,
                "the crash journal must survive until reward receipts and optional season settlement acknowledge");
        assertTrue(journal.contains("private static final int BATCH_VERSION = 2"));
        assertTrue(journal.contains("writeSeasonCompletion(output, batch.seasonCompletion())"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("plugins/rpg-dungeons"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Could not locate repository root");
        return current;
    }
}
