package cn.originalrpg.tools.dbmigration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Strict, bounded audit documents; duplicate JSON keys are errors, not last-value-wins overrides. */
final class IdentityJson {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting().create();
    private IdentityJson() { }

    static JsonObject read(Path file, long maximumBytes) throws IOException, MigrationException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                || Files.size(file) > maximumBytes) throw new MigrationException("Missing, unsafe or oversized JSON document: " + file);
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    static JsonObject parse(String text) throws IOException, MigrationException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement element = read(reader, 0);
            if (!element.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new MigrationException("Expected exactly one JSON object");
            }
            return element.getAsJsonObject();
        } catch (IllegalStateException | NumberFormatException error) {
            throw new MigrationException("Invalid identity JSON document", error);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException, MigrationException {
        if (depth > 32) throw new MigrationException("Identity JSON nesting exceeds 32 levels");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new MigrationException("Duplicate identity JSON key: " + name);
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) {
                    if (array.size() >= 100_000) throw new MigrationException("Identity JSON array exceeds its bound");
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NUMBER -> {
                String value = reader.nextString();
                if (value.length() > 128) throw new MigrationException("Identity JSON number is too long");
                yield new JsonPrimitive(new java.math.BigDecimal(value));
            }
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new MigrationException("Unexpected identity JSON token");
        };
    }

    static String text(JsonObject object, String key, int maximum) throws MigrationException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new MigrationException("Required string field: " + key);
        }
        String text = value.getAsString();
        if (text.isBlank() || text.length() > maximum || text.chars().anyMatch(Character::isISOControl)) {
            throw new MigrationException("Invalid identity field: " + key);
        }
        return text;
    }

    static String canonical(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            for (String key : new TreeSet<>(element.getAsJsonObject().keySet())) {
                sorted.add(key, com.google.gson.JsonParser.parseString(canonical(element.getAsJsonObject().get(key))));
            }
            return JSON.toJson(sorted);
        }
        if (element.isJsonArray()) {
            JsonArray ordered = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                ordered.add(com.google.gson.JsonParser.parseString(canonical(item)));
            }
            return JSON.toJson(ordered);
        }
        return JSON.toJson(element);
    }

    static String hash(String value) { return HexFormat.of().formatHex(digest().digest(value.getBytes(StandardCharsets.UTF_8))); }
    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String fileHash(Path file) throws IOException, MigrationException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            throw new MigrationException("Evidence must be a regular file, never a symlink: " + file);
        }
        var before = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        MessageDigest digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        var after = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
            throw new MigrationException("Evidence changed while hashing: " + file);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void writeNew(Path output, JsonObject value) throws IOException, MigrationException {
        Path target = output.toAbsolutePath().normalize();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new MigrationException("Refusing to replace a reviewed report: " + target);
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new MigrationException("Report directory must already exist");
        Path temporary = Files.createTempFile(parent, ".identity-report-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            Files.writeString(temporary, PRETTY.toJson(value) + "\n", StandardCharsets.UTF_8);
            try (var file = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) { file.force(true); }
            // A hard-link create is atomic and refuses an existing name. ATOMIC_MOVE
            // may replace a concurrently created target on some providers even
            // without REPLACE_EXISTING, so it cannot protect reviewed evidence.
            Files.createLink(target, temporary);
            try (var directory = java.nio.channels.FileChannel.open(parent, java.nio.file.StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
