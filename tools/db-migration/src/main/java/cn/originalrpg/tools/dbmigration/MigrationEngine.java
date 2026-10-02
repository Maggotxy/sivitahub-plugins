package cn.originalrpg.tools.dbmigration;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class MigrationEngine {
    /** 等待 RpgCore 迁移锁时的 SQL 报错标识，用于区分超时与其他失败。 */
    private static final String LOCK_TIMEOUT_STATE = "55P03";

    enum Outcome {
        DRY_RUN_EMPTY_TARGET,
        DRY_RUN_REPLACE,
        ALREADY_IDENTICAL,
        VERIFIED,
        MIGRATED
    }

    record Request(
            String sourceSchema,
            String targetSchema,
            int batchSize,
            boolean dryRun,
            boolean verifyOnly,
            boolean replaceExisting,
            long lockTimeoutMs
    ) {
        Request(
                String sourceSchema,
                String targetSchema,
                int batchSize,
                boolean dryRun,
                boolean verifyOnly,
                boolean replaceExisting
        ) {
            this(sourceSchema, targetSchema, batchSize, dryRun, verifyOnly, replaceExisting,
                    MigrationAdvisoryLock.DEFAULT_TIMEOUT_MS);
        }
    }

    record Result(Outcome outcome, long rows, int tables) {
    }

    private final List<String> tableOrder;
    private final Map<String, List<List<String>>> uniqueKeys;
    private final Map<String, List<List<String>>> requiredIndexes;
    private final SchemaInspector inspector;
    private final DataVerifier verifier;
    private final Consumer<String> output;

    MigrationEngine(Consumer<String> output) {
        this(
                SchemaManifest.tables(),
                SchemaManifest.uniqueKeys(),
                SchemaManifest.requiredIndexes(),
                output
        );
    }

    MigrationEngine(
            List<String> tableOrder,
            Map<String, List<List<String>>> uniqueKeys,
            Consumer<String> output
    ) {
        this(tableOrder, uniqueKeys, Map.of(), output);
    }

    MigrationEngine(
            List<String> tableOrder,
            Map<String, List<List<String>>> uniqueKeys,
            Map<String, List<List<String>>> requiredIndexes,
            Consumer<String> output
    ) {
        this.tableOrder = List.copyOf(tableOrder);
        this.uniqueKeys = Map.copyOf(uniqueKeys);
        this.requiredIndexes = Map.copyOf(requiredIndexes);
        this.inspector = new SchemaInspector();
        this.verifier = new DataVerifier(tableOrder, uniqueKeys);
        this.output = output;
    }

    Result run(Connection source, Connection target, Request request)
            throws MigrationException {
        boolean commitAttempted = false;
        boolean targetCommitted = false;
        try {
            configureSource(source);
            configureTarget(target, request.dryRun() || request.verifyOnly());
            acquireTargetLock(target, request.lockTimeoutMs());

            SchemaInspector.Schema sourceSchema = inspector.inspect(source, request.sourceSchema(), tableOrder);
            SchemaInspector.Schema targetSchema = inspector.inspect(target, request.targetSchema(), tableOrder);
            inspector.requireCompatible(
                    sourceSchema, targetSchema, tableOrder, uniqueKeys, requiredIndexes
            );
            requireNoActiveNodeLease(source, sourceSchema, "Source");
            requireNoActiveNodeLease(target, targetSchema, "Target");
            output.accept("[OK] Schema compatibility: " + tableOrder.size() + " RPG tables");

            Map<String, DataVerifier.TableSnapshot> sourceSnapshot =
                    verifier.snapshotAll(source, sourceSchema, tableOrder);
            Map<String, DataVerifier.TableSnapshot> targetBefore =
                    verifier.snapshotAll(target, targetSchema, tableOrder);
            long sourceRows = verifier.totalRows(sourceSnapshot);
            long targetRows = verifier.totalRows(targetBefore);
            boolean identical = verifier.equal(sourceSnapshot, targetBefore);
            output.accept("[OK] Source snapshot: " + sourceRows + " rows with key/full-row/amount digests");
            reportBefore(sourceSnapshot, targetBefore);

            if (request.verifyOnly()) {
                verifier.requireEqual(sourceSnapshot, targetBefore);
                reportVerified(sourceSnapshot);
                rollback(source);
                rollback(target);
                output.accept("[OK] Verify-only: source and target are identical");
                return new Result(Outcome.VERIFIED, sourceRows, tableOrder.size());
            }
            if (identical) {
                reportVerified(sourceSnapshot);
                rollback(source);
                rollback(target);
                output.accept("[OK] Target is already identical; no data was written");
                return new Result(Outcome.ALREADY_IDENTICAL, sourceRows, tableOrder.size());
            }
            if (targetRows > 0 && !request.replaceExisting()) {
                throw new MigrationException(
                        "Target contains " + targetRows
                                + " RPG rows and differs from source; use an empty target or explicit replace confirmation"
                );
            }
            if (request.dryRun()) {
                rollback(source);
                rollback(target);
                Outcome outcome = targetRows == 0 ? Outcome.DRY_RUN_EMPTY_TARGET : Outcome.DRY_RUN_REPLACE;
                output.accept(targetRows == 0
                        ? "[PLAN] Dry-run: copy into empty compatible target"
                        : "[PLAN] Dry-run: transactionally replace " + targetRows + " target rows");
                return new Result(outcome, sourceRows, tableOrder.size());
            }

            if (request.replaceExisting()) {
                deleteTargetRows(target, targetSchema);
                output.accept("[OK] Existing target RPG rows deleted inside the uncommitted migration transaction");
            }
            long copied = copyAll(source, target, sourceSchema, targetSchema, request.batchSize());
            if (copied != sourceRows) {
                throw new MigrationException("Internal copy count mismatch: expected " + sourceRows + ", copied " + copied);
            }

            Map<String, DataVerifier.TableSnapshot> targetInTransaction =
                    verifier.snapshotAll(target, targetSchema, tableOrder);
            verifier.requireEqual(sourceSnapshot, targetInTransaction);
            output.accept("[OK] Pre-commit verification passed for every table");
            commitAttempted = true;
            target.commit();
            targetCommitted = true;

            target.setReadOnly(true);
            Map<String, DataVerifier.TableSnapshot> targetAfterCommit =
                    verifier.snapshotAll(target, targetSchema, tableOrder);
            verifier.requireEqual(sourceSnapshot, targetAfterCommit);
            reportVerified(sourceSnapshot);
            rollback(target);
            rollback(source);
            output.accept("[OK] Post-commit verification passed; migration is durable");
            return new Result(Outcome.MIGRATED, sourceRows, tableOrder.size());
        } catch (SQLException exception) {
            rollback(source);
            rollback(target);
            if (commitAttempted) {
                throw new MigrationException(
                        "Commit or post-commit verification failed; commit outcome may be durable. "
                                + "Keep Paper stopped and run verify-only before any retry",
                        exception
                );
            }
            throw new MigrationException("Database migration failed; the uncommitted target transaction was rolled back", exception);
        } catch (MigrationException exception) {
            rollback(source);
            rollback(target);
            if (targetCommitted) {
                throw new MigrationException(
                        "Post-commit verification failed; target data is already committed. "
                                + "Keep Paper stopped and run verify-only before any retry: " + exception.getMessage(),
                        exception
                );
            }
            throw exception;
        }
    }

    private static void configureSource(Connection source) throws SQLException {
        source.setAutoCommit(false);
        source.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        source.setReadOnly(true);
    }

    private static void configureTarget(Connection target, boolean readOnly) throws SQLException {
        target.setAutoCommit(false);
        target.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        target.setReadOnly(readOnly);
    }

    /**
     * 取得与在线 Paper 节点完全相同的迁移 advisory lock，最多等待
     * {@code lockTimeoutMs} 毫秒。等待上限由 PostgreSQL 自身的
     * {@code lock_timeout} 执行，因此既不会在超时后继续盲目执行 DDL，
     * 也不会把 {@code SERIALIZABLE} 事务拖到无限期。
     */
    private static void acquireTargetLock(Connection target, long lockTimeoutMs)
            throws SQLException, MigrationException {
        if (!isPostgres(target)) return;
        long timeout = Math.clamp(
                lockTimeoutMs, MigrationAdvisoryLock.MIN_TIMEOUT_MS, MigrationAdvisoryLock.MAX_TIMEOUT_MS);
        try (Statement statement = target.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '" + timeout + "ms'");
        }
        // 双参数形式，与 RpgCore Database.migrationCoordinationStatements 一致。
        String sql = "SELECT pg_advisory_xact_lock(" + MigrationAdvisoryLock.NAMESPACE
                + ", " + MigrationAdvisoryLock.RESOURCE + ")";
        try (Statement statement = target.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            if (LOCK_TIMEOUT_STATE.equals(exception.getSQLState())) {
                throw new MigrationException(
                        "Timed out after " + timeout + "ms waiting for the PostgreSQL migration advisory lock (key "
                                + MigrationAdvisoryLock.composedKey()
                                + "); another Paper node or migration tool holds it. Stop every node, wait for the"
                                + " lock holder to finish, then retry.",
                        exception
                );
            }
            throw exception;
        }
    }

    private void requireNoActiveNodeLease(
            Connection connection, SchemaInspector.Schema schema, String label
    ) throws SQLException, MigrationException {
        if (!tableOrder.contains("rpg_node_leases")) return;
        SchemaInspector.Table leases = schema.tables().get("rpg_node_leases");
        String sql = "SELECT COUNT(*) FROM " + leases.qualifiedName()
                + " WHERE " + SchemaInspector.quote("lease_expires_at") + " > CURRENT_TIMESTAMP";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            long active = result.getLong(1);
            if (active > 0) {
                throw new MigrationException(
                        label + " reports " + active
                                + " active Paper node lease(s); stop every related node and wait for lease expiry"
                );
            }
        }
    }

    private void reportBefore(
            Map<String, DataVerifier.TableSnapshot> source,
            Map<String, DataVerifier.TableSnapshot> target
    ) {
        for (String table : tableOrder) {
            output.accept("[CHECK] " + table + " source_rows=" + source.get(table).rowCount()
                    + " target_rows=" + target.get(table).rowCount());
        }
    }

    private void reportVerified(Map<String, DataVerifier.TableSnapshot> snapshot) {
        for (String table : tableOrder) {
            DataVerifier.TableSnapshot value = snapshot.get(table);
            output.accept("[VERIFY] " + table + " rows=" + value.rowCount()
                    + " primary=OK full=OK critical_totals=" + value.criticalNumericSums().size());
        }
    }

    private void deleteTargetRows(Connection target, SchemaInspector.Schema schema) throws SQLException {
        List<String> reverse = new ArrayList<>(tableOrder);
        Collections.reverse(reverse);
        try (Statement statement = target.createStatement()) {
            for (String tableName : reverse) {
                statement.executeUpdate("DELETE FROM " + schema.tables().get(tableName).qualifiedName());
            }
        }
    }

    private long copyAll(
            Connection source,
            Connection target,
            SchemaInspector.Schema sourceSchema,
            SchemaInspector.Schema targetSchema,
            int batchSize
    ) throws SQLException {
        long total = 0;
        for (String tableName : tableOrder) {
            SchemaInspector.Table sourceTable = sourceSchema.tables().get(tableName);
            SchemaInspector.Table targetTable = targetSchema.tables().get(tableName);
            long copied = copyTable(source, target, sourceTable, targetTable, batchSize);
            total += copied;
            output.accept("[COPY] " + tableName + ": " + copied + " rows");
        }
        return total;
    }

    private static long copyTable(
            Connection source,
            Connection target,
            SchemaInspector.Table sourceTable,
            SchemaInspector.Table targetTable,
            int batchSize
    ) throws SQLException {
        String columns = sourceTable.columns().stream()
                .map(column -> SchemaInspector.quote(column.name()))
                .collect(Collectors.joining(", "));
        String order = sourceTable.primaryKey().stream().map(SchemaInspector::quote)
                .collect(Collectors.joining(", "));
        String placeholders = sourceTable.columns().stream().map(ignored -> "?")
                .collect(Collectors.joining(", "));
        String selectSql = "SELECT " + columns + " FROM " + sourceTable.qualifiedName() + " ORDER BY " + order;
        String insertSql = "INSERT INTO " + targetTable.qualifiedName()
                + " (" + columns + ") VALUES (" + placeholders + ")";

        long copied = 0;
        int pending = 0;
        try (Statement select = source.createStatement(); PreparedStatement insert = target.prepareStatement(insertSql)) {
            select.setFetchSize(batchSize);
            try (ResultSet rows = select.executeQuery(selectSql)) {
                while (rows.next()) {
                    for (int index = 0; index < sourceTable.columns().size(); index++) {
                        SchemaInspector.Column sourceColumn = sourceTable.columns().get(index);
                        SchemaInspector.Column targetColumn = targetTable.column(sourceColumn.name());
                        bind(insert, index + 1, rows, index + 1, sourceColumn, targetColumn);
                    }
                    insert.addBatch();
                    pending++;
                    copied++;
                    if (pending >= batchSize) {
                        requireSuccessfulBatch(insert.executeBatch(), sourceTable.name());
                        pending = 0;
                    }
                }
            }
            if (pending > 0) requireSuccessfulBatch(insert.executeBatch(), sourceTable.name());
        }
        return copied;
    }

    private static void bind(
            PreparedStatement target,
            int targetIndex,
            ResultSet source,
            int sourceIndex,
            SchemaInspector.Column sourceColumn,
            SchemaInspector.Column targetColumn
    ) throws SQLException {
        switch (sourceColumn.family()) {
            case STRING -> {
                String value = source.getString(sourceIndex);
                if (value == null) target.setNull(targetIndex, targetColumn.jdbcType());
                else target.setString(targetIndex, value);
            }
            case INT32 -> {
                int value = source.getInt(sourceIndex);
                if (source.wasNull()) target.setNull(targetIndex, targetColumn.jdbcType());
                else target.setInt(targetIndex, value);
            }
            case INT64 -> {
                long value = source.getLong(sourceIndex);
                if (source.wasNull()) target.setNull(targetIndex, targetColumn.jdbcType());
                else target.setLong(targetIndex, value);
            }
            case DECIMAL -> {
                BigDecimal value = source.getBigDecimal(sourceIndex);
                if (value == null) target.setNull(targetIndex, targetColumn.jdbcType());
                else target.setBigDecimal(targetIndex, value);
            }
            case BOOLEAN -> {
                boolean value = source.getBoolean(sourceIndex);
                if (source.wasNull()) target.setNull(targetIndex, targetColumn.jdbcType());
                else target.setBoolean(targetIndex, value);
            }
            case DATE -> {
                Date value = source.getDate(sourceIndex);
                if (value == null) target.setNull(targetIndex, targetColumn.jdbcType());
                else target.setDate(targetIndex, value);
            }
            case TIMESTAMP -> {
                Timestamp value = source.getTimestamp(sourceIndex);
                if (value == null) target.setNull(targetIndex, targetColumn.jdbcType());
                else target.setTimestamp(targetIndex, value);
            }
        }
    }

    private static void requireSuccessfulBatch(int[] counts, String table) throws SQLException {
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new SQLException("Batch copy failed for " + table);
            }
        }
    }

    private static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgresql");
    }

    private static void rollback(Connection connection) {
        try {
            if (connection != null && !connection.isClosed() && !connection.getAutoCommit()) connection.rollback();
        } catch (SQLException ignored) {
            // The original failure remains the actionable error.
        }
    }
}
