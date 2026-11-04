package cn.originalrpg.tools.dbmigration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Inventories an explicitly selected, stopped runtime. It never rewrites opaque
 * player files or accepts a generic "already migrated" bypass. External provider
 * absence is a reviewed operator assertion, checked against visible local facts.
 */
final class IdentityExternalCoverage {
    static final Set<String> PROVIDERS = Set.of("paper-player-state", "authme", "luckperms", "mypet",
            "betonquest", "citizens", "worldguard", "website", "custom-player-state");
    private static final Map<String, String> PLUGINS = Map.of(
            "authme", "AuthMe", "luckperms", "LuckPerms", "mypet", "MyPet", "betonquest", "BetonQuest",
            "citizens", "Citizens", "worldguard", "WorldGuard");
    record Evidence(JsonObject report, String digest, List<String> blockers) { }

    static Evidence scan(Path runtime, Path coverageFile, IdentityMapping mapping, Path coreH2Base)
            throws IOException, MigrationException {
        Path root = runtime.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)
                || !Files.isRegularFile(root.resolve("server.properties"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(root.resolve("plugins"), LinkOption.NOFOLLOW_LINKS)) {
            throw new MigrationException("Select a real stopped Paper runtime with server.properties and plugins/");
        }
        if (coverageFile.toAbsolutePath().normalize().startsWith(root)
                || mapping.evidenceFile().toAbsolutePath().normalize().startsWith(root)) {
            throw new MigrationException("Mapping evidence and coverage review must be stored outside the mutable runtime");
        }
        Path realRoot = root.toRealPath();
        if (coverageFile.toRealPath().startsWith(realRoot) || mapping.evidenceFile().toRealPath().startsWith(realRoot)) {
            throw new MigrationException("Resolved identity evidence must remain outside the runtime");
        }
        JsonObject review = IdentityJson.read(coverageFile, 256 * 1024);
        if (!review.keySet().equals(Set.of("schema_version", "source_uuid", "target_uuid", "verified_by",
                "evidence_sha256", "providers")) || !review.get("schema_version").isJsonPrimitive()
                || !review.get("schema_version").getAsJsonPrimitive().isNumber()
                || !review.get("schema_version").getAsString().equals("1")
                || !IdentityJson.text(review, "source_uuid", 36).equals(mapping.source().toString())
                || !IdentityJson.text(review, "target_uuid", 36).equals(mapping.target().toString())
                || !IdentityJson.text(review, "verified_by", 128).equals(mapping.verifiedBy())
                || !IdentityJson.text(review, "evidence_sha256", 64).equals(mapping.evidenceSha256())
                || !review.get("providers").isJsonArray()) {
            throw new MigrationException("External coverage review does not match the verified identity mapping");
        }
        TreeSet<String> blockers = new TreeSet<>();
        TreeSet<String> found = new TreeSet<>();
        for (var entry : review.getAsJsonArray("providers")) {
            if (!entry.isJsonObject()) throw new MigrationException("External provider review must be an object");
            JsonObject provider = entry.getAsJsonObject();
            if (!provider.keySet().equals(Set.of("id", "state", "note"))) throw new MigrationException("Unknown external-provider review field");
            String id = IdentityJson.text(provider, "id", 64);
            String state = IdentityJson.text(provider, "state", 32);
            IdentityJson.text(provider, "note", 500);
            if (!PROVIDERS.contains(id) || !found.add(id) || !Set.of("ABSENT", "REQUIRES_ADAPTER").contains(state)) {
                throw new MigrationException("Unknown/duplicate external provider or unsupported adapter declaration");
            }
            if (!state.equals("ABSENT")) blockers.add("EXTERNAL_ADAPTER_REQUIRED:" + id);
        }
        if (!found.equals(PROVIDERS)) throw new MigrationException("Every external identity surface must be explicitly reviewed");
        for (var provider : PLUGINS.entrySet()) {
            if (Files.exists(root.resolve("plugins").resolve(provider.getValue()), LinkOption.NOFOLLOW_LINKS)) {
                blockers.add("EXTERNAL_STORE_PRESENT:" + provider.getKey() + ":plugins/" + provider.getValue());
            }
        }

        JsonArray files = new JsonArray();
        List<Path> paths;
        try (var walk = Files.walk(root)) { paths = walk.limit(100_001).sorted().toList(); }
        if (paths.size() > 100_000) throw new MigrationException("External inventory exceeds 100000 paths; a reviewed bounded adapter is required");
        for (Path file : paths) {
            if (file.equals(root)) continue;
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (Files.isSymbolicLink(file)) throw new MigrationException("Symlinks are not a covered identity surface: " + relative);
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new MigrationException("Unsupported runtime file type: " + relative);
            String kind = classify(relative, file, coreH2Base);
            if (kind.equals("UNSUPPORTED_STATE")) blockers.add("EXTERNAL_FILE_ADAPTER_REQUIRED:" + relative);
            String digest = kind.equals("CORE_DATABASE") ? "covered-by-database-snapshot" : IdentityJson.fileHash(file);
            if (kind.equals("STATIC_TEXT")) {
                if (Files.size(file) > 2_000_000) blockers.add("OVERSIZED_EXTERNAL_TEXT_ADAPTER_REQUIRED:" + relative);
                else {
                    String text = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    if (text.contains(mapping.source().toString()) || text.contains(mapping.target().toString())) {
                        blockers.add("UNCLASSIFIED_EXTERNAL_UUID:" + relative);
                    }
                }
            }
            JsonObject item = new JsonObject();
            item.addProperty("path", relative);
            item.addProperty("kind", kind);
            if (!kind.equals("CORE_DATABASE")) item.addProperty("bytes", Files.size(file));
            item.addProperty("sha256", digest);
            files.add(item);
        }
        JsonObject report = new JsonObject();
        report.addProperty("runtime", root.toString());
        report.add("provider_review", review);
        report.add("files", files);
        return new Evidence(report, IdentityJson.hash(IdentityJson.canonical(report)), List.copyOf(blockers));
    }

