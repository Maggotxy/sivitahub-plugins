package cn.originalrpg.admin;

import cn.originalrpg.api.model.ChapterState;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Atomic, read-only bridge from the authoritative chapter service to the website. */
final class ChapterSnapshotWriter {
    private ChapterSnapshotWriter() {
    }

    static void write(Path dataFolder, ChapterState state) throws IOException {
        write(dataFolder, state, () -> true);
    }

    static void write(Path dataFolder, ChapterState state, BooleanSupplier publishAllowed) throws IOException {
        Objects.requireNonNull(publishAllowed, "publishAllowed");
        requirePublication(publishAllowed);
        Files.createDirectories(dataFolder);
        Path target = dataFolder.resolve("chapter.json");
        Path temporary = Files.createTempFile(dataFolder, "chapter.json.", ".tmp");
        try {
            Files.writeString(temporary, json(state, Instant.now()), StandardOpenOption.TRUNCATE_EXISTING);
            requirePublication(publishAllowed);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                requirePublication(publishAllowed);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requirePublication(BooleanSupplier publishAllowed) {
        if (!publishAllowed.getAsBoolean()) throw new CancellationException("chapter snapshot generation expired");
    }

    static String json(ChapterState state, Instant sampledAt) {
        return "{"
                + "\"schemaVersion\":1,"
                + "\"sampledAt\":" + quoted(sampledAt.toString()) + ","
                + "\"seasonId\":" + quoted(state.seasonId()) + ","
                + "\"chapterId\":" + quoted(state.chapterId()) + ","
                + "\"sequence\":" + state.sequence() + ","
                + "\"maximumOpenAscension\":" + state.maximumOpenAscension() + ","
                + "\"revision\":" + state.revision() + ","
                + "\"changedAt\":" + quoted(state.changedAt().toString())
                + "}\n";
    }

    private static String quoted(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }
}
