package cn.originalrpg.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldEventLifecycleContractTest {
    @Test
    void cancelledTeleportsCannotResetAPlayersHuntChain() throws IOException {
        String source = source("plugins/rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java");
        assertTrue(source.contains("@EventHandler(ignoreCancelled = true)\n    public void onTeleport"));
    }

    @Test
    void optionalMobAppearanceProviderIsResolvedOnlyWhileEnabled() throws IOException {
        String source = source("plugins/rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java");
        assertTrue(source.contains("OptionalServices.enabled(plugin, MobAppearanceService.class)"));
        assertFalse(source.contains("private final MobAppearanceService"));
        assertFalse(source.contains("getServicesManager().load(MobAppearanceService.class)"));
    }

    @Test
    void bloodMoonCreeperTrackingFollowsChunkLoadAndUnload() throws IOException {
        String source = source("plugins/rpg-world/src/main/java/cn/originalrpg/world/WorldEventListener.java");
        assertTrue(source.contains("public void onEntitiesLoad(EntitiesLoadEvent event)"));
        assertTrue(source.contains("public void onEntitiesUnload(EntitiesUnloadEvent event)"));
        assertTrue(source.contains("trackBloodMoonCreeper(entity);"));
        assertTrue(source.contains("if (bloodMoonCreepers.isEmpty()) return;"));
        assertFalse(source.contains("new HashSet<>(bloodMoonCreepers)"));
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
