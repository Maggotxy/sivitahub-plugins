package cn.originalrpg.tools.dbmigration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Offline, one-to-one Core database identity migration. It creates no runtime
 * alias and never merges accounts. The reviewed plan, actual data changes, and
 * immutable receipt share the existing database authority. Every column has an
 * explicit policy; unsupported assets/external state keep apply closed.
 */
final class IdentityMigrationEngine {
    static final String RECEIPTS = "rpg_identity_migration_receipts";
    private static final int MAXIMUM_ROWS_PER_TABLE = 1_000_000;
    private static final int MAXIMUM_CHANGED_ROWS = 250_000;
    private final SchemaInspector inspector = new SchemaInspector();
    private final IdentityColumns policy;
    private final Runnable beforeCommitFaultHook;

    record Plan(JsonObject document, String digest, List<Update> updates, Map<String, TableProof> proofs,
                List<String> blockers, String beforeDigest, String afterDigest) {
        boolean ready() { return blockers.isEmpty(); }
    }
    record Result(String outcome, long changedRows, String planDigest) { }
    private record Update(SchemaInspector.Table table, Map<String, Object> key, Map<String, String> changes) { }
    private record TableProof(long rows, String before, String after, Map<String, Long> changedColumns) { }
    @FunctionalInterface interface EvidenceProbe { IdentityExternalCoverage.Evidence inspect() throws Exception; }

    IdentityMigrationEngine() throws MigrationException { this(() -> { }); }
    IdentityMigrationEngine(Runnable beforeCommitFaultHook) throws MigrationException {
        policy = IdentityColumns.load();
        this.beforeCommitFaultHook = beforeCommitFaultHook;
    }

    Plan preview(Connection connection, String schemaName, IdentityMapping mapping,
                 IdentityExternalCoverage.Evidence external) throws MigrationException {
        try {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            SchemaInspector.Schema schema = checkedSchema(connection, schemaName);
            return buildPlan(connection, schema, mapping, external);
        } catch (SQLException error) {
            throw new MigrationException("Identity preview failed; no rows were changed", error);
        } finally { rollback(connection); }
    }

    Result apply(Connection connection, String schemaName, IdentityMapping mapping, String reviewedPlanDigest,
                 EvidenceProbe evidenceProbe) throws MigrationException {
        boolean commitAttempted = false;
        try {
            if (reviewedPlanDigest == null || !reviewedPlanDigest.matches("[0-9a-f]{64}")) {
                throw new MigrationException("Apply requires the exact digest of a reviewed dry-run plan");
            }
            connection.setAutoCommit(false);
            connection.setReadOnly(false);
            // Every covered table is held ACCESS EXCLUSIVE before the first data
            // read. READ COMMITTED deliberately takes a fresh snapshot after the
            // advisory/table-lock waits: a SERIALIZABLE snapshot established by
            // SELECT pg_advisory_xact_lock before waiting can miss a just-started
            // node. Table locks provide the offline transaction's serialization.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            acquireMaintenanceLock(connection, schemaName);
            SchemaInspector.Schema schema = checkedSchema(connection, schemaName);
            mapping.verifyEvidenceUnchanged();
            IdentityExternalCoverage.Evidence external = evidenceProbe.inspect();
            if (!external.blockers().isEmpty()) throw new MigrationException("External identity adapters remain blocked: " + external.blockers());
            TreeSet<String> active = new TreeSet<>();
            activeStateBlockers(connection, schema, active);
            if (!active.isEmpty()) throw new MigrationException("Identity migration requires stopped authorities: " + active);
            Result prior = previousReceipt(connection, schema, mapping, reviewedPlanDigest);
            if (prior != null) { connection.rollback(); return prior; }

            Plan plan = buildPlan(connection, schema, mapping, external);
            if (!plan.ready()) throw new MigrationException("Identity plan is blocked: " + plan.blockers());
            if (!reviewedPlanDigest.equals(plan.digest())) {
                throw new MigrationException("Database, schema policy, mapping or external inventory changed since review; create a new dry-run plan");
            }
            for (Update update : plan.updates()) applyUpdate(connection, update);
            beforeCommitFaultHook.run();
            verifyResult(connection, schema, plan);
            mapping.verifyEvidenceUnchanged();
            IdentityExternalCoverage.Evidence finalExternal = evidenceProbe.inspect();
            if (!external.digest().equals(finalExternal.digest()) || !finalExternal.blockers().isEmpty()) {
                throw new MigrationException("External identity evidence changed during apply; Core changes were rolled back");
            }
            insertReceipt(connection, schema, mapping, plan);
            commitAttempted = true;
            connection.commit();
            return new Result("CORE_DATABASE_APPLIED", plan.updates().size(), plan.digest());
        } catch (MigrationException error) {
            rollback(connection);
            throw error;
        } catch (Exception error) {
            rollback(connection);
            throw new MigrationException(commitAttempted
                    ? "Identity commit outcome is uncertain; retry the same operation and original reviewed plan to inspect its atomic receipt"
                    : "Identity apply failed; the transaction was rolled back", error);
        }
    }

