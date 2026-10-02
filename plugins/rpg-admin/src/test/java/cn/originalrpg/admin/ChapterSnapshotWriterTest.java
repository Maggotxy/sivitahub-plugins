package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChapterSnapshotWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsVersionedCompleteSnapshot() {
        ChapterState state = new ChapterState("launch-1", "resonance", 1, 1, 7,
                Instant.parse("2026-08-07T12:00:00Z"));
        String json = ChapterSnapshotWriter.json(state, Instant.parse("2026-08-07T12:01:00Z"));

        assertTrue(json.contains("\"schemaVersion\":1"));
        assertTrue(json.contains("\"seasonId\":\"launch-1\""));
        assertTrue(json.contains("\"chapterId\":\"resonance\""));
        assertTrue(json.contains("\"maximumOpenAscension\":1"));
        assertTrue(json.contains("\"revision\":7"));
    }

    @Test
    void revokedGenerationCannotPublishOrLeaveTemporaryFiles() throws Exception {
        ChapterState state = new ChapterState("launch-1", "resonance", 1, 1, 7,
                Instant.parse("2026-08-07T12:00:00Z"));

        assertThrows(CancellationException.class,
                () -> ChapterSnapshotWriter.write(temporaryDirectory, state, () -> false));
        assertTrue(Files.notExists(temporaryDirectory.resolve("chapter.json")));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.findAny().isEmpty());
        }
    }
}
