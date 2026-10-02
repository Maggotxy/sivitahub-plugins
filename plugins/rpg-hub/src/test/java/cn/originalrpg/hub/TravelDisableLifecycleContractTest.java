package cn.originalrpg.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class TravelDisableLifecycleContractTest {
    @Test
    void asynchronousChunkCompletionsDoNotScheduleAfterPluginDisable() throws IOException {
        assertGuarded("BackTravel.java", "loadNeighborhood(target.getWorld()", "whenComplete");
        assertGuarded("HomeTravel.java", "loadArrivalChunks(currentRespawn)", "whenComplete");
        assertGuarded("HubTravel.java", "preloadNeighborhood(wilderness", "whenComplete");
    }

    @Test
    void homeAndBackRetainArrivalChunksAndReleaseEveryLifecycle() throws IOException {
        String home = source("HomeTravel.java");
        String back = source("BackTravel.java");
        String plugin = source("RpgHubPlugin.java");
        for (String source : new String[]{home, back}) {
            assertTrue(source.contains("addPluginChunkTicket(plugin)"));
            assertTrue(source.contains("removePluginChunkTicket(plugin)"));
            assertTrue(source.contains("void close()"));
            assertTrue(source.contains("Bukkit.getPlayer(player.getUniqueId()) == player"));
            assertTrue(source.contains("onAuthenticationRevoked"));
            assertTrue(source.contains("onQuit"));
        }
        assertTrue(back.contains("public void onTeleport(PlayerTeleportEvent event)"));
        assertTrue(plugin.contains("if (home != null) home.close()"));
        assertTrue(plugin.contains("if (back != null) back.close()"));
    }

    private static void assertGuarded(String file, String operation, String callback) throws IOException {
        String source = source(file);
        int load = source.indexOf(operation);
        int completion = source.indexOf(callback, load);
        int enabled = source.indexOf("if (!plugin.isEnabled()) return", completion);
        int schedule = source.indexOf("getScheduler().runTask(plugin", completion);
        assertTrue(load >= 0 && completion > load && enabled > completion && schedule > enabled,
                file + " must reject late chunk completions before touching the Bukkit scheduler");
    }

    private static String source(String file) throws IOException {
        return Files.readString(repositoryRoot().resolve(
                "plugins/rpg-hub/src/main/java/cn/originalrpg/hub/" + file), StandardCharsets.UTF_8);
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
