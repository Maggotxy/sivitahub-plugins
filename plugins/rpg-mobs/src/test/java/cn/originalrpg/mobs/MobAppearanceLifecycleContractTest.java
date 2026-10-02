package cn.originalrpg.mobs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MobAppearanceLifecycleContractTest {
    @Test
    void persistedRareBloodMoonModelIsRestoredByItsArchetypeAfterChunkReload() throws IOException {
        String source = Files.readString(repositoryRoot().resolve(
                "plugins/rpg-mobs/src/main/java/cn/originalrpg/mobs/LegacyMobMaskMigration.java"
        ), StandardCharsets.UTF_8);

        assertTrue(source.contains("bloodMoonRare"));
        assertTrue(source.contains("bloodMoonArchetype"));
        assertTrue(source.contains("appearances.applyBloodMoonModel(entity, rareArchetype)"));
        assertFalse(source.contains("|| markedBloodMoon) appearances.applyBloodMoonHusk"));
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