    private SchemaInspector.Schema checkedSchema(Connection connection, String schemaName) throws SQLException, MigrationException {
        SchemaInspector.Schema schema = inspector.inspect(connection, schemaName, SchemaManifest.tables());
        inspector.requireCompatible(schema, schema, SchemaManifest.tables(), SchemaManifest.uniqueKeys(), SchemaManifest.requiredIndexes());
        policy.requireComplete(schema);
        return schema;
    }

    private Plan buildPlan(Connection connection, SchemaInspector.Schema schema, IdentityMapping mapping,
                           IdentityExternalCoverage.Evidence external) throws SQLException, MigrationException {
        TreeSet<String> blockers = new TreeSet<>(external.blockers());
        activeStateBlockers(connection, schema, blockers);
        rejectOverlappingReceipts(connection, schema, mapping, blockers);
        externalDatabaseTables(connection, schema, blockers);
        List<Update> updates = new ArrayList<>();
        Map<String, TableProof> proofs = new LinkedHashMap<>();
        JsonArray tableReports = new JsonArray();
        boolean[] sourceProfile = {false};
        for (String name : SchemaManifest.tables()) {
            SchemaInspector.Table table = schema.tables().get(name);
            TreeMap<String, Long> counts = new TreeMap<>();
            TreeMap<String, Long> retained = new TreeMap<>();
            List<String> beforeHashes = new ArrayList<>();
            List<String> afterHashes = new ArrayList<>();
            String sql = "SELECT " + columns(table) + " FROM " + table.qualifiedName();
            try (Statement query = connection.createStatement()) {
                query.setFetchSize(1_000);
                try (ResultSet rows = query.executeQuery(sql)) {
                    while (rows.next()) {
                        if (beforeHashes.size() >= MAXIMUM_ROWS_PER_TABLE) throw new MigrationException("Identity review bound exceeded in " + name);
                        Map<String, Object> raw = readRow(rows, table);
                        Map<String, String> before = canonicalRow(raw, table);
                        Map<String, String> after = new LinkedHashMap<>(before);
                        boolean affected = owns(before, table, mapping.source().toString());
                        if (name.equals("rpg_profiles") && affected) sourceProfile[0] = true;
                        Map<String, String> changes = new LinkedHashMap<>();
                        for (SchemaInspector.Column column : table.columns()) {
                            String value = before.get(column.name());
                            IdentityColumns.Policy rule = policy.column(name, column.name()).policy();
                            String replacement = transform(name, column.name(), rule, value, before, mapping, affected, blockers);
                            if (!java.util.Objects.equals(value, replacement)) {
                                changes.put(column.name(), replacement);
                                after.put(column.name(), replacement);
                                counts.merge(column.name(), 1L, Long::sum);
                            } else if (value != null && value.toLowerCase(java.util.Locale.ROOT).contains(mapping.source().toString())
                                    && Set.of(IdentityColumns.Policy.HISTORY, IdentityColumns.Policy.OPERATION_ID,
                                    IdentityColumns.Policy.MIGRATION_AUDIT).contains(rule)) {
                                retained.merge(column.name(), 1L, Long::sum);
                            }
                        }
                        if (affected && (name.equals("rpg_pet_progress") || name.equals("rpg_pet_revival_intents"))) {
                            blockers.add("MYPET_AUTHORITY_ADAPTER_REQUIRED:" + name);
                        }
                        if (affected && name.equals("rpg_equipment_states")
                                // Exact terminal states from EquipmentLifecycle.isTerminal.
                                // SACRIFICED remains a live transition; unknown names are never terminal by inference.
                                && !Set.of("SALVAGED", "LEGACY_EXTRACTED", "DESTROYED").contains(before.get("state"))) {
                            blockers.add("SIGNED_EQUIPMENT_ADAPTER_REQUIRED:" + name);
                        }
                        if (!changes.isEmpty()) {
                            if (updates.size() >= MAXIMUM_CHANGED_ROWS) throw new MigrationException("Identity change bound exceeds 250000 rows; a reviewed bounded migration adapter is required");
                            Map<String, Object> key = new LinkedHashMap<>();
                            for (String keyColumn : table.primaryKey()) key.put(keyColumn, raw.get(keyColumn));
                            updates.add(new Update(table, Collections.unmodifiableMap(key), Collections.unmodifiableMap(changes)));
                        }
                        beforeHashes.add(rowHash(before));
                        afterHashes.add(rowHash(after));
                    }
                }
            }
            TableProof proof = new TableProof(beforeHashes.size(), tableHash(beforeHashes), tableHash(afterHashes), Map.copyOf(counts));
            proofs.put(name, proof);
            JsonObject report = new JsonObject();
            report.addProperty("table", name); report.addProperty("rows", proof.rows());
            report.addProperty("before_sha256", proof.before()); report.addProperty("expected_after_sha256", proof.after());
            JsonArray columns = new JsonArray();
            for (SchemaInspector.Column column : table.columns()) {
                JsonObject field = new JsonObject();
                field.addProperty("column", column.name());
                field.addProperty("family", column.family().name());
                field.addProperty("policy", policy.column(name, column.name()).policy().name());
                field.addProperty("changed_rows", counts.getOrDefault(column.name(), 0L));
                field.addProperty("retained_historical_source_refs", retained.getOrDefault(column.name(), 0L));
                columns.add(field);
            }
            report.add("columns", columns);
            tableReports.add(report);
        }
        if (!sourceProfile[0]) blockers.add("SOURCE_PROFILE_ABSENT");
        String beforeDigest = combined(proofs, false);
        String afterDigest = combined(proofs, true);
        JsonObject document = new JsonObject();
        document.addProperty("schema_version", 1);
        document.addProperty("scope", "CORE_DATABASE_ONLY");
        document.addProperty("operation_id", mapping.operationId().toString());
        document.addProperty("source_uuid", mapping.source().toString());
        document.addProperty("target_uuid", mapping.target().toString());
        document.addProperty("source_label", mapping.sourceLabel());
        document.addProperty("target_label", mapping.targetLabel());
        document.addProperty("verified_by", mapping.verifiedBy());
        document.addProperty("mapping_sha256", mapping.digest());
        document.addProperty("evidence_sha256", mapping.evidenceSha256());
        document.addProperty("column_policy_sha256", policy.digest());
        document.addProperty("database_locator_sha256", IdentityJson.hash(connection.getMetaData().getURL()));
        document.addProperty("database_catalog", connection.getCatalog());
        document.addProperty("database_schema", schema.name());
        document.addProperty("before_sha256", beforeDigest);
        document.addProperty("expected_after_sha256", afterDigest);
        document.addProperty("changed_rows", updates.size());
        document.addProperty("core_apply_ready", blockers.isEmpty());
        document.addProperty("full_server_identity_cutover_verified", false);
        JsonArray issues = new JsonArray(); blockers.forEach(issues::add); document.add("blockers", issues);
        document.add("tables", tableReports);
        document.add("external_coverage", external.report());
        String digest = IdentityJson.hash(IdentityJson.canonical(document));
        document.addProperty("plan_sha256", digest);
        return new Plan(document, digest, List.copyOf(updates), Map.copyOf(proofs), List.copyOf(blockers), beforeDigest, afterDigest);
    }

