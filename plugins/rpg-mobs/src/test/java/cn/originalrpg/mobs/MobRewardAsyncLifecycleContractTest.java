package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MobRewardAsyncLifecycleContractTest {
    @Test
    void experienceBufferKeepsInFlightOwnershipUntilAuthorityIsKnown() throws IOException {
        String source = source("ExperienceWriteBuffer.java");

        assertTrue(source.contains("private final Map<UUID, InFlightWrite> inFlight"));
        assertTrue(source.contains("private final ConcurrentLinkedQueue<CompletedWrite> completedWrites"));
        assertTrue(source.contains("waitForInFlight(inFlightDeadlineNanos);"));
        assertTrue(source.contains("if (!future.isDone())"));
        assertTrue(source.contains("uncertain state at shutdown"));
        assertTrue(source.contains("drainKnownFailures(retry, deadlineNanos);"));
        assertTrue(source.contains("maximum-pending-players"));
        assertTrue(source.contains("retryDelayMillis(attempts, retryBaseMillis, retryMaximumMillis)"));
        assertFalse(source.contains("Bukkit.getScheduler().runTask(plugin, () -> {\n"
                + "                    inFlight.remove"));
    }

    @Test
    void delayedItemsUseStableMailboxReplayAndDoNotDiscardAtAnAttemptLimit() throws IOException {
        String source = source("DelayedMobRewardQueue.java");

        assertTrue(source.contains("activeMailboxWrites"));
        assertTrue(source.contains("waitForInFlightMailboxWrites(inFlightDeadlineNanos);"));
        assertTrue(source.contains("drainMailboxDeliveries(deadlineNanos);"));
        assertTrue(source.contains("delivery.deliveryId()"));
        assertTrue(source.contains("retryMaximumMillis"));
        assertTrue(source.contains("if (closed || !plugin.isEnabled()) return;"));
        assertTrue(source.contains("catch (IllegalStateException ignored)"));
        assertTrue(source.contains("if (!closed && plugin.isEnabled()) action.run();"));
        assertFalse(source.contains("maximumAttempts"));
        assertFalse(source.contains("retry limit reached"));
        assertFalse(source.contains("expired before durable settlement"));
    }

    @Test
    void feedbackRequiresTheExactLoginSessionAndDisableDrainsItemsBeforeExperience() throws IOException {
        String experience = source("ExperienceWriteBuffer.java");
        String queue = source("DelayedMobRewardQueue.java");
        String plugin = source("RpgMobsPlugin.java");

        assertTrue(experience.contains("current == expected && online"));
        assertTrue(queue.contains("current == session && current.isOnline()"));
        assertTrue(plugin.indexOf("delayedRewards.close()") < plugin.indexOf("experienceBuffer.shutdown()"));
    }

    private static String source(String file) throws IOException {
        String relative = file.equals("AdaptiveMobListener.java")
                ? "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/listener/" + file
                : "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/" + file;
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
