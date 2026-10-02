package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EnergyQuotaResult;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.model.SkillBookRedemption;
import cn.originalrpg.api.rules.ProgressionRules;
import cn.originalrpg.api.rules.StarterBuildRules;
import cn.originalrpg.api.service.ProfileService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

public final class JdbcProfileService implements ProfileService {
    private static final Pattern BOOK_ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final Pattern LIMIT_GROUP = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final Pattern QUOTA_SOURCE = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final Pattern PERIOD_KEY = Pattern.compile("[a-zA-Z0-9._:-]{1,32}");
    private static final int MAX_SKILL_POINTS = 1_000_000_000;
    private final DataSource dataSource;
    private final Executor executor;
    private final int startingEnergy;
    private final int energyPerStamina;
    private final IntSupplier maximumOpenAscension;
    private final Map<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();

    public JdbcProfileService(DataSource dataSource, Executor executor, int startingEnergy, int energyPerStamina) {
        this(dataSource, executor, startingEnergy, energyPerStamina, 0);
    }

    public JdbcProfileService(
            DataSource dataSource, Executor executor, int startingEnergy, int energyPerStamina, int maxOpenAscension
    ) {
        this(dataSource, executor, startingEnergy, energyPerStamina,
                () -> Math.clamp(maxOpenAscension, 0, ProgressionRules.MAX_ASCENSION));
    }