    private static String classify(String relative, Path file, Path coreH2Base) {
        if (coreH2Base != null) {
            for (String suffix : List.of(".mv.db", ".lock.db", ".trace.db")) {
                if (file.toAbsolutePath().normalize().equals(Path.of(coreH2Base + suffix))) return "CORE_DATABASE";
            }
        }
        String path = relative.toLowerCase(Locale.ROOT);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (path.startsWith("logs/")) return "HISTORICAL_LOG";
        if (name.endsWith(".jar") || path.startsWith("libraries/") || path.startsWith("versions/")
                || path.startsWith("resourcepacks/") || path.contains("/textures/") || path.contains("/models/")) return "STATIC_CODE_OR_ASSET";
        if (name.equals("server.properties") || name.equals("bukkit.yml") || name.equals("spigot.yml")
                || name.equals("eula.txt") || name.equals("config.yml") || path.startsWith("config/")
                || path.contains("/lang/") || name.equals("plugin.yml")) return "STATIC_TEXT";
        // NBT, region entities, JSON user state, SQLite and plugin/PDC journals
        // require format- and authority-aware adapters, even if UUID text is hidden.
        return "UNSUPPORTED_STATE";
    }

    static void requireNoLocalJavaRuntime(Path runtime) throws MigrationException {
        PaperStopGate.requireStopped(runtime);
        Path root = runtime.toAbsolutePath().normalize();
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            if (process.pid() == ProcessHandle.current().pid() || !process.isAlive()) continue;
            String command = process.info().command().orElse("").replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!command.endsWith("/java") && !command.endsWith("/java.exe")) continue;
            try {
                Path cwd = Path.of("/proc", Long.toString(process.pid()), "cwd").toRealPath();
                if (cwd.startsWith(root)) throw new MigrationException("A Java process still uses the selected runtime: PID " + process.pid());
            } catch (IOException unavailable) {
                // Remote processes are covered by database leases and the explicit
                // maintenance inventory. Do not pretend /proc proves remote shutdown.
            }
        }
    }
}
