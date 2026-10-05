package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.AdminTestState;
import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.service.AdminTestService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcAdminTestService implements AdminTestService {
    private static final Pattern SKILL_ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private final DataSource dataSource;
    private final Executor executor;
    private final JdbcProfileService profiles;
    private final JdbcSkillService skills;
    private final int startingEnergy;
    private final int energyPerStamina;

    public JdbcAdminTestService(
            DataSource dataSource,
            Executor executor,
            JdbcProfileService profiles,
            JdbcSkillService skills,
            int startingEnergy,
            int energyPerStamina
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.profiles = profiles;
        this.skills = skills;
        this.startingEnergy = startingEnergy;
        this.energyPerStamina = energyPerStamina;
    }

    @Override
    public CompletionStage<AdminTestState> status(UUID playerId) {
        // 纯读、事务里只有 SELECT（不写任何行），因此不发布读缓存：没有提交就不该
        // 让读缓存跟着变。锁仍按原样持有（读一致性不变）。
        return AsyncJdbc.supply(executor, () -> {
            AdminTestState state = inTransaction(connection -> {
                PlayerProfile profile = profiles.loadForUpdate(connection, playerId);
                return new AdminTestState(hasSnapshot(connection, playerId), profile,
                        skillCount(connection, playerId));
            }, LockedTransaction.NO_STAMP);
            return state;
        });
    }

    @Override
    public CompletionStage<AdminTestState> activate(
            UUID playerId,
            Archetype archetype,
            Map<String, Integer> skillLevels,
            int attributeValue,
            int skillPoints
    ) {
        validate(archetype, skillLevels, attributeValue, skillPoints);
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            AdminTestState state = inTransaction(connection -> {
                PlayerProfile current = profiles.loadForUpdate(connection, playerId);
                if (!hasSnapshot(connection, playerId)) saveSnapshot(connection, current);

                EnumMap<CoreAttribute, Integer> attributes = new EnumMap<>(CoreAttribute.class);
                for (CoreAttribute attribute : CoreAttribute.values()) attributes.put(attribute, attributeValue);
                int energyCap = startingEnergy + attributeValue * energyPerStamina;
                PlayerProfile testing = new PlayerProfile(
                    playerId, archetype, ProgressionRules.MAX_ASCENSION,
                    ProgressionRules.levelCap(ProgressionRules.MAX_ASCENSION), 0,
                    energyCap, energyCap, 0, skillPoints, attributes);
                profiles.updateInTransaction(connection, testing);
                replaceSkills(connection, playerId, skillLevels);
                return new AdminTestState(true, testing, skillLevels.size());
            }, version);
            return new Committed<>(state, version[0]);
        }).thenApply(committed -> refreshCaches(playerId, committed.value(), committed.version()));
    }

    @Override
    public CompletionStage<AdminTestState> switchArchetype(UUID playerId, Archetype archetype) {
        if (playerId == null || archetype == null || archetype == Archetype.UNCHOSEN) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("A concrete archetype is required"));
        }
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            AdminTestState state = inTransaction(connection -> {
                if (!hasSnapshot(connection, playerId)) {
                throw new IllegalStateException("Administrator test mode is not active");
                }
                PlayerProfile current = profiles.loadForUpdate(connection, playerId);
                PlayerProfile changed = new PlayerProfile(
                    playerId, archetype, current.ascension(), current.level(), current.experience(),
                    current.energy(), current.energyCap(), current.unspentAttributePoints(),
                    current.skillPoints(), current.attributes());
                profiles.updateInTransaction(connection, changed);
                return new AdminTestState(true, changed, skillCount(connection, playerId));
            }, version);
            return new Committed<>(state, version[0]);
        }).thenApply(committed -> refreshCaches(playerId, committed.value(), committed.version()));
    }

    @Override
    public CompletionStage<AdminTestState> restore(UUID playerId) {
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            AdminTestState state = inTransaction(connection -> {
                PlayerProfile snapshot = loadSnapshot(connection, playerId);
                if (snapshot == null) throw new IllegalStateException("Administrator test mode is not active");
                // 快照行（rpg_admin_test_profiles）已 FOR UPDATE，但档案行的锁要单独取：
                // 取号必须在 update 之前、且必须有一次行锁在手，否则发布顺序会倒置。
                profiles.loadSnapshot(connection, playerId);
                profiles.updateInTransaction(connection, snapshot);
                restoreSkills(connection, playerId);
                int restoredSkills = skillCount(connection, playerId);
                deleteSnapshot(connection, playerId);
                return new AdminTestState(false, snapshot, restoredSkills);
            }, version);
            return new Committed<>(state, version[0]);
        }).thenApply(committed -> refreshCaches(playerId, committed.value(), committed.version()));
    }

    private AdminTestState refreshCaches(UUID playerId, AdminTestState state, long version) {
        profiles.cacheCommitted(state.profile(), version);
        skills.evict(playerId);
        return state;
    }

    private void validate(
            Archetype archetype, Map<String, Integer> skillLevels, int attributeValue, int skillPoints
    ) {
        if (archetype == null || archetype == Archetype.UNCHOSEN || skillLevels == null
                || skillLevels.isEmpty() || skillLevels.size() > 128
                || attributeValue < 1 || attributeValue > 1_000
                || skillPoints < 1 || skillPoints > 1_000_000) {
            throw new IllegalArgumentException("Invalid administrator test-mode request");
        }
        skillLevels.forEach((id, level) -> {
            if (id == null || !SKILL_ID.matcher(id).matches() || level == null || level < 1 || level > 100) {
                throw new IllegalArgumentException("Invalid administrator test skill: " + id);
            }
        });
    }

    private void saveSnapshot(Connection connection, PlayerProfile profile) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_admin_test_profiles (
                    player_uuid, archetype, ascension, level, experience, energy,
                    unspent_attribute_points, skill_points, defense, strength, agility, intelligence, stamina
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            bindProfile(insert, profile);
            insert.executeUpdate();
        }
        try (PreparedStatement copy = connection.prepareStatement("""
                INSERT INTO rpg_admin_test_skills (player_uuid, skill_id, level, point_cost, variant)
                SELECT player_uuid, skill_id, level, point_cost, variant
                FROM rpg_skills WHERE player_uuid = ?
                """)) {
            copy.setString(1, profile.playerId().toString());
            copy.executeUpdate();
        }
    }

    private void replaceSkills(Connection connection, UUID playerId, Map<String, Integer> skillLevels)
            throws SQLException {
        deleteCurrentSkills(connection, playerId);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost, variant)
                VALUES (?, ?, ?, 0, NULL)
                """)) {
            for (Map.Entry<String, Integer> entry : skillLevels.entrySet()) {
                insert.setString(1, playerId.toString());
                insert.setString(2, entry.getKey());
                insert.setInt(3, entry.getValue());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void restoreSkills(Connection connection, UUID playerId) throws SQLException {
        deleteCurrentSkills(connection, playerId);
        try (PreparedStatement copy = connection.prepareStatement("""
                INSERT INTO rpg_skills (player_uuid, skill_id, level, point_cost, variant)
                SELECT player_uuid, skill_id, level, point_cost, variant
                FROM rpg_admin_test_skills WHERE player_uuid = ?
                """)) {
            copy.setString(1, playerId.toString());
            copy.executeUpdate();
        }
    }

    private void deleteCurrentSkills(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM rpg_skills WHERE player_uuid = ?")) {
            delete.setString(1, playerId.toString());
            delete.executeUpdate();
        }
    }

    private boolean hasSnapshot(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_admin_test_profiles WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private PlayerProfile loadSnapshot(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM rpg_admin_test_profiles WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                EnumMap<CoreAttribute, Integer> attributes = new EnumMap<>(CoreAttribute.class);
                attributes.put(CoreAttribute.DEFENSE, result.getInt("defense"));
                attributes.put(CoreAttribute.STRENGTH, result.getInt("strength"));
                attributes.put(CoreAttribute.AGILITY, result.getInt("agility"));
                attributes.put(CoreAttribute.INTELLIGENCE, result.getInt("intelligence"));
                attributes.put(CoreAttribute.STAMINA, result.getInt("stamina"));
                int cap = startingEnergy + result.getInt("stamina") * energyPerStamina;
                return new PlayerProfile(
                        playerId, Archetype.valueOf(result.getString("archetype")),
                        result.getInt("ascension"), result.getInt("level"), result.getLong("experience"),
                        Math.min(result.getInt("energy"), cap), cap,
                        result.getInt("unspent_attribute_points"), result.getInt("skill_points"), attributes);
            }
        }
    }

    private void deleteSnapshot(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement deleteSkills = connection.prepareStatement(
                "DELETE FROM rpg_admin_test_skills WHERE player_uuid = ?");
             PreparedStatement deleteProfile = connection.prepareStatement(
                     "DELETE FROM rpg_admin_test_profiles WHERE player_uuid = ?")) {
            deleteSkills.setString(1, playerId.toString());
            deleteSkills.executeUpdate();
            deleteProfile.setString(1, playerId.toString());
            deleteProfile.executeUpdate();
        }
    }

    private int skillCount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM rpg_skills WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void bindProfile(PreparedStatement statement, PlayerProfile profile) throws SQLException {
        statement.setString(1, profile.playerId().toString());
        statement.setString(2, profile.archetype().name());
        statement.setInt(3, profile.ascension());
        statement.setInt(4, profile.level());
        statement.setLong(5, profile.experience());
        statement.setInt(6, profile.energy());
        statement.setInt(7, profile.unspentAttributePoints());
        statement.setInt(8, profile.skillPoints());
        statement.setInt(9, profile.attribute(CoreAttribute.DEFENSE));
        statement.setInt(10, profile.attribute(CoreAttribute.STRENGTH));
        statement.setInt(11, profile.attribute(CoreAttribute.AGILITY));
        statement.setInt(12, profile.attribute(CoreAttribute.INTELLIGENCE));
        statement.setInt(13, profile.attribute(CoreAttribute.STAMINA));
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        return inTransaction(operation, new long[1]);
    }

    /**
     * @param versionStamp 出参：提交成功后回填「**持锁时（commit 之前）**」取得的提交版本号
     *                     （{@link JdbcProfileService#nextCommitVersion()}）。取号由
     *                     {@link LockedTransaction} 在业务代码第一次持锁之后完成，因此
     *                     取号顺序 == 加锁顺序 == 提交顺序。
     */
    private <T> T inTransaction(SqlOperation<T> operation, long[] versionStamp) {
        try (Connection raw = dataSource.getConnection()) {
            Connection connection = LockedTransaction.wrap(raw, profiles::nextCommitVersion);
            try {
                T result = operation.apply(connection);
                connection.commit();
                versionStamp[0] = LockedTransaction.versionOf(connection);
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) throw runtimeException;
                throw new IllegalStateException("Administrator test transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open administrator test transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