    private boolean owns(Map<String, String> row, SchemaInspector.Table table, String uuid) {
        for (SchemaInspector.Column column : table.columns()) {
            String value = row.get(column.name());
            if (value == null) continue;
            IdentityColumns.Policy rule = policy.column(table.name(), column.name()).policy();
            if (rule == IdentityColumns.Policy.PLAYER && value.equals(uuid)) return true;
            if (rule == IdentityColumns.Policy.PLAYER_LIST && List.of(value.split(",", -1)).contains(uuid)) return true;
        }
        return false;
    }

    private String transform(String table, String column, IdentityColumns.Policy rule, String value,
                             Map<String, String> row, IdentityMapping mapping, boolean affected,
                             Set<String> blockers) throws MigrationException {
        if (value == null) return null;
        String source = mapping.source().toString();
        String target = mapping.target().toString();
        String location = table + "." + column;
        switch (rule) {
            case PLAYER -> {
                if (value.equals(target)) blockers.add("TARGET_IDENTITY_COLLISION:" + location);
                return value.equals(source) ? target : value;
            }
            case PLAYER_SCOPE -> {
                if (value.equals("global")) return value;
                if (!value.startsWith("player:")) { blockers.add("UNKNOWN_PLAYER_SCOPE:" + location); return value; }
                String player = value.substring(7);
                IdentityMapping.uuid(player);
                if (player.equals(target)) blockers.add("TARGET_IDENTITY_COLLISION:" + location);
                return player.equals(source) ? "player:" + target : value;
            }
            case PLAYER_LIST -> {
                List<UUID> players = new ArrayList<>();
                for (String player : value.split(",", -1)) players.add(IdentityMapping.uuid(player));
                if (players.isEmpty() || players.size() > 4 || new java.util.HashSet<>(players).size() != players.size()) {
                    blockers.add("INVALID_TEAM_IDENTITY:" + location); return value;
                }
                if (players.contains(mapping.target())) blockers.add("TARGET_IDENTITY_COLLISION:" + location);
                if (!players.contains(mapping.source())) return value;
                players.replaceAll(player -> player.equals(mapping.source()) ? mapping.target() : player);
                return players.stream().sorted().map(UUID::toString).collect(Collectors.joining(","));
            }
            case MILESTONE -> {
                String expected = "local-milestone:" + row.get("player_uuid") + ":" + row.get("ascension") + ":" + row.get("milestone_index");
                if (!value.equals(expected)) { blockers.add("UNRECOGNIZED_MILESTONE_IDENTITY:" + location); return value; }
                return row.get("player_uuid").equals(source)
                        ? "local-milestone:" + target + ":" + row.get("ascension") + ":" + row.get("milestone_index") : value;
            }
            case ROLE_SUBJECT -> {
                if (!"ROLE_GRANT".equals(row.get("action"))) return value;
                String[] parts = value.split(":", -1);
                if (parts.length < 1 || parts.length > 2 || parts[0].length() != 36
                        || parts.length == 2 && !parts[1].matches("[A-Z_]{2,24}")) {
                    blockers.add("UNRECOGNIZED_ROLE_SUBJECT:" + location); return value;
                }
                IdentityMapping.uuid(parts[0]);
                if (parts[0].equals(target)) blockers.add("TARGET_IDENTITY_COLLISION:" + location);
                return parts[0].equals(source) ? target + (parts.length == 2 ? ":" + parts[1] : "") : value;
            }
            case DUNGEON_EVENT -> {
                if (!"dungeon.completion".equals(row.get("aggregate_type"))) {
                    blockers.add("OUTBOX_PAYLOAD_ADAPTER_REQUIRED:" + row.get("aggregate_type")); return value;
                }
                try {
                    JsonObject event = IdentityJson.parse(value);
                    if (!event.keySet().equals(Set.of("runId", "player", "dungeon"))) throw new IllegalArgumentException();
                    String run = IdentityJson.text(event, "runId", 36);
                    String player = IdentityJson.text(event, "player", 36);
                    IdentityMapping.uuid(run); IdentityMapping.uuid(player);
                    IdentityJson.text(event, "dungeon", 64);
                    if (!run.equals(row.get("aggregate_id"))) throw new IllegalArgumentException();
                    if (player.equals(target)) blockers.add("TARGET_IDENTITY_COLLISION:" + location);
                    if (!player.equals(source)) return value;
                    event.addProperty("player", target);
                    return IdentityJson.canonical(event);
                } catch (RuntimeException | MigrationException | java.io.IOException malformed) {
                    blockers.add("UNRECOGNIZED_DUNGEON_EVENT:" + location); return value;
                }
            }
            case OPAQUE_ASSET -> {
                if (value.isBlank()) return value;
                boolean historical = table.equals("rpg_market_deliveries") && "DELIVERED".equals(row.get("state"))
                        || table.equals("rpg_market_listings") && Set.of("SOLD", "CANCELLED", "EXPIRED").contains(row.get("state"))
                        || table.equals("rpg_dungeon_ticket_escrows") && Set.of("CONSUMED", "RETURNED").contains(row.get("state"));
                if (table.equals("rpg_support_payment_orders")) {
                    if (affected) blockers.add("SIGNED_PAYMENT_RECEIPT_ADAPTER_REQUIRED:" + location);
                } else if (!historical) {
                    // An item owned by someone else may still name this player as
                    // crafter/bound owner. Base64 substring search cannot prove absence.
                    blockers.add("OPAQUE_ASSET_ADAPTER_REQUIRED:" + location);
                }
                return value;
            }
            case VALUE -> {
                if (value.toLowerCase(java.util.Locale.ROOT).contains(source)
                        || value.toLowerCase(java.util.Locale.ROOT).contains(target)) {
                    blockers.add("UNCLASSIFIED_EMBEDDED_UUID:" + location);
                }
                return value;
            }
            case HISTORY, OPERATION_ID, SESSION_TOKEN, MIGRATION_AUDIT -> { return value; }
        }
        throw new MigrationException("Unhandled identity policy: " + rule);
    }

