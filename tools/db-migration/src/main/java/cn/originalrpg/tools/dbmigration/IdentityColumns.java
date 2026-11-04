package cn.originalrpg.tools.dbmigration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Checked-in semantic classification for every column. Unknown columns never default to "safe". */
final class IdentityColumns {
    enum Policy {
        PLAYER, PLAYER_LIST, PLAYER_SCOPE, MILESTONE, DUNGEON_EVENT, ROLE_SUBJECT,
        OPAQUE_ASSET, HISTORY, OPERATION_ID, SESSION_TOKEN, VALUE, MIGRATION_AUDIT
    }
    record Column(Policy policy, SchemaInspector.TypeFamily family) { }
    private final Map<String, Map<String, Column>> tables;
    private final String digest;

    private IdentityColumns(Map<String, Map<String, Column>> tables, String digest) {
        this.tables = Map.copyOf(tables);
        this.digest = digest;
    }

    static IdentityColumns load() throws MigrationException {
        var resource = IdentityColumns.class.getResourceAsStream("/identity-columns-v1.tsv");
        if (resource == null) throw new MigrationException("Identity column policy is missing from the tool distribution");
        Map<String, Map<String, Column>> tables = new LinkedHashMap<>();
        StringBuilder canonical = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length != 4 || !parts[0].matches("rpg_[a-z_]+") || !parts[1].matches("[a-z_][a-z_0-9]*")) {
                    throw new MigrationException("Invalid identity column policy row");
                }
                Column column = new Column(Policy.valueOf(parts[2]), SchemaInspector.TypeFamily.valueOf(parts[3]));
                if (tables.computeIfAbsent(parts[0], ignored -> new LinkedHashMap<>()).put(parts[1], column) != null) {
                    throw new MigrationException("Duplicate identity column policy: " + parts[0] + "." + parts[1]);
                }
                canonical.append(line).append('\n');
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new MigrationException("Could not read identity column policy", error);
        }
        tables.replaceAll((table, columns) -> Map.copyOf(columns));
        return new IdentityColumns(tables, IdentityJson.hash(canonical.toString()));
    }

    void requireComplete(SchemaInspector.Schema schema) throws MigrationException {
        if (!tables.keySet().equals(schema.tables().keySet())) {
            throw new MigrationException("Identity policy table coverage differs from the actual schema");
        }
        for (SchemaInspector.Table table : schema.tables().values()) {
            Set<String> actual = new TreeSet<>();
            for (SchemaInspector.Column column : table.columns()) {
                actual.add(column.name());
                Column rule = tables.get(table.name()).get(column.name());
                if (rule == null || rule.family() != column.family()) {
                    throw new MigrationException("Unclassified or changed identity column: " + table.name() + "." + column.name());
                }
                if (rule.policy() != Policy.VALUE && rule.policy() != Policy.MIGRATION_AUDIT
                        && column.family() != SchemaInspector.TypeFamily.STRING) {
                    throw new MigrationException("Identity text policy applied to a non-text column: " + table.name() + "." + column.name());
                }
            }
            if (!actual.equals(tables.get(table.name()).keySet())) {
                throw new MigrationException("Identity policy contains absent columns for " + table.name());
            }
        }
    }

    Column column(String table, String column) { return tables.get(table).get(column); }
    String digest() { return digest; }
}
