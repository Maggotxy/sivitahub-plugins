package cn.originalrpg.performance;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Generation-fenced atomic projection of the live performance snapshot.
 *
 * <p>A Paper plugin generation can outlive disable briefly while an I/O task
 * finishes. The generation marker and OS file lock prevent that old task from
 * replacing a snapshot produced by a newly enabled generation. Every write
 * also uses its own temporary path, so two class loaders never truncate one
 * another's staging file.</p>
 */
final class PerformanceStatusStore {
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    private static final long RETRY_NANOS = Duration.ofMillis(5).toNanos();

    private final Path directory;
    private final Path target;
    private final Path marker;
    private final Path lockPath;
    private final String generation;
    private final AtomicBoolean active = new AtomicBoolean();

    PerformanceStatusStore(Path directory) {
        this(directory, UUID.randomUUID().toString());
    }

    PerformanceStatusStore(Path directory, String generation) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.target = directory.resolve("status.json");
        this.marker = directory.resolve("status.json.generation");
        this.lockPath = directory.resolve("status.json.lock");
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    void activate() throws IOException {
        Files.createDirectories(directory);
        withExclusiveLock(() -> {
            writeAtomic(marker, generation + System.lineSeparator(), "generation");
            active.set(true);
            return null;
        });
    }

    boolean write(String json) throws IOException {
        Objects.requireNonNull(json, "json");
        if (!active.get()) return false;
        Path temporary = uniqueTemporary("snapshot");
        try {
            Files.writeString(temporary, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return withExclusiveLock(() -> {
                if (!active.get() || !ownsMarker()) return false;
                replace(temporary, target);
                return true;
            });
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void deactivate() throws IOException {
        active.set(false);
        if (!Files.isDirectory(directory)) return;
        withExclusiveLock(() -> {
            if (ownsMarker()) Files.deleteIfExists(marker);
            return null;
        });
    }

    private boolean ownsMarker() throws IOException {
        if (!Files.isRegularFile(marker)) return false;
        return generation.equals(Files.readString(marker).strip());
    }

    private void writeAtomic(Path destination, String value, String purpose) throws IOException {
        Path temporary = uniqueTemporary(purpose);
        try {
            Files.writeString(temporary, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            replace(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path uniqueTemporary(String purpose) {
        return directory.resolve("status.json." + purpose + "." + generation + "."
                + UUID.randomUUID() + ".tmp");
    }

    private static void replace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private <T> T withExclusiveLock(IoOperation<T> operation) throws IOException {
        long deadline = System.nanoTime() + LOCK_TIMEOUT.toNanos();
        while (true) {
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                try (FileLock lock = tryLock(channel)) {
                    if (lock != null) return operation.run();
                }
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("Timed out waiting for the performance status file lock");
            }
            LockSupport.parkNanos(RETRY_NANOS);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for the performance status file lock");
            }
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException exception) {
            return null;
        }
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T run() throws IOException;
    }
}