    private void activeStateBlockers(Connection connection, SchemaInspector.Schema schema, Set<String> blockers) throws SQLException {
        Instant now = databaseNow(connection);
        for (String table : List.of("rpg_node_leases", "rpg_session_leases")) {
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM " + schema.tables().get(table).qualifiedName()
                    + " WHERE lease_expires_at > ?")) {
                query.setTimestamp(1, Timestamp.from(now));
                try (ResultSet result = query.executeQuery()) { result.next(); if (result.getLong(1) > 0) blockers.add("ACTIVE_AUTHORITY:" + table); }
            }
        }
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM " + schema.tables().get("rpg_outbox").qualifiedName()
                + " WHERE processed_at IS NULL AND claim_expires_at > ?")) {
            query.setTimestamp(1, Timestamp.from(now));
            try (ResultSet result = query.executeQuery()) { result.next(); if (result.getLong(1) > 0) blockers.add("ACTIVE_OUTBOX_CLAIM"); }
        }
        try (var query = connection.createStatement(); var result = query.executeQuery("SELECT COUNT(*) FROM "
                + schema.tables().get("rpg_dungeon_energy_reservations").qualifiedName() + " WHERE state = 'RESERVED'")) {
            result.next(); if (result.getLong(1) > 0) blockers.add("UNRECOVERED_DUNGEON_RESERVATIONS");
        }
        try (var query = connection.createStatement(); var result = query.executeQuery("SELECT COUNT(*) FROM "
                + schema.tables().get("rpg_market_deliveries").qualifiedName() + " WHERE state IN ('CLAIMED','STAGING')")) {
            // An expired reservation is still an unresolved operation during
            // identity maintenance; STAGING can already have a physical receipt.
            // Shutdown or expiry never proves that an item was not delivered.
            result.next(); if (result.getLong(1) > 0) blockers.add("UNRESOLVED_MAILBOX_CLAIMS");
        }
    }

    private void externalDatabaseTables(Connection connection, SchemaInspector.Schema schema, Set<String> blockers) throws SQLException {
        try (var tables = connection.getMetaData().getTables(connection.getCatalog(), schema.name(), "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT);
                if (!schema.tables().containsKey(name)) blockers.add("EXTERNAL_DATABASE_TABLE_ADAPTER_REQUIRED:" + name);
            }
        }
    }

    private void rejectOverlappingReceipts(Connection connection, SchemaInspector.Schema schema,
                                          IdentityMapping mapping, Set<String> blockers) throws SQLException {
        try (var query = connection.prepareStatement("SELECT operation_id FROM " + schema.tables().get(RECEIPTS).qualifiedName()
                + " WHERE operation_id = ? OR source_uuid IN (?, ?) OR target_uuid IN (?, ?)")) {
            query.setString(1, mapping.operationId().toString());
            query.setString(2, mapping.source().toString()); query.setString(3, mapping.target().toString());
            query.setString(4, mapping.source().toString()); query.setString(5, mapping.target().toString());
            try (var result = query.executeQuery()) { if (result.next()) blockers.add("EXISTING_IDENTITY_MIGRATION_RECEIPT"); }
        }
    }

    private Result previousReceipt(Connection connection, SchemaInspector.Schema schema, IdentityMapping mapping,
                                   String reviewedDigest) throws SQLException, MigrationException {
        try (var query = connection.prepareStatement("SELECT source_uuid,target_uuid,mapping_sha256,plan_sha256,changed_rows FROM "
                + schema.tables().get(RECEIPTS).qualifiedName() + " WHERE operation_id = ?")) {
            query.setString(1, mapping.operationId().toString());
            try (var result = query.executeQuery()) {
                if (!result.next()) return null;
                if (!result.getString("source_uuid").equals(mapping.source().toString())
                        || !result.getString("target_uuid").equals(mapping.target().toString())
                        || !result.getString("mapping_sha256").equals(mapping.digest())
                        || !result.getString("plan_sha256").equals(reviewedDigest)) {
                    throw new MigrationException("Identity operation ID was reused with different reviewed content");
                }
                return new Result("ALREADY_APPLIED", result.getLong("changed_rows"), reviewedDigest);
            }
        }
    }

    private void applyUpdate(Connection connection, Update update) throws SQLException, MigrationException {
        String set = update.changes().keySet().stream().map(key -> SchemaInspector.quote(key) + " = ?").collect(Collectors.joining(", "));
        String where = update.key().keySet().stream().map(key -> SchemaInspector.quote(key) + " = ?").collect(Collectors.joining(" AND "));
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + update.table().qualifiedName() + " SET " + set + " WHERE " + where)) {
            int index = 1;
            for (String value : update.changes().values()) statement.setString(index++, value);
            for (Object value : update.key().values()) statement.setObject(index++, value);
            if (statement.executeUpdate() != 1) throw new MigrationException("Identity row changed while migrating: " + update.table().name());
        }
    }

    private void verifyResult(Connection connection, SchemaInspector.Schema schema, Plan plan) throws SQLException, MigrationException {
        for (String name : SchemaManifest.tables()) {
            SchemaInspector.Table table = schema.tables().get(name);
            List<String> hashes = new ArrayList<>();
            try (var query = connection.createStatement(); var result = query.executeQuery("SELECT " + columns(table) + " FROM " + table.qualifiedName())) {
                while (result.next()) {
                    if (hashes.size() >= MAXIMUM_ROWS_PER_TABLE) throw new MigrationException("Identity proof bound exceeded in " + name);
                    hashes.add(rowHash(canonicalRow(readRow(result, table), table)));
                }
            }
            TableProof expected = plan.proofs().get(name);
            if (hashes.size() != expected.rows() || !tableHash(hashes).equals(expected.after())) {
                throw new MigrationException("Post-update full-row proof differs from the reviewed identity-only transformation: " + name);
            }
        }
    }

    private void insertReceipt(Connection connection, SchemaInspector.Schema schema, IdentityMapping mapping, Plan plan) throws SQLException {
        try (var insert = connection.prepareStatement("INSERT INTO " + schema.tables().get(RECEIPTS).qualifiedName() + """
                 (operation_id,source_uuid,target_uuid,source_label,target_label,verified_by,evidence_sha256,
                  mapping_sha256,plan_sha256,before_sha256,after_sha256,changed_rows)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setString(1, mapping.operationId().toString()); insert.setString(2, mapping.source().toString());
            insert.setString(3, mapping.target().toString()); insert.setString(4, mapping.sourceLabel());
            insert.setString(5, mapping.targetLabel()); insert.setString(6, mapping.verifiedBy());
            insert.setString(7, mapping.evidenceSha256()); insert.setString(8, mapping.digest());
            insert.setString(9, plan.digest()); insert.setString(10, plan.beforeDigest());
            insert.setString(11, plan.afterDigest()); insert.setLong(12, plan.updates().size());
            insert.executeUpdate();
        }
    }

    private void acquireMaintenanceLock(Connection connection, String schemaName) throws SQLException, MigrationException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (product.equals("H2")) return; // CLI accepts only an offline embedded file; no AUTO_SERVER/TCP.
        if (!product.equals("PostgreSQL")) throw new MigrationException("Unsupported identity migration database");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '30s'");
            statement.execute("SELECT pg_advisory_xact_lock(" + MigrationAdvisoryLock.NAMESPACE + ", " + MigrationAdvisoryLock.RESOURCE + ")");
            // Lock the node table first: startup cannot insert a new owner between
            // the offline check and commit. Then freeze every covered authority.
            List<String> tables = new ArrayList<>(SchemaManifest.tables());
            tables.remove("rpg_node_leases"); tables.addFirst("rpg_node_leases");
            for (String table : tables) statement.execute("LOCK TABLE " + SchemaInspector.quote(schemaName) + "."
                    + SchemaInspector.quote(table) + " IN ACCESS EXCLUSIVE MODE");
        }
    }

    private static Instant databaseNow(Connection connection) throws SQLException {
        if (connection.getMetaData().getDatabaseProductName().equals("H2")) return Instant.now();
        try (var query = connection.createStatement(); var result = query.executeQuery("SELECT clock_timestamp()")) {
            result.next(); return result.getTimestamp(1).toInstant();
        }
    }

    private static Map<String, Object> readRow(ResultSet rows, SchemaInspector.Table table) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < table.columns().size(); index++) {
            SchemaInspector.Column column = table.columns().get(index);
            Object value = switch (column.family()) {
                case STRING -> rows.getString(index + 1);
                case INT32, INT64, DECIMAL -> rows.getBigDecimal(index + 1);
                case BOOLEAN -> { boolean bool = rows.getBoolean(index + 1); yield rows.wasNull() ? null : bool; }
                case DATE -> rows.getDate(index + 1);
                case TIMESTAMP -> rows.getTimestamp(index + 1);
            };
            values.put(column.name(), value);
        }
        return values;
    }

    private static Map<String, String> canonicalRow(Map<String, Object> raw, SchemaInspector.Table table) {
        Map<String, String> row = new LinkedHashMap<>();
        for (SchemaInspector.Column column : table.columns()) {
            Object value = raw.get(column.name());
            String canonical = switch (value) {
                case null -> null;
                case BigDecimal number -> number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
                case Timestamp timestamp -> timestamp.toLocalDateTime().toString();
                default -> value.toString();
            };
            row.put(column.name(), canonical);
        }
        return row;
    }

    private static String columns(SchemaInspector.Table table) {
        return table.columns().stream().map(column -> SchemaInspector.quote(column.name())).collect(Collectors.joining(", "));
    }

    private static String rowHash(Map<String, String> row) {
        JsonObject json = new JsonObject(); row.forEach(json::addProperty);
        return IdentityJson.hash(IdentityJson.canonical(json));
    }

    private static String tableHash(List<String> rows) {
        rows.sort(Comparator.naturalOrder());
        return IdentityJson.hash(rows.size() + "\n" + String.join("\n", rows));
    }

    private static String combined(Map<String, TableProof> proofs, boolean after) {
        return IdentityJson.hash(new TreeMap<>(proofs).entrySet().stream().map(entry -> entry.getKey() + ":"
                + entry.getValue().rows() + ":" + (after ? entry.getValue().after() : entry.getValue().before()))
                .collect(Collectors.joining("\n")));
    }

    private static void rollback(Connection connection) {
        try { if (!connection.isClosed()) connection.rollback(); } catch (SQLException ignored) { /* Original failure remains authoritative. */ }
    }
}
