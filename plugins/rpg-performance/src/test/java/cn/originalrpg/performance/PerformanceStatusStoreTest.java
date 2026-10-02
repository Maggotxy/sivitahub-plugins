package cn.originalrpg.performance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PerformanceStatusStoreTest {
    @TempDir
    Path directory;

    @Test
    void aNewGenerationFencesLateWritesFromTheOldGeneration() throws IOException {
        PerformanceStatusStore oldGeneration = new PerformanceStatusStore(directory, "old");
        oldGeneration.activate();
        assertTrue(oldGeneration.write("old-first"));

        PerformanceStatusStore newGeneration = new PerformanceStatusStore(directory, "new");
        newGeneration.activate();
        assertFalse(oldGeneration.write("old-late"));
        assertTrue(newGeneration.write("new-live"));

        assertEquals("new-live", Files.readString(directory.resolve("status.json")));
    }

    @Test
    void deactivationRejectsFurtherWritesAndCleansItsMarker() throws IOException {
        PerformanceStatusStore store = new PerformanceStatusStore(directory, "only");
        store.activate();
        store.deactivate();

        assertFalse(store.write("late"));
        assertFalse(Files.exists(directory.resolve("status.json.generation")));
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
