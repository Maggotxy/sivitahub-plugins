package cn.originalrpg.tools.dbmigration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class SchemaInspector {
    enum TypeFamily {
        STRING, INT32, INT64, DECIMAL, BOOLEAN, DATE, TIMESTAMP
    }

    record Column(
            String name,
            int jdbcType,
            String databaseType,
            int size,
            int scale,
            boolean nullable,
            int ordinal,
            TypeFamily family
    ) {
    }

    record Table(
            String schema,
            String name,
            List<Column> columns,
            List<String> primaryKey,
            List<List<String>> uniqueKeys,
            List<List<String>> nonUniqueIndexes
    ) {
        String qualifiedName() {
            return quote(schema) + "." + quote(name);
        }

        Column column(String columnName) {
            return columns.stream()
                    .filter(candidate -> candidate.name().equals(columnName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown column " + columnName));
        }
    }

    record Schema(String name, Map<String, Table> tables) {
    }

    Schema inspect(Connection connection, String requestedSchema, List<String> expectedTables)
            throws SQLException, MigrationException {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = resolveSchema(metadata, requestedSchema);
        Set<String> discovered = discoverRpgTables(connection, metadata, schema);
        Set<String> expected = new LinkedHashSet<>(expectedTables);
        if (!discovered.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(discovered);
            Set<String> unexpected = new LinkedHashSet<>(discovered);
            unexpected.removeAll(expected);
            throw new MigrationException(
                    "RPG schema manifest mismatch in " + schema
                            + "; missing=" + missing + "; unexpected=" + unexpected
            );
        }

        Map<String, Table> tables = new LinkedHashMap<>();
        for (String tableName : expectedTables) {
            List<Column> columns = columns(connection, metadata, schema, tableName);
            if (columns.isEmpty()) {
                throw new MigrationException("No columns found for " + schema + "." + tableName);
            }
            List<String> primaryKey = primaryKey(connection, metadata, schema, tableName);
            if (primaryKey.isEmpty()) {
                throw new MigrationException("Table has no primary key: " + schema + "." + tableName);
            }
            List<List<String>> uniqueKeys = uniqueKeys(connection, metadata, schema, tableName);
            List<List<String>> nonUniqueIndexes = nonUniqueIndexes(connection, metadata, schema, tableName);
            tables.put(tableName, new Table(
                    schema, tableName, columns, primaryKey, uniqueKeys, nonUniqueIndexes
            ));
        }
        return new Schema(schema, Map.copyOf(tables));
    }

    void requireCompatible(
            Schema source, Schema target, List<String> tableOrder,
            Map<String, List<List<String>>> requiredUniqueKeys,
            Map<String, List<List<String>>> requiredIndexes
    )
            throws MigrationException {
        for (String tableName : tableOrder) {
            Table sourceTable = source.tables().get(tableName);
            Table targetTable = target.tables().get(tableName);
            Map<String, Column> sourceColumns = byName(sourceTable.columns());
            Map<String, Column> targetColumns = byName(targetTable.columns());
            if (!sourceColumns.keySet().equals(targetColumns.keySet())) {
                throw new MigrationException("Column set mismatch for " + tableName);
            }
            for (String columnName : sourceColumns.keySet()) {
                requireCompatible(sourceColumns.get(columnName), targetColumns.get(columnName), tableName);
            }
            if (!sourceTable.primaryKey().equals(targetTable.primaryKey())) {
                throw new MigrationException("Primary key mismatch for " + tableName);
            }
            for (List<String> key : requiredUniqueKeys.getOrDefault(tableName, List.of())) {
                if (!sourceTable.uniqueKeys().contains(key)) {
                    throw new MigrationException("Source is missing required unique key " + tableName + key);
                }
                if (!targetTable.uniqueKeys().contains(key)) {
                    throw new MigrationException("Target is missing required unique key " + tableName + key);
                }
            }
            for (List<String> index : requiredIndexes.getOrDefault(tableName, List.of())) {
                if (!sourceTable.nonUniqueIndexes().contains(index)) {
                    throw new MigrationException("Source is missing required index " + tableName + index);
                }
                if (!targetTable.nonUniqueIndexes().contains(index)) {
                    throw new MigrationException("Target is missing required index " + tableName + index);
                }
            }
        }
    }

    static String quote(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Unsafe SQL identifier");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String resolveSchema(DatabaseMetaData metadata, String requested)
            throws SQLException, MigrationException {
        try (ResultSet result = metadata.getSchemas()) {
            while (result.next()) {
                String candidate = result.getString("TABLE_SCHEM");
                if (candidate != null && candidate.equalsIgnoreCase(requested)) return candidate;
            }
        }
        throw new MigrationException("Database schema does not exist: " + requested);
    }

    private static Set<String> discoverRpgTables(
            Connection connection, DatabaseMetaData metadata, String schema
    ) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (ResultSet result = metadata.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                String table = result.getString("TABLE_NAME");
                if (table != null && table.toLowerCase(Locale.ROOT).startsWith("rpg_")) {
                    tables.add(table.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tables;
    }

    private static List<Column> columns(
            Connection connection, DatabaseMetaData metadata, String schema, String table
    ) throws SQLException, MigrationException {
        List<Column> columns = new ArrayList<>();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), schema, table, "%")) {
            while (result.next()) {
                int jdbcType = result.getInt("DATA_TYPE");
                columns.add(new Column(
                        result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                        jdbcType,
                        result.getString("TYPE_NAME"),
                        result.getInt("COLUMN_SIZE"),
                        result.getInt("DECIMAL_DIGITS"),
                        result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        result.getInt("ORDINAL_POSITION"),
                        family(jdbcType)
                ));
            }
        }
        columns.sort(Comparator.comparingInt(Column::ordinal));
        return List.copyOf(columns);
    }

    private static List<String> primaryKey(
            Connection connection, DatabaseMetaData metadata, String schema, String table
    ) throws SQLException {
        Map<Short, String> ordered = new TreeMap<>();
        try (ResultSet result = metadata.getPrimaryKeys(connection.getCatalog(), schema, table)) {
            while (result.next()) {
                ordered.put(result.getShort("KEY_SEQ"), result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(ordered.values());
    }

    private static List<List<String>> uniqueKeys(
            Connection connection, DatabaseMetaData metadata, String schema, String table
    ) throws SQLException {
        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), schema, table, true, false)) {
            while (result.next()) {
                String indexName = result.getString("INDEX_NAME");
                String columnName = result.getString("COLUMN_NAME");
                if (indexName == null || columnName == null || result.getBoolean("NON_UNIQUE")) continue;
                indexes.computeIfAbsent(indexName, ignored -> new TreeMap<>())
                        .put(result.getShort("ORDINAL_POSITION"), columnName.toLowerCase(Locale.ROOT));
            }
        }
        return indexes.values().stream()
                .map(index -> List.copyOf(index.values()))
                .distinct()
                .toList();
    }

    private static List<List<String>> nonUniqueIndexes(
            Connection connection, DatabaseMetaData metadata, String schema, String table
    ) throws SQLException {
        Map<String, Map<Short, String>> indexes = new LinkedHashMap<>();
        try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), schema, table, false, false)) {
            while (result.next()) {
                String indexName = result.getString("INDEX_NAME");
                String columnName = result.getString("COLUMN_NAME");
                if (indexName == null || columnName == null || !result.getBoolean("NON_UNIQUE")) continue;
                indexes.computeIfAbsent(indexName, ignored -> new TreeMap<>())
                        .put(result.getShort("ORDINAL_POSITION"), columnName.toLowerCase(Locale.ROOT));
            }
        }
        return indexes.values().stream()
                .map(index -> List.copyOf(index.values()))
                .distinct()
                .toList();
    }

    private static Map<String, Column> byName(List<Column> columns) {
        Map<String, Column> result = new LinkedHashMap<>();
        columns.forEach(column -> result.put(column.name(), column));
        return result;
    }

    private static void requireCompatible(Column source, Column target, String table)
            throws MigrationException {
        String label = table + "." + source.name();
        if (source.family() != target.family()) {
            throw new MigrationException(
                    "Type-family mismatch for " + label + ": "
                            + source.databaseType() + " -> " + target.databaseType()
            );
        }
        if (source.nullable() != target.nullable()) {
            throw new MigrationException("Nullability mismatch for " + label);
        }
        if (source.family() == TypeFamily.STRING
                && source.size() > 0 && target.size() > 0 && target.size() < source.size()) {
            throw new MigrationException("Target string width is smaller for " + label);
        }
        if (source.family() == TypeFamily.DECIMAL
                && (target.size() < source.size() || target.scale() < source.scale())) {
            throw new MigrationException("Target numeric precision/scale is smaller for " + label);
        }
    }

    private static TypeFamily family(int jdbcType) throws MigrationException {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> TypeFamily.STRING;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> TypeFamily.INT32;
            case Types.BIGINT -> TypeFamily.INT64;
            case Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> TypeFamily.DECIMAL;
            case Types.BOOLEAN, Types.BIT -> TypeFamily.BOOLEAN;
            case Types.DATE -> TypeFamily.DATE;
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> TypeFamily.TIMESTAMP;
            default -> throw new MigrationException("Unsupported JDBC column type: " + jdbcType);
        };
    }
}
