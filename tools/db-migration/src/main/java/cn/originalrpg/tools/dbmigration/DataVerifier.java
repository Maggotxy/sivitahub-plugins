package cn.originalrpg.tools.dbmigration;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class DataVerifier {
    private static final Pattern CRITICAL_NUMERIC_NAME = Pattern.compile(
            "(?i)(?:^|_)(balance|amount|fee|price|gold|merit|credit|credits|paid|contribution|cost|delta|earned|granted|units|energy|experience)(?:_|$)"
    );
    private final List<String> tableOrder;
    private final Map<String, List<List<String>>> uniqueKeys;

    DataVerifier(List<String> tableOrder, Map<String, List<List<String>>> uniqueKeys) {
        this.tableOrder = List.copyOf(tableOrder);
        this.uniqueKeys = Map.copyOf(uniqueKeys);
    }

    record TableSnapshot(
            long rowCount,
            String primaryKeyDigest,
            String fullRowDigest,
            Map<String, BigDecimal> criticalNumericSums
    ) {
    }

    Map<String, TableSnapshot> snapshotAll(
            Connection connection, SchemaInspector.Schema schema, List<String> tableOrder
    ) throws SQLException, MigrationException {
        Map<String, TableSnapshot> snapshots = new LinkedHashMap<>();
        for (String tableName : tableOrder) {
            SchemaInspector.Table table = schema.tables().get(tableName);
            requireNoDuplicateKeys(connection, table, uniqueKeys.getOrDefault(tableName, List.of()));
            snapshots.put(tableName, snapshot(connection, table));
        }
        return Map.copyOf(snapshots);
    }

    void requireEqual(Map<String, TableSnapshot> source, Map<String, TableSnapshot> target)
            throws MigrationException {
        for (String table : tableOrder) {
            TableSnapshot left = source.get(table);
            TableSnapshot right = target.get(table);
            if (left == null || right == null) {
                throw new MigrationException("Missing verification snapshot for " + table);
            }
            if (left.rowCount() != right.rowCount()) {
                throw new MigrationException(
                        "Row-count mismatch for " + table + ": source=" + left.rowCount()
                                + ", target=" + right.rowCount()
                );
            }
            if (!left.primaryKeyDigest().equals(right.primaryKeyDigest())) {
                throw new MigrationException("Primary/unique identity digest mismatch for " + table);
            }
            if (!left.fullRowDigest().equals(right.fullRowDigest())) {
                throw new MigrationException("Full-row digest mismatch for " + table);
            }
            if (!left.criticalNumericSums().equals(right.criticalNumericSums())) {
                throw new MigrationException("Critical monetary/numeric total mismatch for " + table);
            }
        }
    }

    boolean equal(Map<String, TableSnapshot> source, Map<String, TableSnapshot> target) {
        try {
            requireEqual(source, target);
            return true;
        } catch (MigrationException ignored) {
            return false;
        }
    }

    long totalRows(Map<String, TableSnapshot> snapshots) {
        return snapshots.values().stream().mapToLong(TableSnapshot::rowCount).sum();
    }

    private TableSnapshot snapshot(Connection connection, SchemaInspector.Table table)
            throws SQLException, MigrationException {
        MessageDigest primaryDigest = digest();
        MessageDigest fullDigest = digest();
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        List<SchemaInspector.Column> numericColumns = table.columns().stream()
                .filter(DataVerifier::isCriticalNumeric)
                .toList();
        numericColumns.forEach(column -> sums.put(column.name(), BigDecimal.ZERO));

        String columns = table.columns().stream()
                .map(column -> SchemaInspector.quote(column.name()))
                .collect(java.util.stream.Collectors.joining(", "));
        String order = table.primaryKey().stream()
                .map(SchemaInspector::quote)
                .collect(java.util.stream.Collectors.joining(", "));
        String sql = "SELECT " + columns + " FROM " + table.qualifiedName() + " ORDER BY " + order;

        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (int index = 0; index < table.columns().size(); index++) {
            indexes.put(table.columns().get(index).name(), index + 1);
        }

        long rows = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(1_000);
            try (ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    updateLong(primaryDigest, rows);
                    updateLong(fullDigest, rows);
                    for (String keyColumn : table.primaryKey()) {
                        SchemaInspector.Column column = table.column(keyColumn);
                        updateValue(primaryDigest, canonical(result, indexes.get(keyColumn), column));
                    }
                    for (SchemaInspector.Column column : table.columns()) {
                        int index = indexes.get(column.name());
                        updateValue(fullDigest, column.name());
                        updateValue(fullDigest, canonical(result, index, column));
                    }
                    for (SchemaInspector.Column column : numericColumns) {
                        BigDecimal value = result.getBigDecimal(indexes.get(column.name()));
                        if (value != null) sums.compute(column.name(), (ignored, total) -> total.add(value));
                    }
                    rows++;
                }
            }
        }

        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        sums.forEach((name, value) -> normalized.put(name, normalize(value)));
        return new TableSnapshot(
                rows,
                HexFormat.of().formatHex(primaryDigest.digest()),
                HexFormat.of().formatHex(fullDigest.digest()),
                Map.copyOf(normalized)
        );
    }

    private static void requireNoDuplicateKeys(
            Connection connection, SchemaInspector.Table table, List<List<String>> additionalKeys
    ) throws SQLException, MigrationException {
        List<List<String>> keys = new ArrayList<>();
        keys.add(table.primaryKey());
        keys.addAll(additionalKeys);
        for (List<String> key : keys) {
            String columns = key.stream().map(SchemaInspector::quote)
                    .collect(java.util.stream.Collectors.joining(", "));
            String notNull = key.stream().map(column -> SchemaInspector.quote(column) + " IS NOT NULL")
                    .collect(java.util.stream.Collectors.joining(" AND "));
            String sql = "SELECT 1 FROM " + table.qualifiedName()
                    + " WHERE " + notNull
                    + " GROUP BY " + columns + " HAVING COUNT(*) > 1 FETCH FIRST 1 ROW ONLY";
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                if (result.next()) {
                    throw new MigrationException("Duplicate primary/unique key data in " + table.name() + key);
                }
            }
        }
    }

    private static boolean isCriticalNumeric(SchemaInspector.Column column) {
        boolean numeric = switch (column.family()) {
            case INT32, INT64, DECIMAL -> true;
            default -> false;
        };
        return numeric && CRITICAL_NUMERIC_NAME.matcher(column.name()).find();
    }

    private static String canonical(ResultSet result, int index, SchemaInspector.Column column)
            throws SQLException {
        return switch (column.family()) {
            case STRING -> result.getString(index);
            case INT32, INT64, DECIMAL -> {
                BigDecimal value = result.getBigDecimal(index);
                yield value == null ? null : normalize(value).toPlainString();
            }
            case BOOLEAN -> {
                boolean value = result.getBoolean(index);
                yield result.wasNull() ? null : Boolean.toString(value);
            }
            case DATE -> {
                Date value = result.getDate(index);
                yield value == null ? null : value.toLocalDate().toString();
            }
            case TIMESTAMP -> {
                Timestamp value = result.getTimestamp(index);
                yield value == null ? null : value.toLocalDateTime().toString();
            }
        };
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value.signum() == 0) return BigDecimal.ZERO;
        return value.stripTrailingZeros();
    }

    private static MessageDigest digest() throws MigrationException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 is not available", exception);
        }
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void updateValue(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
