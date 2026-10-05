package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.AscensionEvidence;
import cn.originalrpg.api.model.AscensionRequirement;
import cn.originalrpg.api.model.AscensionResult;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.AscensionRules;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.service.AscensionService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * Owns the complete ascension decision. Profile, previous branch, learned
 * skills, settled dungeons, Merit debit and the audit receipt share one JDBC
 * transaction; equipment and journey evidence comes from trusted main-thread
 * services and is recorded with the receipt.
 */
public final class JdbcAscensionService implements AscensionService {
    private final DataSource dataSource;
    private final Executor executor;
    private final JdbcProfileService profiles;
    private final IntSupplier maximumOpenAscension;

    public JdbcAscensionService(
            DataSource dataSource,
            Executor executor,
            JdbcProfileService profiles,
            IntSupplier maximumOpenAscension
    ) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.maximumOpenAscension = java.util.Objects.requireNonNull(
                maximumOpenAscension, "maximumOpenAscension");
    }

    @Override
    public CompletionStage<AscensionResult> eligibility(UUID playerId, AscensionEvidence evidence) {
        if (playerId == null || evidence == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid ascension eligibility request"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> evaluate(
                connection, playerId, evidence, false)));
    }

    @Override
    public CompletionStage<AscensionResult> ascend(UUID playerId, AscensionEvidence evidence) {
        if (playerId == null || evidence == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid ascension request"));
        }
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            AscensionResult result = inTransaction(
                    connection -> evaluate(connection, playerId, evidence, true), version);
            return new Committed<>(result, version[0]);
        }).thenApply(committed -> {
            AscensionResult result = committed.value();
            if (result.status() == AscensionResult.Status.SUCCESS) {
                profiles.cacheCommitted(result.profile(), committed.version());
            }
            return result;
        });
    }

    private AscensionResult evaluate(
            Connection connection, UUID playerId, AscensionEvidence evidence, boolean settle
    ) throws SQLException {
        PlayerProfile current = profiles.loadForUpdate(connection, playerId);
        int target = current.ascension() + 1;
        AscensionRequirement requirement = AscensionRules.requirement(evidence.targetAscension()).orElse(null);
        if (target > ProgressionRules.MAX_ASCENSION) {
            return result(AscensionResult.Status.MAXIMUM_REACHED, current, null, evidence,
                    0, 0, 0, 0, false);
        }
        if (requirement == null) {
            throw new IllegalArgumentException("Unsupported ascension target");
        }
        Counts counts = counts(connection, playerId, target, settle);
        if (evidence.targetAscension() != target) {
            return result(AscensionResult.Status.STALE_REQUEST, current, requirement, evidence, counts);
        }
        if (current.archetype() == Archetype.UNCHOSEN) {
            return result(AscensionResult.Status.INVALID, current, requirement, evidence, counts);
        }
        if (target > Math.clamp(maximumOpenAscension.getAsInt(), 0, ProgressionRules.MAX_ASCENSION)) {
            return result(AscensionResult.Status.CHAPTER_LOCKED, current, requirement, evidence, counts);
        }
        if (current.level() < ProgressionRules.levelCap(current.ascension())) {
            return result(AscensionResult.Status.LEVEL_REQUIRED, current, requirement, evidence, counts);
        }
        if (requirement.journeyRequired() && !evidence.journeyComplete()) {
            return result(AscensionResult.Status.JOURNEY_REQUIRED, current, requirement, evidence, counts);
        }
        if (requirement.previousBranchRequired() && !counts.previousBranchSelected()) {
            return result(AscensionResult.Status.PREVIOUS_BRANCH_REQUIRED, current, requirement, evidence, counts);
        }
        if (counts.learnedSkills() < requirement.learnedSkills()) {
            return result(AscensionResult.Status.SKILLS_REQUIRED, current, requirement, evidence, counts);
        }
        if (counts.dungeonClears() < requirement.dungeonClears()
                || counts.distinctDungeons() < requirement.distinctDungeons()) {
            return result(AscensionResult.Status.DUNGEONS_REQUIRED, current, requirement, evidence, counts);
        }
        if (evidence.equipmentIds().isEmpty() || evidence.equipmentScore() < requirement.equipmentScore()) {
            return result(AscensionResult.Status.EQUIPMENT_REQUIRED, current, requirement, evidence, counts);
        }
        if (counts.merit() < requirement.meritCost()) {
            return result(AscensionResult.Status.MERIT_REQUIRED, current, requirement, evidence, counts);
        }
        if (!settle) return result(AscensionResult.Status.READY, current, requirement, evidence, counts);

        PlayerProfile updated = new PlayerProfile(
                current.playerId(), current.archetype(), target, 1, 0,
                current.energyCap(), current.energyCap(), current.unspentAttributePoints(),
                current.skillPoints(), current.attributes());
        writeReceipt(connection, playerId, current, requirement, evidence, counts);
        if (requirement.meritCost() > 0) debitMerit(connection, playerId, requirement);
        profiles.updateInTransaction(connection, updated);
        return result(AscensionResult.Status.SUCCESS, updated, requirement, evidence,
                counts.learnedSkills(), counts.dungeonClears(), counts.distinctDungeons(),
                counts.merit() - requirement.meritCost(),
                counts.previousBranchSelected());
    }

    private Counts counts(Connection connection, UUID playerId, int target, boolean lockMerit) throws SQLException {
        int learnedSkills;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM rpg_skills WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                learnedSkills = result.getInt(1);
            }
        }
        Set<String> qualifying = AscensionRules.qualifyingDungeons(target);
        long dungeonClears = 0;
        int distinctDungeons = 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(qualifying.size(), "?"));
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT dungeon_id, COUNT(*) AS clears
                FROM rpg_dungeon_reward_receipts
                WHERE player_uuid = ? AND dungeon_id IN (%s)
                GROUP BY dungeon_id
                """.formatted(placeholders))) {
            int parameter = 1;
            query.setString(parameter++, playerId.toString());
            for (String dungeonId : qualifying.stream().sorted().toList()) {
                query.setString(parameter++, dungeonId);
            }
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    dungeonClears = Math.addExact(dungeonClears, result.getLong("clears"));
                    distinctDungeons++;
                }
            }
        }
        boolean branch = target <= 1;
        if (target > 1) {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT 1 FROM rpg_class_branches WHERE player_uuid = ? AND stage = ?")) {
                query.setString(1, playerId.toString());
                query.setInt(2, target - 1);
                try (ResultSet result = query.executeQuery()) {
                    branch = result.next();
                }
            }
        }
        long merit = merit(connection, playerId, lockMerit);
        return new Counts(learnedSkills, dungeonClears, distinctDungeons, merit, branch);
    }

    private long merit(Connection connection, UUID playerId, boolean lock) throws SQLException {
        if (lock) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES (?, 0) ON CONFLICT DO NOTHING")) {
                insert.setString(1, playerId.toString());
                insert.executeUpdate();
            }
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Math.max(0, result.getLong(1)) : 0;
            }
        }
    }

    private void writeReceipt(
            Connection connection, UUID playerId, PlayerProfile current, AscensionRequirement requirement,
            AscensionEvidence evidence, Counts counts
    ) throws SQLException {
        String equipmentIds = evidence.equipmentIds().stream().map(UUID::toString).sorted()
                .collect(Collectors.joining(","));
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ascension_receipts (
                    player_uuid, target_ascension, archetype, previous_branch_selected,
                    journey_complete, equipment_score, equipment_ids, learned_skills,
                    dungeon_clears, merit_spent
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, playerId.toString());
            insert.setInt(2, requirement.targetAscension());
            insert.setString(3, current.archetype().name());
            insert.setBoolean(4, counts.previousBranchSelected());
            insert.setBoolean(5, evidence.journeyComplete());
            insert.setInt(6, evidence.equipmentScore());
            insert.setString(7, equipmentIds);
            insert.setInt(8, counts.learnedSkills());
            insert.setLong(9, counts.dungeonClears());
            insert.setLong(10, requirement.meritCost());
            insert.executeUpdate();
        }
    }

    private void debitMerit(
            Connection connection, UUID playerId, AscensionRequirement requirement
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_progression_wallets SET merit = merit - ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND merit >= ?
                """)) {
            update.setLong(1, requirement.meritCost());
            update.setString(2, playerId.toString());
            update.setLong(3, requirement.meritCost());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Ascension Merit changed during settlement");
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_merit_ledger (id, idempotency_key, player_uuid, amount, direction, reason)
                VALUES (?, ?, ?, ?, 'DEBIT', ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, "ascension:" + playerId + ":" + requirement.targetAscension());
            insert.setString(3, playerId.toString());
            insert.setLong(4, requirement.meritCost());
            insert.setString(5, "class-ascension-" + requirement.targetAscension());
            insert.executeUpdate();
        }
    }

    private static AscensionResult result(
            AscensionResult.Status status, PlayerProfile profile, AscensionRequirement requirement,
            AscensionEvidence evidence, Counts counts
    ) {
        return result(status, profile, requirement, evidence, counts.learnedSkills(), counts.dungeonClears(),
                counts.distinctDungeons(), counts.merit(), counts.previousBranchSelected());
    }

    private static AscensionResult result(
            AscensionResult.Status status, PlayerProfile profile, AscensionRequirement requirement,
            AscensionEvidence evidence, int learnedSkills, long dungeonClears, int distinctDungeons,
            long merit, boolean branch
    ) {
        return new AscensionResult(status, profile, requirement, evidence,
                learnedSkills, dungeonClears, distinctDungeons, merit, branch);
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        return inTransaction(operation, new long[1]);
    }

    /**
     * @param versionStamp 出参：写入本次事务**提交成功后**取得的提交版本号
     *                     （{@link JdbcProfileService#nextCommitVersion()}）。调用方在
     *                     {@code thenApply} 里用它发布读缓存，使发布判定基于提交顺序。
     */
    private <T> T inTransaction(SqlOperation<T> operation, long[] versionStamp) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                versionStamp[0] = profiles.nextCommitVersion();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Ascension transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open ascension transaction", exception);
        }
    }

    private record Counts(
            int learnedSkills, long dungeonClears, int distinctDungeons,
            long merit, boolean previousBranchSelected
    ) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
