package cn.originalrpg.tools.dbmigration;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Operator-verified mapping. Names are audit labels and are never used to infer UUID ownership. */
record IdentityMapping(UUID operationId, UUID source, UUID target, String sourceLabel, String targetLabel,
                       String verifiedBy, Path evidenceFile, String evidenceSha256, String digest) {
    private static final Set<String> FIELDS = Set.of("schema_version", "operation_id", "source_uuid", "target_uuid",
            "source_label", "target_label", "verified_by", "evidence_file", "evidence_sha256");

    static IdentityMapping read(Path file) throws IOException, MigrationException {
        JsonObject json = IdentityJson.read(file, 64 * 1024);
        if (!json.keySet().equals(FIELDS) || !json.get("schema_version").isJsonPrimitive()
                || !json.get("schema_version").getAsJsonPrimitive().isNumber()
                || !json.get("schema_version").getAsString().equals("1")) {
            throw new MigrationException("Unsupported identity mapping schema or unknown/missing fields");
        }
        UUID operation = uuid(IdentityJson.text(json, "operation_id", 36));
        UUID source = uuid(IdentityJson.text(json, "source_uuid", 36));
        UUID target = uuid(IdentityJson.text(json, "target_uuid", 36));
        if (source.equals(target)) throw new MigrationException("Identity mapping source and target must differ");
        String sourceLabel = IdentityJson.text(json, "source_label", 64);
        String targetLabel = IdentityJson.text(json, "target_label", 64);
        String operator = IdentityJson.text(json, "verified_by", 128);
        Path evidence = file.toAbsolutePath().getParent().resolve(IdentityJson.text(json, "evidence_file", 4096)).normalize();
        String evidenceHash = IdentityJson.text(json, "evidence_sha256", 64);
        if (!evidenceHash.matches("[0-9a-f]{64}") || !evidenceHash.equals(IdentityJson.fileHash(evidence))) {
            throw new MigrationException("Identity ownership evidence digest does not match the reviewed file");
        }
        // The evidence's content, not its host-dependent location, binds the mapping.
        JsonObject canonical = json.deepCopy();
        canonical.remove("evidence_file");
        return new IdentityMapping(operation, source, target, sourceLabel, targetLabel, operator,
                evidence, evidenceHash, IdentityJson.hash(IdentityJson.canonical(canonical)));
    }

    static UUID uuid(String value) throws MigrationException {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value) || uuid.equals(new UUID(0, 0))) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException error) { throw new MigrationException("Expected a canonical, nonzero UUID", error); }
    }

    void verifyEvidenceUnchanged() throws IOException, MigrationException {
        if (!evidenceSha256.equals(IdentityJson.fileHash(evidenceFile))) {
            throw new MigrationException("Identity ownership evidence changed after planning");
        }
    }
}
