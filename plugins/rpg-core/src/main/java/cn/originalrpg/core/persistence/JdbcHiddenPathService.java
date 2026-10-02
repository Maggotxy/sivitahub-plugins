package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.HiddenPath;
import cn.originalrpg.api.model.HiddenPathActivationResult;
import cn.originalrpg.api.model.HiddenPathEligibility;
import cn.originalrpg.api.model.HiddenPathProfile;
import cn.originalrpg.api.rules.HiddenPathRules;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.service.HiddenPathService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A capacity row is locked before counting claims. This avoids the usual
 * "count then insert" race where two concurrent hidden-boss winners can both
 * take the final seat. Player profiles are locked too, so activation sees one
 * coherent class/attribute/skill/dungeon snapshot.
 */
public final class JdbcHiddenPathService implements HiddenPathService {
    private final DataSource dataSource;
    private final java.util.concurrent.Executor executor;

    public JdbcHiddenPathService(DataSource dataSource, java.util.concurrent.Executor executor) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<Optional<HiddenPathProfile>> path(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(connection -> Optional.ofNullable(findPath(connection, playerId, false)));
    }

    @Override
    public CompletionStage<HiddenPathEligibility> eligibility(UUID playerId, HiddenPath path) {
        if (playerId == null || path == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("player and path are required"));
        }
        return async(connection -> eligibility(connection, playerId, path, false));
    }

    @Override
    public CompletionStage<HiddenPathActivationResult> activate(UUID playerId, HiddenPath path) {
        if (playerId == null || path == null) {
            return CompletableFuture.completedFuture(new HiddenPathActivationResult(
                    HiddenPathActivationResult.Status.INVALID, Optional.empty(), Optional.empty()));
        }
        return async(connection -> {
            ensureCapacityRow(connection, path);
            int capacity = lockCapacity(connection, path);
            HiddenPathProfile existing = findPath(connection, playerId, true);
            if (existing != null) {
                return new HiddenPathActivationResult(HiddenPathActivationResult.Status.ALREADY_ACTIVE,
                        Optional.of(existing), Optional.empty());
            }
            HiddenPathEligibility eligibility = eligibility(connection, playerId, path, true, capacity);
            if (!eligibility.eligible()) {
                HiddenPathActivationResult.Status status = eligibility.missingRequirements().contains("capacity")
                        ? HiddenPathActivationResult.Status.CAPACITY_FULL
                        : HiddenPathActivationResult.Status.REQUIREMENTS_NOT_MET;
                return new HiddenPathActivationResult(status, Optional.empty(), Optional.of(eligibility));
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_hidden_paths (player_uuid, path, activated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    """)) {
                insert.setString(1, playerId.toString());
                insert.setString(2, path.name());
                if (insert.executeUpdate() != 1) throw new IllegalStateException("Hidden path claim was not recorded");
            }
            try (PreparedStatement audit = connection.prepareStatement("""
                    INSERT INTO rpg_hidden_path_audit (id, player_uuid, path, action)
                    VALUES (?, ?, ?, 'ACTIVATE')
                    """)) {
                audit.setString(1, UUID.randomUUID().toString());
                audit.setString(2, playerId.toString());
                audit.setString(3, path.name());
                audit.executeUpdate();
            }
            HiddenPathProfile activated = findPath(connection, playerId, true);
            return new HiddenPathActivationResult(HiddenPathActivationResult.Status.ACTIVATED,
                    Optional.ofNullable(activated), Optional.empty());
        });
    }

    private HiddenPathEligibility eligibility(
            Connection connection, UUID playerId, HiddenPath path, boolean lock
    ) throws SQLException {
        return eligibility(connection, playerId, path, lock, capacity(connection, path, false));
    }

    private HiddenPathEligibility eligibility(
            Connection connection, UUID playerId, HiddenPath path, boolean lock, int capacity
    ) throws SQLException {
        List<String> missing = new ArrayList<>();
        ProfileGate profile = profile(connection, playerId, lock);
        if (profile == null) {
            missing.add("profile");
        } else {
            if (profile.archetype() != HiddenPathRules.archetype(path)) missing.add("archetype");
            int routeAscension = HiddenPathRules.minimumAscension(path);
            if (profile.ascension() < routeAscension) missing.add("ascension");
            // Level is a LOCAL level since Phase 1. The route gate is authored
            // as "this ascension, local level X", so it can only be evaluated on
            // the ladder of the ascension which locks it. A stage-3 player at
            // local 10 already sits above a stage-3 local-20 gate, while a
            // stage-2 player at local 30 does not.
            if (profile.ascension() == routeAscension) {
                if (profile.level() < Math.min(HiddenPathRules.minimumLevel(path), ProgressionRules.LOCAL_LEVEL_CAP)) {
                    missing.add("level");
                }
            } else if (ProgressionRules.effectiveLevel(profile.ascension(), profile.level())
                    < ProgressionRules.effectiveLevel(routeAscension, HiddenPathRules.minimumLevel(path))) {
                // Any other stage is only comparable through effective levels.
                missing.add("level");
            }
            for (Map.Entry<String, Integer> requirement : attributes(path).entrySet()) {
                if (profile.attribute(requirement.getKey()) < requirement.getValue()) {
                    missing.add("attribute." + requirement.getKey().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        if (!learned(connection, playerId, HiddenPathRules.requiredSkill(path), lock)) missing.add("skill");
        if (!completedHiddenDungeon(connection, playerId, HiddenPathRules.requiredDungeon(path))) missing.add("hidden_dungeon");
        if (!redeemedSkillBook(connection, playerId, HiddenPathRules.requiredSkillBook(path))) {
            missing.add("skill_book");
        }
        int holders = holders(connection, path);
        if (holders >= capacity) missing.add("capacity");
        return new HiddenPathEligibility(path, missing.isEmpty(), Math.min(holders, capacity), capacity, missing);
    }

    private static Map<String, Integer> attributes(HiddenPath path) {
        return switch (path) {
            case BLOODPACT -> Map.of("agility", 32, "stamina", 18);
            case TALISMAN -> Map.of("intelligence", 18, "strength", 13, "agility", 9, "stamina", 6);
        };
    }

    private ProfileGate profile(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = """
                SELECT archetype, ascension, level, defense, strength, agility, intelligence, stamina
                FROM rpg_profiles WHERE player_uuid = ?
                """ + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                return new ProfileGate(Archetype.valueOf(rows.getString("archetype")), rows.getInt("ascension"),
                        rows.getInt("level"), rows.getInt("defense"), rows.getInt("strength"),
                        rows.getInt("agility"), rows.getInt("intelligence"), rows.getInt("stamina"));
            }
        }
    }

    private boolean learned(Connection connection, UUID playerId, String skillId, boolean lock) throws SQLException {
        String sql = "SELECT 1 FROM rpg_skills WHERE player_uuid = ? AND skill_id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            query.setString(2, skillId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean completedHiddenDungeon(Connection connection, UUID playerId, String dungeonId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM rpg_dungeon_reward_receipts
                WHERE player_uuid = ? AND dungeon_id = ? LIMIT 1
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, dungeonId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private boolean redeemedSkillBook(Connection connection, UUID playerId, String bookId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT 1 FROM rpg_skill_book_uses
                WHERE player_uuid = ? AND book_id = ? LIMIT 1
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, bookId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void ensureCapacityRow(Connection connection, HiddenPath path) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_hidden_path_caps (path, capacity) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, path.name());
            insert.setInt(2, HiddenPathRules.capacity(path));
            insert.executeUpdate();
        }
    }

    private int lockCapacity(Connection connection, HiddenPath path) throws SQLException {
        return capacity(connection, path, true);
    }

    private int capacity(Connection connection, HiddenPath path, boolean lock) throws SQLException {
        String sql = "SELECT capacity FROM rpg_hidden_path_caps WHERE path = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, path.name());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return HiddenPathRules.capacity(path);
                return Math.clamp(rows.getInt(1), 1, HiddenPathRules.capacity(path));
            }
        }
    }

    private int holders(Connection connection, HiddenPath path) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM rpg_hidden_paths WHERE path = ?")) {
            query.setString(1, path.name());
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return Math.toIntExact(rows.getLong(1));
            }
        }
    }

    private HiddenPathProfile findPath(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT path, activated_at FROM rpg_hidden_paths WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                Timestamp activated = rows.getTimestamp("activated_at");
                return new HiddenPathProfile(playerId, HiddenPath.valueOf(rows.getString("path")),
                        activated == null ? Instant.EPOCH : activated.toInstant());
            }
        }
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
            } catch (Exception failure) {
                connection.rollback();
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Hidden-path transaction failed", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not open hidden-path transaction", failure);
        }
    }

    private record ProfileGate(
            Archetype archetype, int ascension, int level, int defense, int strength,
            int agility, int intelligence, int stamina
    ) {
        int attribute(String name) {
            return switch (name) {
                case "defense" -> defense;
                case "strength" -> strength;
                case "agility" -> agility;
                case "intelligence" -> intelligence;
                case "stamina" -> stamina;
                default -> 0;
            };
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
