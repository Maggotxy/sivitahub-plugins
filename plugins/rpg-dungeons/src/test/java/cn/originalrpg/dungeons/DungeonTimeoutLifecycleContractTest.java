package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonTimeoutLifecycleContractTest {
    @Test
    void recycledWorldsCancelOldTimeoutsAndFenceLateCallbacksBySessionId() throws IOException {
        String source = source("plugins/rpg-dungeons/src/main/java/cn/originalrpg/dungeons/DungeonManager.java");
        int assign = source.indexOf("private void assignInstance(");
        int cancelOld = source.indexOf("cancelInstanceTimeout(worldName)", assign);
        int putSession = source.indexOf("instanceSessions.put(worldName, sessionId)", cancelOld);
        int schedule = source.indexOf("timeoutSession(worldName, sessionId)", putSession);
        int release = source.indexOf("private void releaseInstance(World world)");
        int cancelRelease = source.indexOf("cancelInstanceTimeout(name)", release);
        int timeout = source.indexOf("private void timeoutSession(String worldName, UUID sessionId)");
        int generationFence = source.indexOf("DungeonSessionRules.isCurrentSession", timeout);

        assertTrue(assign >= 0 && cancelOld > assign && putSession > cancelOld && schedule > putSession,
                "every assignment must replace the old timeout and bind a fresh session generation");
        assertTrue(release >= 0 && cancelRelease > release,
                "recycling an instance must cancel its active timeout task");
        assertTrue(timeout >= 0 && generationFence > timeout,
                "a late scheduled callback must prove it still owns the current session");
    }

    private static String source(String relative) throws IOException {
        return Files.readString(repositoryRoot().resolve(relative), StandardCharsets.UTF_8);
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
