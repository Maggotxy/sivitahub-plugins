package cn.originalrpg.guide;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonEvidenceRetryContractTest {
    @Test
    void persistedDungeonEvidenceUsesBoundedSameSessionRetries() throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-guide/src/main/java/cn/originalrpg/guide/GuideTracker.java"));
        assertTrue(source.contains("MAXIMUM_DUNGEON_EVIDENCE_MISSES = 4"));
        assertTrue(source.contains("DUNGEON_EVIDENCE_RETRY_TICKS = 20L * 15L"));
        assertTrue(source.contains("dungeonEvidenceChecked.remove(player.getUniqueId())"));
        assertTrue(source.contains("if (ready(player)) queryDungeonEvidence(player)"));
        assertTrue(source.contains("dungeonEvidenceMisses.remove(playerId)"));
        assertTrue(source.contains("if (!plugin.isEnabled()) return"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("plugins/rpg-guide"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Could not locate repository root");
        return current;
    }
}
