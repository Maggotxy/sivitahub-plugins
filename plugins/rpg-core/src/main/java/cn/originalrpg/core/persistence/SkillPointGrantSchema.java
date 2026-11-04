package cn.originalrpg.core.persistence;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Prevents an old range-based audit schema from silently losing milestone replay protection. */
final class SkillPointGrantSchema {
    private static final String TABLE = "rpg_skill_point_grants";
    private static final Set<String> CURRENT = Set.of(
            "milestone_key", "receipt", "player_uuid", "points", "ascension",
            "milestone_index", "to_local_level", "granted_at");
    private static final Set<String> LEGACY = Set.of(
            "source", "idempotency_key", "player_uuid", "points", "ascension",
            "from_local_level", "to_local_level", "granted_at");

    private SkillPointGrantSchema() { }

    /** Runs after the PostgreSQL migration advisory lock, before any runtime schema changes. */
    static void prepare(Connection connection, boolean postgres) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String table = metadata.storesUpperCaseIdentifiers() ? TABLE.toUpperCase(Locale.ROOT) : TABLE;
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        Set<String> columns = new HashSet<>();
        try (ResultSet result = metadata.getColumns(catalog, schema, table, null)) {
            while (result.next()) {
                // Metadata treats '_' as a pattern; ignore similarly named tables.
                if (table.equalsIgnoreCase(result.getString("TABLE_NAME"))) {
                    columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
        }
        if (columns.isEmpty()) return; // The normal migration creates a new canonical table.
        if (columns.equals(CURRENT)) {
            if (!hasMilestonePrimaryKey(metadata, catalog, schema, table)) {
                throw reviewRequired("the milestone_key primary key is missing");
            }
            return;
        }
        if (!columns.equals(LEGACY)) {
            throw reviewRequired("the existing table has an unrecognized column shape");
        }
        try (Statement statement = connection.createStatement()) {
            if (postgres) {
                // Hold out old writers while proving that a rebuild cannot remove audit rows.
                statement.execute("LOCK TABLE rpg_skill_point_grants IN ACCESS EXCLUSIVE MODE");
            }
            try (ResultSet result = statement.executeQuery("SELECT 1 FROM rpg_skill_point_grants LIMIT 1")) {
                if (result.next()) {
                    throw reviewRequired("the legacy range-based table contains historical grants");
                }
            }
            // There are no rows to interpret or preserve. The canonical CREATE below
            // recreates this table; no profile XP or skill-point balance is modified.
            statement.execute("DROP TABLE rpg_skill_point_grants");
        }
    }

    private static boolean hasMilestonePrimaryKey(
            DatabaseMetaData metadata, String catalog, String schema, String table
    ) throws SQLException {
        Set<String> primaryKey = new HashSet<>();
        try (ResultSet result = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (result.next()) {
                String column = result.getString("COLUMN_NAME");
                if (column != null) primaryKey.add(column.toLowerCase(Locale.ROOT));
            }
        }
        return primaryKey.equals(Set.of("milestone_key"));
    }

    private static SQLException reviewRequired(String reason) {
        return new SQLException("Skill-point schema migration stopped: " + reason
                + ". Existing audit rows and profile balances were not changed. "
                + "Review docs/operations/PROGRESSION_RECEIPT_MIGRATION.md before upgrading; "
                + "do not delete historical grants or infer identities from current levels.");
    }
}
