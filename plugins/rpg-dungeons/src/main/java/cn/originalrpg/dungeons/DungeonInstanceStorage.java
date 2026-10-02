package cn.originalrpg.dungeons;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Locates and removes disposable dungeon worlds without allowing a recursive
 * delete to escape one of the explicitly approved storage roots.
 */
final class DungeonInstanceStorage {
    static final String INSTANCE_PREFIX = "dungeon_instance_";
    // startNewInstance always appends one non-empty base-36 generation token.
    // Requiring at least two non-empty segments prevents a merely similar
    // persistent world (or a truncated prefix) from entering the disposable
    // world lifecycle.
    private static final Pattern INSTANCE_NAME = Pattern.compile(
            "dungeon_instance_[a-z0-9]+(?:_[a-z0-9]+)+");

    private final List<Path> worldRoots;
    private final Path worldGuardRoot;
    private final Set<Path> approvedRoots;

    DungeonInstanceStorage(Path worldContainer, Path overworldFolder, Path worldGuardRoot) {
        Path normalizedContainer = normalize(worldContainer);
        Path normalizedOverworld = normalize(overworldFolder);
        LinkedHashSet<Path> discoveredWorldRoots = new LinkedHashSet<>();
        discoveredWorldRoots.add(normalizedContainer);
        discoveredWorldRoots.add(normalizedOverworld.resolve("dimensions").resolve("minecraft").normalize());
        // In Paper 26.2 a CraftWorld folder can resolve differently from the physical
        // namespaced dimension root. Discover only the exact dimensions/minecraft
        // child below direct server-level folders; never recurse through arbitrary paths.
        if (Files.isDirectory(normalizedContainer, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(normalizedContainer)) {
                entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.resolve("dimensions").resolve("minecraft").normalize())
                        .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .forEach(discoveredWorldRoots::add);
            } catch (IOException ignored) {
                // orphanDirectories reports scan failures for the approved roots it can access
            }
        }
        this.worldRoots = List.copyOf(discoveredWorldRoots);
        this.worldGuardRoot = normalize(worldGuardRoot);
        LinkedHashSet<Path> roots = new LinkedHashSet<>(worldRoots);
        roots.add(this.worldGuardRoot);
        this.approvedRoots = Set.copyOf(roots);
    }

    List<Path> orphanDirectories(Set<String> loadedWorlds) throws IOException {
        Set<String> loaded = Set.copyOf(loadedWorlds);
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        for (Path root : approvedRoots) {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue;
            try (var entries = Files.list(root)) {
                entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> isInstanceName(path.getFileName().toString()))
                        .filter(path -> !loaded.contains(path.getFileName().toString()))
                        .map(DungeonInstanceStorage::normalize)
                        .forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    int deleteOrphans(Collection<Path> candidates, Consumer<String> warning) {
        int deleted = 0;
        for (Path candidate : candidates) {
            if (deleteDirectory(candidate, warning)) deleted++;
        }
        return deleted;
    }

    boolean isApprovedInstanceDirectory(Path candidate) {
        Path normalized = normalize(candidate);
        Path parent = normalized.getParent();
        return parent != null
                && approvedRoots.contains(parent)
                && isInstanceName(normalized.getFileName().toString())
                && !Files.isSymbolicLink(normalized);
    }

    List<Path> worldRoots() {
        return worldRoots;
    }

    Path worldGuardRoot() {
        return worldGuardRoot;
    }

    private boolean deleteDirectory(Path candidate, Consumer<String> warning) {
        Path normalized = normalize(candidate);
        if (!isApprovedInstanceDirectory(normalized)) {
            warning.accept("Refused to delete unexpected dungeon path " + normalized);
            return false;
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isSymbolicLink()) {
                        Files.deleteIfExists(file);
                    } else {
                        Files.delete(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException exception) {
            warning.accept("Could not clean dungeon instance " + normalized + ": " + exception.getMessage());
            return false;
        }
    }

    static boolean isInstanceName(String name) {
        return name != null && INSTANCE_NAME.matcher(name).matches();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
