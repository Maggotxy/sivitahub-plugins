package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SkillLearnResult;
import cn.originalrpg.api.model.SkillMutationRequest;
import cn.originalrpg.api.model.SkillMutationResult;
import cn.originalrpg.api.model.SkillProgress;
import cn.originalrpg.api.model.SkillUpgradeResult;
import cn.originalrpg.api.model.SkillVariantCatalog;
import cn.originalrpg.api.service.SkillService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcSkillService implements SkillService {
    private static final Pattern SKILL_ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final Pattern VARIANT = Pattern.compile("[A-Z0-9._-]{2,64}");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{3,96}");
    private static final BigDecimal MAX_GOLD = new BigDecimal("1000000000000.00");
    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;
    private final Map<UUID, Map<String, SkillProgress>> cache = new ConcurrentHashMap<>();

    public JdbcSkillService(DataSource dataSource, Executor executor) {
        this(dataSource, executor, BigDecimal.ZERO);
    }

    public JdbcSkillService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
    }

    @Override
    public CompletionStage<Map<String, SkillProgress>> skills(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Map.of());
        Map<String, SkillProgress> cached = cache.get(playerId);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return async(connection -> {
            Map<String, SkillProgress> result = new HashMap<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT skill_id, level, point_cost, variant FROM rpg_skills WHERE player_uuid = ?")) {
                query.setString(1, playerId.toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        SkillProgress progress = map(rows);
                        result.put(progress.skillId(), progress);
                    }
                }
            }
            return Map.copyOf(result);
        }).thenApply(loaded -> cache.merge(playerId, loaded, (current, ignored) -> current));
    }

    @Override
    public CompletionStage<Optional<SkillProgress>> skill(UUID playerId, String skillId) {
        if (!valid(playerId, skillId)) return CompletableFuture.completedFuture(Optional.empty());
        Map<String, SkillProgress> cached = cache.get(playerId);
        if (cached != null) return CompletableFuture.completedFuture(Optional.ofNullable(cached.get(skillId)));
        return skills(playerId).thenApply(loaded -> Optional.ofNullable(loaded.get(skillId)));
    }

    @Override
    public Optional<Map<String, SkillProgress>> cachedSkills(UUID playerId) {
        return Optional.ofNullable(playerId == null ? null : cache.get(playerId));
    }

    @Override
    public CompletionStage<SkillLearnResult> learn(UUID playerId, String skillId, int pointCost) {
        if (!valid(playerId, skillId) || pointCost < 0 || pointCost > 1000) {
            return CompletableFuture.completedFuture(
                    new SkillLearnResult(SkillLearnResult.Status.INVALID, null, 0));
        }
        return async(connection -> {
            int availablePoints = lockProfile(connection, playerId);
            SkillProgress existing = select(connection, playerId, skillId);
            long spent = spentPoints(connection, playerId);
            if (existing != null) {
                return new SkillLearnResult(SkillLearnResult.Status.ALREADY_LEARNED, existing, reported(spent));
            }
            if (spent + pointCost > availablePoints) {
                return new SkillLearnResult(SkillLearnResult.Status.INSUFFICIENT_POINTS, null, reported(spent));
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost, variant)
                    VALUES (?, ?, 1, ?, NULL)
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setString(2, skillId);
                insert.setInt(3, pointCost);
                insert.executeUpdate();
            }
            SkillProgress learned = select(connection, playerId, skillId);
            return new SkillLearnResult(SkillLearnResult.Status.SUCCESS, learned, reported(spent + pointCost));
        }).thenApply(result -> {
            if (result.progress() != null) {
                // Never manufacture a partial "complete skill set" when learn()
                // was called before skills(). Update only an already complete snapshot.
                cache.computeIfPresent(playerId, (ignored, existing) -> {
                    Map<String, SkillProgress> updated = new HashMap<>(existing);
                    updated.put(result.progress().skillId(), result.progress());
                    return Map.copyOf(updated);
                });
            }
            return result;
        });
    }

    @Override
    public CompletionStage<SkillUpgradeResult> upgrade(
            UUID playerId, String skillId, int expectedLevel, int maximumLevel, int pointCost
    ) {
        if (!valid(playerId, skillId) || expectedLevel < 1 || expectedLevel > 100
                || maximumLevel < 2 || maximumLevel > 100 || pointCost < 1 || pointCost > 1000) {
            return CompletableFuture.completedFuture(
                    new SkillUpgradeResult(SkillUpgradeResult.Status.INVALID, null, 0));
        }
        return async(connection -> {
            int availablePoints = lockProfile(connection, playerId);
            SkillProgress existing = select(connection, playerId, skillId, true);
            long spent = spentPoints(connection, playerId);
            if (existing == null) {
                return new SkillUpgradeResult(SkillUpgradeResult.Status.NOT_LEARNED, null, reported(spent));
            }
            if (existing.level() != expectedLevel) {
                return new SkillUpgradeResult(SkillUpgradeResult.Status.STALE_LEVEL, existing, reported(spent));
            }
            if (existing.level() >= maximumLevel) {
                return new SkillUpgradeResult(SkillUpgradeResult.Status.MAX_LEVEL, existing, reported(spent));
            }
            if (spent + (long) pointCost > availablePoints) {
                return new SkillUpgradeResult(
                        SkillUpgradeResult.Status.INSUFFICIENT_POINTS, existing, reported(spent));
            }
            int upgradedPointCost = Math.addExact(existing.pointCost(), pointCost);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_skills SET level = ?, point_cost = ?
                    WHERE player_uuid = ? AND skill_id = ? AND level = ?
                    """)) {
                update.setInt(1, existing.level() + 1);
                update.setInt(2, upgradedPointCost);
                update.setString(3, playerId.toString());
                update.setString(4, skillId);
                update.setInt(5, expectedLevel);
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("Locked skill level changed during upgrade");
                }
            }
            SkillProgress upgraded = select(connection, playerId, skillId, false);
            return new SkillUpgradeResult(
                    SkillUpgradeResult.Status.SUCCESS, upgraded, reported(spent + pointCost));
        }).thenApply(result -> {
            if (result.progress() != null) {
                cache.computeIfPresent(playerId, (ignored, existing) -> {
                    Map<String, SkillProgress> updated = new HashMap<>(existing);
                    SkillProgress persisted = result.progress();
                    SkillProgress cached = existing.get(persisted.skillId());
                    // Upgrade changes only level and cumulative point cost. A
                    // concurrent variant mutation owns the variant field, so
                    // preserve the latest cache value regardless of callback order.
                    updated.put(persisted.skillId(), new SkillProgress(
                            persisted.skillId(), persisted.level(), persisted.pointCost(),
                            cached == null ? persisted.variant() : cached.variant()));
                    return Map.copyOf(updated);
                });
            }
            return result;
        });
    }

    @Override
    public CompletionStage<Boolean> setVariant(UUID playerId, String skillId, String variant) {
        if (!valid(playerId, skillId) || variant == null || !VARIANT.matcher(variant).matches()
                || !SkillVariantCatalog.supports(skillId, variant)) {
            return CompletableFuture.completedFuture(false);
        }
        return async(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_skills SET variant = ? WHERE player_uuid = ? AND skill_id = ?
                    """)) {
                update.setString(1, variant);
                update.setString(2, playerId.toString());
                update.setString(3, skillId);
                return update.executeUpdate() == 1;
            }
        }).thenApply(updated -> {
            if (updated) {
                cache.computeIfPresent(playerId, (ignored, existing) -> {
                    SkillProgress current = existing.get(skillId);
                    if (current == null) return existing;
                    Map<String, SkillProgress> replacement = new HashMap<>(existing);
                    replacement.put(skillId, new SkillProgress(
                            current.skillId(), current.level(), current.pointCost(), Optional.of(variant)));
                    return Map.copyOf(replacement);
                });
            }
            return updated;
        });
    }

    @Override
    public CompletionStage<Boolean> clearVariant(UUID playerId, String skillId) {
        if (!valid(playerId, skillId) || SkillVariantCatalog.variants(skillId).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return async(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_skills SET variant = NULL WHERE player_uuid = ? AND skill_id = ?
                    """)) {
                update.setString(1, playerId.toString());
                update.setString(2, skillId);
                return update.executeUpdate() == 1;
            }
        }).thenApply(updated -> {
            if (updated) {
                cache.computeIfPresent(playerId, (ignored, existing) -> {
                    SkillProgress current = existing.get(skillId);
                    if (current == null) return existing;
                    Map<String, SkillProgress> replacement = new HashMap<>(existing);
                    replacement.put(skillId, new SkillProgress(
                            current.skillId(), current.level(), current.pointCost(), Optional.empty()));
                    return Map.copyOf(replacement);
                });
            }
            return updated;
        });
    }

    @Override
    public CompletionStage<SkillMutationResult> settleMutation(SkillMutationRequest request) {
        if (!validMutation(request)) {
            return CompletableFuture.completedFuture(emptyMutationResult(SkillMutationResult.Status.INVALID));
        }
        BigDecimal cost = money(request.goldCost());
        return async(connection -> {
            loadOrCreateAccount(connection, request.playerId());
            BigDecimal balance = lockBalance(connection, request.playerId());
            MutationReceipt replay = mutationReceipt(connection, request.idempotencyKey());
            if (replay != null) return replayResult(request, replay, balance);

            SkillProgress current = select(connection, request.playerId(), request.skillId(), true);
            if (current == null) {
                return mutationResult(SkillMutationResult.Status.NOT_LEARNED,
                        SkillMutationResult.Outcome.NONE, BigDecimal.ZERO, balance, Optional.empty());
            }
            if (!current.variant().equals(request.expectedVariant())) {
                return mutationResult(SkillMutationResult.Status.STALE_VARIANT,
                        SkillMutationResult.Outcome.NONE, BigDecimal.ZERO, balance, current.variant());
            }
            if (balance.compareTo(cost) < 0) {
                return mutationResult(SkillMutationResult.Status.INSUFFICIENT_FUNDS,
                        SkillMutationResult.Outcome.NONE, BigDecimal.ZERO, balance, current.variant());
            }

            SkillMutationResult.Outcome outcome = mutationOutcome(request);
            Optional<String> resultingVariant = resultingVariant(request);
            MutationReceipt receipt = new MutationReceipt(
                    request.playerId(), request.skillId(), request.operation(), request.expectedVariant(),
                    request.candidateVariant(), cost, outcome, resultingVariant);
            if (!insertMutationReceipt(connection, request.idempotencyKey(), receipt)) {
                MutationReceipt concurrent = mutationReceipt(connection, request.idempotencyKey());
                return concurrent == null
                        ? emptyMutationResult(SkillMutationResult.Status.INVALID)
                        : replayResult(request, concurrent, balance);
            }
            if (!insertMutationDebit(connection, request, cost)) {
                throw new IllegalStateException("Mutation debit key already belongs to another ledger entry");
            }
            if (outcome == SkillMutationResult.Outcome.MUTATED
                    || outcome == SkillMutationResult.Outcome.RESTORED) {
                updateVariant(connection, request.playerId(), request.skillId(), resultingVariant);
            }
            BigDecimal updatedBalance = money(balance.subtract(cost));
            updateBalance(connection, request.playerId(), updatedBalance);
            return mutationResult(SkillMutationResult.Status.SETTLED,
                    outcome, cost, updatedBalance, resultingVariant);
        }).thenApply(result -> {
            if (result.status() == SkillMutationResult.Status.SETTLED
                    && (result.outcome() == SkillMutationResult.Outcome.MUTATED
                    || result.outcome() == SkillMutationResult.Outcome.RESTORED)) {
                cache.computeIfPresent(request.playerId(), (ignored, existing) -> {
                    SkillProgress current = existing.get(request.skillId());
                    if (current == null) return existing;
                    Map<String, SkillProgress> replacement = new HashMap<>(existing);
                    replacement.put(request.skillId(), new SkillProgress(
                            current.skillId(), current.level(), current.pointCost(), result.resultingVariant()));
                    return Map.copyOf(replacement);
                });
            }
            return result;
        });
    }

    @Override
    public void evict(UUID playerId) {
        if (playerId != null) cache.remove(playerId);
    }

    private int lockProfile(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT skill_points FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Player profile must exist before learning skills");
                return result.getInt("skill_points");
            }
        }
    }

    private long spentPoints(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COALESCE(SUM(point_cost), 0) FROM rpg_skills WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static int reported(long spent) {
        return (int) Math.clamp(spent, 0L, Integer.MAX_VALUE);
    }

    private SkillProgress select(Connection connection, UUID playerId, String skillId) throws SQLException {
        return select(connection, playerId, skillId, false);
    }

    private SkillProgress select(
            Connection connection, UUID playerId, String skillId, boolean lock
    ) throws SQLException {
        String sql = "SELECT skill_id, level, point_cost, variant FROM rpg_skills "
                + "WHERE player_uuid = ? AND skill_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            query.setString(2, skillId);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private static SkillProgress map(ResultSet result) throws SQLException {
        String variant = result.getString("variant");
        return new SkillProgress(
                result.getString("skill_id"),
                result.getInt("level"),
                result.getInt("point_cost"),
                Optional.ofNullable(variant)
        );
    }

    private static boolean valid(UUID playerId, String skillId) {
        return playerId != null && skillId != null && SKILL_ID.matcher(skillId).matches();
    }

    private static boolean validMutation(SkillMutationRequest request) {
        if (request == null || !valid(request.playerId(), request.skillId())
                || !SkillVariantCatalog.implemented().containsKey(request.skillId())
                || request.goldCost().signum() <= 0 || money(request.goldCost()).signum() <= 0
                || money(request.goldCost()).compareTo(MAX_GOLD) > 0
                || !IDEMPOTENCY_KEY.matcher(request.idempotencyKey()).matches()) {
            return false;
        }
        return switch (request.operation()) {
            case MUTATE -> request.expectedVariant().isEmpty()
                    && request.candidateVariant().map(candidate ->
                    VARIANT.matcher(candidate).matches()
                            && SkillVariantCatalog.supports(request.skillId(), candidate)).orElse(true);
            case RESTORE -> request.candidateVariant().isEmpty()
                    && request.expectedVariant().map(expected ->
                    VARIANT.matcher(expected).matches()
                            && SkillVariantCatalog.supports(request.skillId(), expected)).orElse(false);
        };
    }

    private static SkillMutationResult.Outcome mutationOutcome(SkillMutationRequest request) {
        return switch (request.operation()) {
            case MUTATE -> request.candidateVariant().isPresent()
                    ? SkillMutationResult.Outcome.MUTATED : SkillMutationResult.Outcome.FAILED_ROLL;
            case RESTORE -> SkillMutationResult.Outcome.RESTORED;
        };
    }

    private static Optional<String> resultingVariant(SkillMutationRequest request) {
        return switch (request.operation()) {
            case MUTATE -> request.candidateVariant();
            case RESTORE -> Optional.empty();
        };
    }

    private SkillMutationResult replayResult(
            SkillMutationRequest request, MutationReceipt receipt, BigDecimal balance
    ) {
        if (!receipt.matches(request)) {
            return mutationResult(SkillMutationResult.Status.INVALID,
                    SkillMutationResult.Outcome.NONE, BigDecimal.ZERO, balance, Optional.empty());
        }
        return mutationResult(SkillMutationResult.Status.REPLAYED,
                receipt.outcome(), receipt.goldCost(), balance, receipt.resultingVariant());
    }

    private MutationReceipt mutationReceipt(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, skill_id, operation, expected_variant, candidate_variant,
                       gold_cost, outcome, resulting_variant
                FROM rpg_skill_mutation_receipts WHERE idempotency_key = ?
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new MutationReceipt(
                        UUID.fromString(result.getString("player_uuid")),
                        result.getString("skill_id"),
                        SkillMutationRequest.Operation.valueOf(result.getString("operation")),
                        Optional.ofNullable(result.getString("expected_variant")),
                        Optional.ofNullable(result.getString("candidate_variant")),
                        money(result.getBigDecimal("gold_cost")),
                        SkillMutationResult.Outcome.valueOf(result.getString("outcome")),
                        Optional.ofNullable(result.getString("resulting_variant")));
            }
        }
    }

    private boolean insertMutationReceipt(
            Connection connection, String key, MutationReceipt receipt
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_skill_mutation_receipts
                    (idempotency_key, player_uuid, skill_id, operation, expected_variant,
                     candidate_variant, gold_cost, outcome, resulting_variant)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, key);
            insert.setString(2, receipt.playerId().toString());
            insert.setString(3, receipt.skillId());
            insert.setString(4, receipt.operation().name());
            insert.setString(5, receipt.expectedVariant().orElse(null));
            insert.setString(6, receipt.candidateVariant().orElse(null));
            insert.setBigDecimal(7, receipt.goldCost());
            insert.setString(8, receipt.outcome().name());
            insert.setString(9, receipt.resultingVariant().orElse(null));
            return insert.executeUpdate() == 1;
        }
    }

    private boolean insertMutationDebit(
            Connection connection, SkillMutationRequest request, BigDecimal cost
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger
                    (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, NULL, ?, 0, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, request.idempotencyKey());
            insert.setString(3, request.playerId().toString());
            insert.setBigDecimal(4, cost);
            insert.setString(5, request.operation() == SkillMutationRequest.Operation.MUTATE
                    ? "skill-mutation:" + request.skillId()
                    : "skill-mutation-restore:" + request.skillId());
            return insert.executeUpdate() == 1;
        }
    }

    private void updateVariant(
            Connection connection, UUID playerId, String skillId, Optional<String> variant
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_skills SET variant = ? WHERE player_uuid = ? AND skill_id = ?")) {
            update.setString(1, variant.orElse(null));
            update.setString(2, playerId.toString());
            update.setString(3, skillId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Locked skill disappeared during mutation settlement");
            }
        }
    }

    private void loadOrCreateAccount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
    }

    private BigDecimal lockBalance(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Mutation account could not be created");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Locked mutation account disappeared during settlement");
            }
        }
    }

    private static SkillMutationResult emptyMutationResult(SkillMutationResult.Status status) {
        return mutationResult(status, SkillMutationResult.Outcome.NONE,
                BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty());
    }

    private static SkillMutationResult mutationResult(
            SkillMutationResult.Status status,
            SkillMutationResult.Outcome outcome,
            BigDecimal charged,
            BigDecimal balance,
            Optional<String> resultingVariant
    ) {
        return new SkillMutationResult(status, outcome, money(charged), money(balance), resultingVariant);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private <T> CompletionStage<T> async(SqlOperation<T> operation) {
        return AsyncJdbc.supply(executor, () -> inTransaction(operation));
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
                throw new IllegalStateException("Skill transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open skill transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    private record MutationReceipt(
            UUID playerId,
            String skillId,
            SkillMutationRequest.Operation operation,
            Optional<String> expectedVariant,
            Optional<String> candidateVariant,
            BigDecimal goldCost,
            SkillMutationResult.Outcome outcome,
            Optional<String> resultingVariant
    ) {
        private boolean matches(SkillMutationRequest request) {
            return playerId.equals(request.playerId()) && skillId.equals(request.skillId())
                    && operation == request.operation()
                    && expectedVariant.equals(request.expectedVariant())
                    && candidateVariant.equals(request.candidateVariant())
                    && goldCost.equals(money(request.goldCost()));
        }
    }
}