    public JdbcProfileService(
            DataSource dataSource, Executor executor, int startingEnergy, int energyPerStamina,
            IntSupplier maximumOpenAscension
    ) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingEnergy = startingEnergy;
        this.energyPerStamina = energyPerStamina;
        this.maximumOpenAscension = java.util.Objects.requireNonNull(
                maximumOpenAscension, "maximumOpenAscension");
    }

    @Override
    public CompletionStage<PlayerProfile> get(UUID playerId) {
        PlayerProfile cached = cache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> loadOrCreate(connection, playerId, true)))
                .thenApply(profile -> {
                    cache.put(playerId, profile);
                    return profile;
                });
    }

    @Override
    public Optional<PlayerProfile> cached(UUID playerId) {
        return Optional.ofNullable(cache.get(playerId));
    }

    @Override
    public void evict(UUID playerId) {
        if (playerId != null) cache.remove(playerId);
    }

    @Override
    public CompletionStage<PlayerProfile> chooseArchetype(UUID playerId, Archetype archetype) {
        if (archetype == Archetype.UNCHOSEN) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Cannot choose UNCHOSEN"));
        }
        return mutate(playerId, profile -> {
            if (profile.archetype() != Archetype.UNCHOSEN) {
                throw new IllegalStateException("The starting class has already been selected");
            }
            return copy(profile, archetype, profile.level(), profile.experience(), profile.energy());
        });
    }

    @Override
    public CompletionStage<PlayerProfile> allocateAttribute(UUID playerId, CoreAttribute attribute) {
        return mutate(playerId, profile -> {
            if (profile.unspentAttributePoints() <= 0) {
                throw new IllegalStateException("No unspent attribute points are available");
            }
            EnumMap<CoreAttribute, Integer> attributes = new EnumMap<>(CoreAttribute.class);
            attributes.putAll(profile.attributes());
            attributes.merge(attribute, 1, Integer::sum);
            int energyCap = startingEnergy + attributes.getOrDefault(CoreAttribute.STAMINA, 0) * energyPerStamina;
            return new PlayerProfile(
                    profile.playerId(), profile.archetype(), profile.ascension(), profile.level(), profile.experience(),
                    Math.min(profile.energy(), energyCap), energyCap, profile.unspentAttributePoints() - 1,
                    profile.skillPoints(), attributes
            );
        });
    }

    @Override
    public CompletionStage<PlayerProfile> prepareStarterBuild(UUID playerId) {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PlayerProfile current = loadOrCreate(connection, playerId, true);
            if (current.archetype() == Archetype.UNCHOSEN) {
                throw new IllegalStateException("A class is required before starter correction");
            }
            if (StarterBuildRules.ready(current.archetype(), current.attributes())) return current;
            int allocated = current.attributes().values().stream()
                    .mapToInt(value -> Math.max(0, value)).sum();
            int totalBudget = Math.addExact(allocated, current.unspentAttributePoints());
            if (totalBudget < StarterBuildRules.STARTING_ATTRIBUTE_POINTS) {
                throw new IllegalStateException("Starter attribute budget is incomplete");
            }
            EnumMap<CoreAttribute, Integer> corrected = new EnumMap<>(CoreAttribute.class);
            corrected.putAll(StarterBuildRules.requirements(current.archetype()));
            int energyCap = startingEnergy + corrected.getOrDefault(CoreAttribute.STAMINA, 0) * energyPerStamina;
            PlayerProfile updated = new PlayerProfile(
                    current.playerId(), current.archetype(), current.ascension(), current.level(), current.experience(),
                    Math.min(current.energy(), energyCap), energyCap,
                    totalBudget - StarterBuildRules.STARTING_ATTRIBUTE_POINTS,
                    current.skillPoints(), corrected
            );
            update(connection, updated);
            return updated;
        })).thenApply(updated -> {
            cache.put(playerId, updated);
            return updated;
        });
    }

    @Override
    public CompletionStage<PlayerProfile> grantExperience(UUID playerId, long amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Experience must be positive"));
        }
        return mutate(playerId, profile -> withGrantedExperience(profile, amount));
    }

    @Override
    public CompletionStage<Boolean> spendEnergy(UUID playerId, int amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PlayerProfile current = loadOrCreate(connection, playerId, true);
            if (current.energy() < amount) {
                return new EnergySpend(false, current);
            }
            PlayerProfile updated = copy(current, current.archetype(), current.level(), current.experience(), current.energy() - amount);
            update(connection, updated);
            return new EnergySpend(true, updated);
        })).thenApply(result -> {
            cache.put(playerId, result.profile());
            return result.spent();
        });
    }

    @Override
    public CompletionStage<PlayerProfile> restoreEnergy(UUID playerId, int amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Energy amount must be positive"));
        }
        PlayerProfile cached = cache.get(playerId);
        if (cached != null && cached.energy() >= cached.energyCap()) {
            return CompletableFuture.completedFuture(cached);
        }
        return mutate(playerId, profile -> copy(
                profile,
                profile.archetype(),
                profile.level(),
                profile.experience(),
                Math.min(profile.energyCap(), profile.energy() + amount)
        ));
    }

    @Override
    public CompletionStage<EnergyQuotaResult> restoreEnergyWithQuota(
            UUID playerId, String sourceId, String periodKey, int baseAmount,
            int decayEveryUses, int decayAmount, int minimumAmount, int periodLimit
    ) {
        if (playerId == null || sourceId == null || !QUOTA_SOURCE.matcher(sourceId).matches()
                || periodKey == null || !PERIOD_KEY.matcher(periodKey).matches()
                || baseAmount < 1 || baseAmount > 10_000
                || decayEveryUses < 1 || decayEveryUses > 10_000
                || decayAmount < 0 || decayAmount > baseAmount
                || minimumAmount < 1 || minimumAmount > baseAmount
                || periodLimit < 1 || periodLimit > 1_000_000) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid Energy quota request"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PlayerProfile current = loadOrCreate(connection, playerId, true);
            ensureEnergyQuota(connection, playerId, sourceId, periodKey);
            EnergyQuota quota = lockEnergyQuota(connection, playerId, sourceId, periodKey);
            int next = diminishingGrant(
                    quota.uses(), baseAmount, decayEveryUses, decayAmount, minimumAmount);
            if (current.energy() >= current.energyCap()) {
                return new EnergyQuotaResult(EnergyQuotaResult.Status.ENERGY_FULL, current,
                        0, quota.uses(), Math.min(quota.energyRestored(), periodLimit), periodLimit,
                        quota.energyRestored() >= periodLimit ? 0 : next);
            }
            if (quota.energyRestored() >= periodLimit) {
                return new EnergyQuotaResult(EnergyQuotaResult.Status.PERIOD_LIMIT, current,
                        0, quota.uses(), periodLimit, periodLimit, 0);
            }
            int granted = Math.min(next, Math.min(
                    periodLimit - quota.energyRestored(), current.energyCap() - current.energy()));
            if (granted <= 0) {
                return new EnergyQuotaResult(EnergyQuotaResult.Status.PERIOD_LIMIT, current,
                        0, quota.uses(), Math.min(quota.energyRestored(), periodLimit), periodLimit, 0);
            }
            PlayerProfile updated = copy(current, current.archetype(), current.level(),
                    current.experience(), current.energy() + granted);
            update(connection, updated);
            int updatedUses = quota.uses() + 1;
            int updatedTotal = quota.energyRestored() + granted;
            updateEnergyQuota(connection, playerId, sourceId, periodKey, updatedUses, updatedTotal);
            int following = updatedTotal >= periodLimit ? 0 : diminishingGrant(
                    updatedUses, baseAmount, decayEveryUses, decayAmount, minimumAmount);
            return new EnergyQuotaResult(EnergyQuotaResult.Status.SUCCESS, updated,
                    granted, updatedUses, updatedTotal, periodLimit, following);
        })).thenApply(result -> {
            cache.put(playerId, result.profile());
            return result;
        });
    }

    private static int diminishingGrant(
            int uses, int baseAmount, int decayEveryUses, int decayAmount, int minimumAmount
    ) {
        long reduction = (long) Math.max(0, uses) / decayEveryUses * decayAmount;
        return (int) Math.max(minimumAmount, Math.max(0L, (long) baseAmount - reduction));
    }

    private void ensureEnergyQuota(
            Connection connection, UUID playerId, String sourceId, String periodKey
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_energy_recovery_quotas (
                    player_uuid, source_id, period_key, uses, energy_restored
                ) VALUES (?, ?, ?, 0, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, sourceId);
            insert.setString(3, periodKey);
            insert.executeUpdate();
        }
    }

    private EnergyQuota lockEnergyQuota(
            Connection connection, UUID playerId, String sourceId, String periodKey
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT uses, energy_restored FROM rpg_energy_recovery_quotas
                WHERE player_uuid = ? AND source_id = ? AND period_key = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, sourceId);
            query.setString(3, periodKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Energy quota row disappeared");
                return new EnergyQuota(Math.max(0, result.getInt("uses")),
                        Math.max(0, result.getInt("energy_restored")));
            }
        }
    }

    private void updateEnergyQuota(
            Connection connection, UUID playerId, String sourceId, String periodKey,
            int uses, int energyRestored
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_energy_recovery_quotas
                SET uses = ?, energy_restored = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND source_id = ? AND period_key = ?
                """)) {
            update.setInt(1, uses);
            update.setInt(2, energyRestored);
            update.setString(3, playerId.toString());
            update.setString(4, sourceId);
            update.setString(5, periodKey);
            if (update.executeUpdate() != 1) throw new IllegalStateException("Energy quota update was lost");
        }
    }

    @Override
    public CompletionStage<PlayerProfile> ascend(UUID playerId) {
        // Deprecated bypass — retained for test harness and single-node dev; production ascensions
        // must use AscensionService (transactional, branch/skill/dungeon/merit checks, C-015).
        return mutate(playerId, profile -> {
            int nextAscension = profile.ascension() + 1;
            if (profile.archetype() == Archetype.UNCHOSEN) {
                throw new IllegalStateException("A starting class must be selected first");
            }
            int openAscension = Math.clamp(
                    maximumOpenAscension.getAsInt(), 0, ProgressionRules.MAX_ASCENSION);
            if (nextAscension > openAscension) {
                throw new IllegalStateException("The next ascension is not open for this chapter");
            }
            if (profile.level() < ProgressionRules.levelCap(profile.ascension())) {
                throw new IllegalStateException("The current stage level cap has not been reached");
            }
            return new PlayerProfile(
                    profile.playerId(), profile.archetype(), nextAscension, 1, 0,
                    profile.energyCap(), profile.energyCap(), profile.unspentAttributePoints(),
                    profile.skillPoints(), profile.attributes()
            );
        });
    }

    @Override
    public CompletionStage<SkillBookRedemption> redeemSkillBook(
            UUID playerId, UUID instanceId, String bookId, int points, String limitGroup, int maxUses
    ) {
        if (playerId == null || instanceId == null || bookId == null || !BOOK_ID.matcher(bookId).matches()
                || points < 1 || points > 1000 || maxUses < 0 || maxUses > 1000
                || (limitGroup != null && !LIMIT_GROUP.matcher(limitGroup).matches())
                || (limitGroup == null && maxUses != 0)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid skill-book redemption"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PlayerProfile current = loadOrCreate(connection, playerId, true);
            UsedBook existing = usedBook(connection, instanceId);
            if (existing != null) {
                return new SkillBookRedemption(
                        SkillBookRedemption.Status.DUPLICATE,
                        existing.pointsGranted(),
                        countUses(connection, playerId, existing.limitGroup()),
                        current
                );
            }

            int uses = countUses(connection, playerId, limitGroup);
            if (limitGroup != null && maxUses > 0 && uses >= maxUses) {
                return new SkillBookRedemption(
                        SkillBookRedemption.Status.LIMIT_REACHED, 0, uses, current);
            }
            if (current.skillPoints() > MAX_SKILL_POINTS - points) {
                return new SkillBookRedemption(
                        SkillBookRedemption.Status.POINT_LIMIT, 0, uses, current);
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_skill_book_uses (
                        instance_id, player_uuid, book_id, limit_group, points_granted
                    ) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, instanceId.toString());
                insert.setString(2, playerId.toString());
                insert.setString(3, bookId);
                insert.setString(4, limitGroup);
                insert.setInt(5, points);
                if (insert.executeUpdate() != 1) {
                    UsedBook raced = usedBook(connection, instanceId);
                    if (raced == null) throw new IllegalStateException("Skill-book replay could not be resolved");
                    return new SkillBookRedemption(
                            SkillBookRedemption.Status.DUPLICATE,
                            raced.pointsGranted(),
                            countUses(connection, playerId, raced.limitGroup()),
                            current
                    );
                }
            }

            int updatedPoints = current.skillPoints() + points;
            PlayerProfile updated = new PlayerProfile(
                    current.playerId(), current.archetype(), current.ascension(), current.level(), current.experience(),
                    current.energy(), current.energyCap(), current.unspentAttributePoints(), updatedPoints,
                    current.attributes()
            );
            update(connection, updated);
            return new SkillBookRedemption(
                    SkillBookRedemption.Status.SUCCESS, points, uses + (limitGroup == null ? 0 : 1), updated);
        })).thenApply(result -> {
            cache.put(playerId, result.profile());
            return result;
        });
    }

    private UsedBook usedBook(Connection connection, UUID instanceId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT points_granted, limit_group FROM rpg_skill_book_uses WHERE instance_id = ?
                """)) {
            query.setString(1, instanceId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new UsedBook(result.getInt("points_granted"), result.getString("limit_group")) : null;
            }
        }
    }

    private int countUses(Connection connection, UUID playerId, String limitGroup) throws SQLException {
        if (limitGroup == null) return 0;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_skill_book_uses WHERE player_uuid = ? AND limit_group = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, limitGroup);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private CompletionStage<PlayerProfile> mutate(UUID playerId, ProfileMutation mutation) {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            PlayerProfile current = loadOrCreate(connection, playerId, true);
            PlayerProfile updated = mutation.apply(current);
            update(connection, updated);
            return updated;
        })).thenApply(updated -> {
            cache.put(playerId, updated);
            return updated;
        });
    }

    /** Package-private transaction bridge used by compound persistence services. */
    PlayerProfile loadForUpdate(Connection connection, UUID playerId) throws SQLException {
        return loadOrCreate(connection, playerId, true);
    }

    /** Package-private transaction bridge used by compound persistence services. */
    void updateInTransaction(Connection connection, PlayerProfile profile) throws SQLException {
        update(connection, profile);
    }

    /** Refreshes the read cache only after the caller's transaction has committed. */
    void cacheCommitted(PlayerProfile profile) {
        cache.put(profile.playerId(), profile);
    }

    /** Applies normal level/point progression inside a caller-owned transaction. */
    PlayerProfile grantExperienceInTransaction(Connection connection, UUID playerId, long amount)
            throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("Experience must be positive");
        PlayerProfile profile = loadForUpdate(connection, playerId);
        PlayerProfile updated = withGrantedExperience(profile, amount);
        update(connection, updated);
        return updated;
    }

    private PlayerProfile loadOrCreate(Connection connection, UUID playerId, boolean lock) throws SQLException {
        PlayerProfile existing = select(connection, playerId, lock);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_profiles (
                    player_uuid, archetype, ascension, level, experience, energy,
                    unspent_attribute_points, skill_points, defense, strength, agility, intelligence, stamina
                ) VALUES (?, ?, 0, 1, 0, ?, 5, 1, 0, 0, 0, 0, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, Archetype.UNCHOSEN.name());
            insert.setInt(3, startingEnergy);
            insert.executeUpdate();
        }
        return select(connection, playerId, lock);
    }

    private PlayerProfile select(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT * FROM rpg_profiles WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                EnumMap<CoreAttribute, Integer> attributes = new EnumMap<>(CoreAttribute.class);
                attributes.put(CoreAttribute.DEFENSE, result.getInt("defense"));
                attributes.put(CoreAttribute.STRENGTH, result.getInt("strength"));
                attributes.put(CoreAttribute.AGILITY, result.getInt("agility"));
                attributes.put(CoreAttribute.INTELLIGENCE, result.getInt("intelligence"));
                attributes.put(CoreAttribute.STAMINA, result.getInt("stamina"));
                int energyCap = startingEnergy + result.getInt("stamina") * energyPerStamina;
                return new PlayerProfile(
                        playerId,
                        Archetype.valueOf(result.getString("archetype")),
                        result.getInt("ascension"),
                        result.getInt("level"),
                        result.getLong("experience"),
                        Math.min(result.getInt("energy"), energyCap),
                        energyCap,
                        result.getInt("unspent_attribute_points"),
                        result.getInt("skill_points"),
                        attributes
                );
            }
        }
    }

    private void update(Connection connection, PlayerProfile profile) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_profiles SET archetype = ?, ascension = ?, level = ?, experience = ?, energy = ?,
                    unspent_attribute_points = ?, skill_points = ?, defense = ?, strength = ?, agility = ?,
                    intelligence = ?, stamina = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ?
                """)) {
            update.setString(1, profile.archetype().name());
            update.setInt(2, profile.ascension());
            update.setInt(3, profile.level());
            update.setLong(4, profile.experience());
            update.setInt(5, profile.energy());
            update.setInt(6, profile.unspentAttributePoints());
            update.setInt(7, profile.skillPoints());
            update.setInt(8, profile.attribute(CoreAttribute.DEFENSE));
            update.setInt(9, profile.attribute(CoreAttribute.STRENGTH));
            update.setInt(10, profile.attribute(CoreAttribute.AGILITY));
            update.setInt(11, profile.attribute(CoreAttribute.INTELLIGENCE));
            update.setInt(12, profile.attribute(CoreAttribute.STAMINA));
            update.setString(13, profile.playerId().toString());
            update.executeUpdate();
        }
    }

    private PlayerProfile copy(PlayerProfile profile, Archetype archetype, int level, long experience, int energy) {
        return new PlayerProfile(
                profile.playerId(), archetype, profile.ascension(), level, experience, energy,
                profile.energyCap(), profile.unspentAttributePoints(), profile.skillPoints(), profile.attributes()
        );
    }

    private PlayerProfile withGrantedExperience(PlayerProfile profile, long amount) {
        int ascension = profile.ascension();
        int localLevel = profile.level();
        // Validate local invariant (defensive against legacy rows with level>30)
        if (localLevel < 1 || localLevel > ProgressionRules.LOCAL_LEVEL_CAP) {
            throw new IllegalStateException("Corrupted profile: localLevel=" + localLevel + " ascension=" + ascension);
        }
        int startingEffective = ProgressionRules.effectiveLevel(ascension, localLevel);
        long experience = profile.experience() > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE : profile.experience() + amount;
        int cap = ProgressionRules.LOCAL_LEVEL_CAP;
        while (localLevel < cap) {
            long needed = ProgressionRules.experienceToNextLocalLevel(ascension, localLevel);
            if (experience < needed) break;
            experience -= needed;
            localLevel++;
        }
        if (localLevel == cap) {
            // At local cap, clamp to next stage's first step -1 or sentinel
            long clampNeeded = (ascension < ProgressionRules.MAX_ASCENSION)
                    ? ProgressionRules.experienceToNextLocalLevel(ascension, cap - 1)
                    : Long.MAX_VALUE;
            if (clampNeeded != Long.MAX_VALUE) {
                experience = Math.min(experience, clampNeeded - 1);
            }
        }
        int finalEffective = ProgressionRules.effectiveLevel(ascension, localLevel);
        int levelsGained = localLevel - profile.level();
        // Skill points are based on effective level milestones (every 3 effective levels)
        int skillPointsGained = finalEffective / 3 - startingEffective / 3;
        // Guard against negative due to corrupted startingEffective
        if (skillPointsGained < 0) skillPointsGained = 0;
        return new PlayerProfile(
                profile.playerId(), profile.archetype(), ascension, localLevel, experience, profile.energy(),
                profile.energyCap(), profile.unspentAttributePoints() + levelsGained,
                profile.skillPoints() + skillPointsGained, profile.attributes()
        );
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Profile transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open profile transaction", exception);
        }
    }

    @FunctionalInterface
    private interface ProfileMutation {
        PlayerProfile apply(PlayerProfile profile);
    }

    private record EnergySpend(boolean spent, PlayerProfile profile) {
    }

    private record EnergyQuota(int uses, int energyRestored) {
    }

    private record UsedBook(int pointsGranted, String limitGroup) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
