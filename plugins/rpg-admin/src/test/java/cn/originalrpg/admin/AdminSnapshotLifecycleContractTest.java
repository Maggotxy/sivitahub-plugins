package cn.originalrpg.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class AdminSnapshotLifecycleContractTest {
    @Test
    void jdbcCompletionOnlyHandsImmutableReportToSnapshotWorker() throws IOException {
        String source = Files.readString(source("RpgAdminPlugin.java"));
        int export = source.indexOf("private void exportEconomySnapshot()");
        int chapter = source.indexOf("private void exportChapterSnapshot()", export);
        String method = source.substring(export, chapter);

        assertTrue(method.contains("io.economy(report)"));
        assertTrue(!method.contains("EconomyHistoryStore"));
        assertTrue(!method.contains("EconomySnapshotWriter"));
        assertTrue(!method.contains("Files."));
    }

    @Test
    void chapterServiceIsSampledBeforeTheIoSubmission() throws IOException {
        String source = Files.readString(source("RpgAdminPlugin.java"));
        int method = source.indexOf("private void exportChapterSnapshot()");
        int serviceRead = source.indexOf("chapters.current()", method);
        int immutableCopy = source.indexOf("new ChapterState(", serviceRead);
        int submission = source.indexOf("io.chapter(snapshot)", immutableCopy);
        assertTrue(method >= 0 && serviceRead > method && immutableCopy > serviceRead && submission > immutableCopy);
    }

    @Test
    void disableInvalidatesCallbacksBeforeBoundedClose() throws IOException {
        String source = Files.readString(source("RpgAdminPlugin.java"));
        int disable = source.indexOf("public void onDisable()");
        int inactive = source.indexOf("snapshotLifecycleActive.set(false)", disable);
        int detach = source.indexOf("snapshotIo = null", inactive);
        int close = source.indexOf("current.close(Duration.ofMillis(timeoutMillis))", detach);
        assertTrue(disable >= 0 && inactive > disable && detach > inactive && close > detach);
    }

    private static Path source(String file) {
        return Path.of("src/main/java/cn/originalrpg/admin", file);
    }
}
