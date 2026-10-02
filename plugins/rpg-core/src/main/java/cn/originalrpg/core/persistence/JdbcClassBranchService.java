package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.ClassBranchResult;
import cn.originalrpg.api.service.ClassBranchService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcClassBranchService implements ClassBranchService {
    private static final Pattern BRANCH_ID = Pattern.compile("[a-z0-9._-]{2,64}");

    private final DataSource dataSource;
    private final Executor executor;

    public JdbcClassBranchService(DataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletionStage<Map<Integer, String>> branches(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Map.of());
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            Map<Integer, String> branches = new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT stage, branch_id FROM rpg_class_branches WHERE player_uuid = ? ORDER BY stage
                    """)) {
                query.setString(1, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) branches.put(result.getInt("stage"), result.getString("branch_id"));
                }
            }
            return Map.copyOf(branches);
        }));
    }

    @Override
    public CompletionStage<ClassBranchResult> choose(
            UUID playerId, Archetype requiredArchetype, int stage, String branchId,
            Set<String> allowedPreviousBranches
    ) {
        if (playerId == null || requiredArchetype == null || requiredArchetype == Archetype.UNCHOSEN
                || stage < 1 || stage > 3 || branchId == null || !BRANCH_ID.matcher(branchId).matches()
                || allowedPreviousBranches == null || allowedPreviousBranches.stream()
                .anyMatch(value -> value == null || !BRANCH_ID.matcher(value).matches())) {
            return CompletableFuture.completedFuture(result(ClassBranchResult.Status.INVALID, Math.clamp(stage, 1, 3), null));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            ProfileGate gate = lockProfile(connection, playerId);
            if (gate == null || gate.archetype() != requiredArchetype) {
                return result(ClassBranchResult.Status.CLASS_MISMATCH, stage, null);
            }
            if (gate.ascension() < stage) {
                return result(ClassBranchResult.Status.STAGE_LOCKED, stage, null);
            }
            if (stage > 1 && !allowedPreviousBranches.isEmpty()) {
                String previous = selected(connection, playerId, stage - 1);
                if (!allowedPreviousBranches.contains(previous)) {
                    return result(ClassBranchResult.Status.PREREQUISITE_MISMATCH, stage, previous);
                }
            }
            String existing = selected(connection, playerId, stage);
            if (existing != null) {
                return result(ClassBranchResult.Status.ALREADY_CHOSEN, stage, existing);
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_class_branches (player_uuid, stage, branch_id) VALUES (?, ?, ?)
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setInt(2, stage);
                insert.setString(3, branchId);
                insert.executeUpdate();
            }
            return result(ClassBranchResult.Status.SUCCESS, stage, branchId);
        }));
    }

    private ProfileGate lockProfile(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT archetype, ascension FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new ProfileGate(
                        Archetype.valueOf(result.getString("archetype")), result.getInt("ascension")) : null;
            }
        }
    }

    private String selected(Connection connection, UUID playerId, int stage) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT branch_id FROM rpg_class_branches WHERE player_uuid = ? AND stage = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setInt(2, stage);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static ClassBranchResult result(ClassBranchResult.Status status, int stage, String branchId) {
        return new ClassBranchResult(status, stage, Optional.ofNullable(branchId));
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Class-branch transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open class-branch transaction", exception);
        }
    }

    private record ProfileGate(Archetype archetype, int ascension) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
