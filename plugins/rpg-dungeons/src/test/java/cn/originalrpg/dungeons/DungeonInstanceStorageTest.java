package cn.originalrpg.dungeons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DungeonInstanceStorageTest {
    @TempDir
    Path temporary;

    @Test
    void discoversLegacyNamespacedAndWorldGuardOrphansButPreservesLoadedWorlds() throws IOException {
        Path container = Files.createDirectories(temporary.resolve("runtime"));
        Path overworld = Files.createDirectories(container.resolve("survival"));
        Path namespaced = Files.createDirectories(overworld.resolve("dimensions/minecraft"));
        Path worldGuard = Files.createDirectories(container.resolve("plugins/WorldGuard/worlds"));
        DungeonInstanceStorage storage = new DungeonInstanceStorage(container, overworld, worldGuard);

        Path legacy = Files.createDirectories(container.resolve("dungeon_instance_legacy_1"));
        Path nested = Files.createDirectories(namespaced.resolve("dungeon_instance_nested_2"));
        Path loaded = Files.createDirectories(namespaced.resolve("dungeon_instance_loaded_3"));
        Path guardLegacy = Files.createDirectories(worldGuard.resolve(legacy.getFileName()));
        Path guardNested = Files.createDirectories(worldGuard.resolve(nested.getFileName()));
        Path guardLoaded = Files.createDirectories(worldGuard.resolve(loaded.getFileName()));
        Files.createDirectories(container.resolve("world"));
        Files.createDirectories(namespaced.resolve("not_a_dungeon"));

        List<Path> orphans = storage.orphanDirectories(Set.of("dungeon_instance_loaded_3"));

        assertEquals(Set.of(legacy, nested, guardLegacy, guardNested), Set.copyOf(orphans));
        assertFalse(orphans.contains(loaded));
        assertFalse(orphans.contains(guardLoaded));
    }

    @Test
    void acceptsOnlyCompleteCanonicalInstanceNames() {
        assertTrue(DungeonInstanceStorage.isInstanceName("dungeon_instance_ancient_ruins_1z141z"));
        assertTrue(DungeonInstanceStorage.isInstanceName("dungeon_instance_a_0"));

        for (String name : List.of(
                "dungeon_instance_",
                "dungeon_instance_safe",
                "dungeon_instance_safe_",
                "dungeon_instance__safe_1",
                "dungeon_instance_Ancient_1",
                "dungeon_instance_ancient-ruins_1",
                "dungeon_instance_safe/child",
                "dungeon_instance_safe.1",
                "not_dungeon_instance_safe_1")) {
            assertFalse(DungeonInstanceStorage.isInstanceName(name), name);
        }
        assertFalse(DungeonInstanceStorage.isInstanceName(null));
    }

    @Test
    void deletesOnlyDirectChildrenOfApprovedRootsAndDoesNotFollowSymlinks() throws IOException {
        Path container = Files.createDirectories(temporary.resolve("runtime"));
        Path overworld = Files.createDirectories(container.resolve("world"));
        Path worldGuard = Files.createDirectories(container.resolve("plugins/WorldGuard/worlds"));
        DungeonInstanceStorage storage = new DungeonInstanceStorage(container, overworld, worldGuard);
        Path namespaced = Files.createDirectories(overworld.resolve("dimensions/minecraft"));
        Path target = Files.createDirectories(namespaced.resolve("dungeon_instance_safe_1"));
        Files.writeString(target.resolve("level.dat"), "disposable");
        Path protectedFile = Files.writeString(temporary.resolve("protected.txt"), "keep");
        Files.createSymbolicLink(target.resolve("external-link"), protectedFile);
        Path nestedUnexpected = Files.createDirectories(
                namespaced.resolve("unexpected/dungeon_instance_nested_too_deep"));
        Path similarlyNamed = Files.createDirectories(namespaced.resolve("dungeon_instance_BAD"));

        int deleted = storage.deleteOrphans(List.of(target, nestedUnexpected, similarlyNamed), ignored -> { });

        assertEquals(1, deleted);
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(protectedFile));
        assertTrue(Files.exists(nestedUnexpected));
        assertTrue(Files.exists(similarlyNamed));
    }

    @Test
    void exposesBothSupportedPaperStorageRoots() throws IOException {
        Path container = Files.createDirectories(temporary.resolve("runtime"));
        Path overworld = Files.createDirectories(container.resolve("custom-level"));
        Path worldGuard = container.resolve("plugins/WorldGuard/worlds");
        DungeonInstanceStorage storage = new DungeonInstanceStorage(container, overworld, worldGuard);

        assertEquals(List.of(container.toAbsolutePath(),
                overworld.resolve("dimensions/minecraft").toAbsolutePath()), storage.worldRoots());
        assertEquals(worldGuard.toAbsolutePath(), storage.worldGuardRoot());
    }

    @Test
    void discoversPhysicalNamespacedRootWhenApiWorldFolderPointsElsewhere() throws IOException {
        Path container = Files.createDirectories(temporary.resolve("runtime"));
        Path physicalRoot = Files.createDirectories(container.resolve("world/dimensions/minecraft"));
        Path apiFolder = Files.createDirectories(container.resolve("world/dimensions/minecraft/overworld"));
        Path worldGuard = container.resolve("plugins/WorldGuard/worlds");

        DungeonInstanceStorage storage = new DungeonInstanceStorage(container, apiFolder, worldGuard);

        assertTrue(storage.worldRoots().contains(physicalRoot.toAbsolutePath()));
    }
}
