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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
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
    /**
     * 读缓存：值带一个「提交版本号」。版本号在写事务**仍持有行锁时（commit 之前）**取得，
     * 因此同一玩家的版本号顺序 == 行锁顺序 == 数据库提交顺序。发布时只接受更高的版本号
     * —— 晚到的旧快照不得回滚新快照。
     */
    private final Map<UUID, CachedProfile> cache = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong commitVersion = new java.util.concurrent.atomic.AtomicLong();

    public JdbcProfileService(DataSource dataSource, Executor executor, int startingEnergy, int energyPerStamina) {
        this(dataSource, executor, startingEnergy, energyPerStamina, () -> 0);
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
        CachedProfile cached = cache.get(playerId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached.profile());
        }
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            PlayerProfile profile = inTransaction(
                    connection -> loadOrCreate(connection, playerId, true), version);
            return new Committed<>(profile, version[0]);
        }).thenApply(committed -> {
            // 缓存未命中才回填。号在 FOR UPDATE 之后（见 loadOrCreate），
            // 因此这里的快照不会把更新的快照盖掉。
            publish(committed.value(), committed.version());
            return committed.value();
        });
    }

    @Override
    public Optional<PlayerProfile> cached(UUID playerId) {
        CachedProfile cached = cache.get(playerId);
        return Optional.ofNullable(cached == null ? null : cached.profile());
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
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            PlayerProfile updated = inTransaction(connection -> {
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
                PlayerProfile correctedProfile = new PlayerProfile(
                    current.playerId(), current.archetype(), current.ascension(), current.level(), current.experience(),
                    Math.min(current.energy(), energyCap), energyCap,
                    totalBudget - StarterBuildRules.STARTING_ATTRIBUTE_POINTS,
                    current.skillPoints(), corrected
                );
                update(connection, correctedProfile);
                return correctedProfile;
            }, version);
            return new Committed<>(updated, version[0]);
        }).thenApply(committed -> {
            publish(committed.value(), committed.version());
            return committed.value();
        });
    }

    @Override
    public CompletionStage<PlayerProfile> grantExperience(UUID playerId, long amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Experience must be positive"));
        }
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            PlayerProfile updated = inTransaction(connection -> {
                PlayerProfile current = loadOrCreate(connection, playerId, true);
                PlayerProfile leveled = withGrantedExperience(current, amount);
                // 直接授予经验没有业务 receipt，幂等依据是「档案 + 本段档位」：
                // 同一档位只落一行流水，重复授予到同一档位不再加点。
                int granted = settleSkillPointMilestones(connection,
                    skillPointContexts(current, leveled, profileSource(playerId)));
                PlayerProfile settled = withSkillPoints(leveled, granted);
                update(connection, settled);
                return settled;
            }, version);
            return new Committed<>(updated, version[0]);
        }).thenApply(committed -> {
            publish(committed.value(), committed.version());
            return committed.value();
        });
    }

    /** 直接授予经验时的来源标识（与副本/补偿的 receipt 来源区分开）。 */
    private static String profileSource(UUID playerId) {
        return "profile:" + playerId;
    }

    @Override
    public CompletionStage<Boolean> spendEnergy(UUID playerId, int amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            EnergySpend result = inTransaction(connection -> {
                PlayerProfile current = loadOrCreate(connection, playerId, true);
                if (current.energy() < amount) {
                    return new EnergySpend(false, current);
                }
                PlayerProfile updated = copy(current, current.archetype(), current.level(),
                        current.experience(), current.energy() - amount);
                update(connection, updated);
                return new EnergySpend(true, updated);
            }, version);
            return new Committed<>(result, version[0]);
        }).thenApply(committed -> {
            publish(committed.value().profile(), committed.version());
            return committed.value().spent();
        });
    }

    @Override
    public CompletionStage<PlayerProfile> restoreEnergy(UUID playerId, int amount) {
        if (amount <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Energy amount must be positive"));
        }
        CachedProfile cached = cache.get(playerId);
        if (cached != null && cached.profile().energy() >= cached.profile().energyCap()) {
            return CompletableFuture.completedFuture(cached.profile());
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
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            EnergyQuotaResult result = inTransaction(connection -> {
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
            }, version);
            return new Committed<>(result, version[0]);
        }).thenApply(committed -> {
            publish(committed.value().profile(), committed.version());
            return committed.value();
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
    public CompletionStage<SkillBookRedemption> redeemSkillBook(
            UUID playerId, UUID instanceId, String bookId, int points, String limitGroup, int maxUses
    ) {
        if (playerId == null || instanceId == null || bookId == null || !BOOK_ID.matcher(bookId).matches()
                || points < 1 || points > 1000 || maxUses < 0 || maxUses > 1000
                || (limitGroup != null && !LIMIT_GROUP.matcher(limitGroup).matches())
                || (limitGroup == null && maxUses != 0)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid skill-book redemption"));
        }
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            SkillBookRedemption result = inTransaction(connection -> {
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
                        if (raced == null) {
                            throw new IllegalStateException("Skill-book replay could not be resolved");
                        }
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
                        current.playerId(), current.archetype(), current.ascension(), current.level(),
                        current.experience(), current.energy(), current.energyCap(),
                        current.unspentAttributePoints(), updatedPoints, current.attributes()
                );
                update(connection, updated);
                return new SkillBookRedemption(
                        SkillBookRedemption.Status.SUCCESS, points, uses + (limitGroup == null ? 0 : 1), updated);
            }, version);
            return new Committed<>(result, version[0]);
        }).thenApply(committed -> {
            publish(committed.value().profile(), committed.version());
            return committed.value();
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
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            PlayerProfile updated = inTransaction(connection -> {
                PlayerProfile current = loadOrCreate(connection, playerId, true);
                PlayerProfile applied = mutation.apply(current);
                update(connection, applied);
                return applied;
            }, version);
            return new Committed<>(updated, version[0]);
        }).thenApply(committed -> {
            publish(committed.value(), committed.version());
            return committed.value();
        });
    }

    /** Package-private transaction bridge used by compound persistence services. */
    PlayerProfile loadForUpdate(Connection connection, UUID playerId) throws SQLException {
        return loadOrCreate(connection, playerId, true);
    }

    /**
     * Package-private transaction bridge：读一行 {@code FOR UPDATE} 只是为了持锁。
     *
     * <p>用于「锁的行不是档案表本身」的发布点（管理员测试模式的还原：锁定并删除
     * 的是 {@code rpg_admin_test_profiles} 快照行）。取号跟着这次 FOR UPDATE，
     * 保证取号顺序 == 加锁顺序。
     */
    void loadSnapshot(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid FROM rpg_profiles WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing profile row for lock acquisition");
                }
            }
        }
        markLocked(connection, true);
    }

    /** Package-private transaction bridge used by compound persistence services. */
    void updateInTransaction(Connection connection, PlayerProfile profile) throws SQLException {
        update(connection, profile);
    }

    /**
     * 提交后刷新读缓存。
     *
     * @param version 调用方事务**持锁时**取得的版本号（{@link #nextCommitVersion()}）。
     *                必须传该号，否则「谁先回调谁后写」又会把旧快照盖到新快照上。
     */
    void cacheCommitted(PlayerProfile profile, long version) {
        publish(profile, version);
    }

    /**
     * 发布已提交的快照到读缓存。
     *
     * <p>判据是**提交版本号**，不是「谁先回到主线程」：一个先提交的事务可能后执行
     * {@code thenApply}，把更旧的快照盖到更新的快照上，读缓存被回滚
     * （实测 {@code CACHED=19 DB=20}，即门禁 {@code expected: <20> but was: <19>}）。
     * 行锁保证数据库正确，但 {@link #get} 只读缓存，玩家因此读到回滚后的经验。
     *
     * @param version 该事务**持锁时（commit 之前）**取得的版本号（{@link #nextCommitVersion()}）
     */
    private void publish(PlayerProfile profile, long version) {
        cache.compute(profile.playerId(), (key, existing) ->
                existing == null || version > existing.version()
                        ? new CachedProfile(profile, version) : existing);
    }

    /** 取提交版本号（单调递增，全实例唯一）。**必须在持锁时、commit 之前调用**，见 inTransaction。 */
    long nextCommitVersion() {
        return commitVersion.incrementAndGet();
    }

    private record CachedProfile(PlayerProfile profile, long version) {
    }


    /**
     * Applies normal level/point progression inside a caller-owned transaction.
     *
     * <p>调用方（如副本结算 / 事故补偿）传业务来源与 receipt：receipt 只作审计，
     * 技能点去重靠「(玩家, 转, 档位)」档位键，见 {@link #settleSkillPointMilestones}。
     */
    PlayerProfile grantExperienceInTransaction(
            Connection connection, UUID playerId, long amount, UUID source, String receiptId
    ) throws SQLException {
        if (amount <= 0) throw new IllegalArgumentException("Experience must be positive");
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(receiptId, "receiptId");
        PlayerProfile profile = loadForUpdate(connection, playerId);
        PlayerProfile leveled = withGrantedExperience(profile, amount);
        // 技能点幂等同「档位」绑定，不与 receipt 绑定：receipt 只是审计备注。
        // 若把 receipt 混进唯一键，同一档位换一条 receipt 跨过会重复发点。
        int granted = settleSkillPointMilestones(connection,
                skillPointContexts(profile, leveled, source.toString()));
        PlayerProfile updated = withSkillPoints(leveled, granted);
        update(connection, updated);
        return updated;
    }

    /**
     * 一次升级结算跨越的里程碑列表。
     *
     * <p>幂等依据是「一行一个档位」，不是「一行一个结算区间」：
     * 一行一个区间会让同一档位被两条区间各写一行（本地 1→6 与 4→6 都过第 6 档），
     * 重放就无法只生效一次。因此键只取 (玩家, 转, 档位)。
     */
    private static List<SkillPointContext> skillPointContexts(
            PlayerProfile before, PlayerProfile after, String source
    ) {
        List<SkillPointContext> contexts = new ArrayList<>();
        int perPoint = ProgressionRules.LOCAL_LEVELS_PER_SKILL_POINT;
        int firstMilestone = before.level() / perPoint + 1;
        int lastMilestone = after.level() / perPoint;
        for (int milestone = firstMilestone; milestone <= lastMilestone; milestone++) {
            int atLocal = milestone * perPoint;
            contexts.add(new SkillPointContext(after.playerId(),
                    ProgressionRules.skillPointMilestoneSource(after.playerId(), after.ascension(), atLocal),
                    source, 1, after.ascension(), milestone, atLocal));
        }
        return contexts;
    }

    /**
     * 技能点里程碑结算（幂等）。
     *
     * <p>唯一依据是 rpg_skill_point_grants.milestone_key 主键 = (玩家, 转, 档位)：
     * INSERT ... ON CONFLICT DO NOTHING 成功才算「本次新发」。
     * 业务 receipt 只落审计列，不参与去重 —— 否则换一条 receipt 跨同一档位会重复发点。
     * 技能点不是货币，但仍然只有落库成功才加内存值，禁止内存补偿。
     *
     * @return 本次真正新落库的里程碑数（重放时为 0）
     */
    private int settleSkillPointMilestones(
            Connection connection, List<SkillPointContext> contexts
    ) throws SQLException {
        int granted = 0;
        for (SkillPointContext context : contexts) {
            if (settleSkillPointMilestone(connection, context)) granted++;
        }
        return granted;
    }

    private boolean settleSkillPointMilestone(
            Connection connection, SkillPointContext context
    ) throws SQLException {
        if (context.points() <= 0) return false;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_skill_point_grants (
                    milestone_key, receipt, player_uuid, points, ascension,
                    milestone_index, to_local_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, context.milestoneKey());
            insert.setString(2, context.receipt());
            insert.setString(3, context.playerId().toString());
            insert.setInt(4, context.points());
            insert.setInt(5, context.ascension());
            insert.setInt(6, context.milestoneIndex());
            insert.setInt(7, context.toLocalLevel());
            if (insert.executeUpdate() == 1) return true;
        }
        // 唯一键是 (玩家, 转, 档位)：冲突即「这一档已经发过了」，返回 false 不加点。
        // 不再比较 from_local_level —— 它描述的是结算区间，不是档位身份；
        // 拿它做一致性校验会把合法的第二条结算区间误判成键复用而整单回滚。
        return false;
    }

    private PlayerProfile loadOrCreate(Connection connection, UUID playerId, boolean lock) throws SQLException {
        PlayerProfile existing = select(connection, playerId, lock);
        if (existing != null) {
            // Phase 1 写入口：档案能被 ProgressionRules 唯一解释才是正常状态；
            // 读不懂的档案（老口径 level>30、越界转数、负经验）必须留下一行人工复核记录，
            // 否则这类档案会静默被 ceil 成合法值，后面再也查不出原始证据。
            recordAnomalyIfUninterpretable(connection, existing);
            markLocked(connection, lock);
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
        PlayerProfile created = select(connection, playerId, lock);
        // 新建行的 FOR UPDATE（第二次 select）已经持有行锁，取号点在其之后。
        markLocked(connection, lock);
        return created;
    }

    /**
     * 行锁到手后登记「本事务已经持锁」并按需取提交版本号。
     *
     * <p>取号纪律：**版本号必须在 {@code SELECT ... FOR UPDATE} 之后取**。顺序
     * 「取号 → 加锁」会留下这样的交错：T1 取号 v1、尚未加锁被抢占 → T2 取号
     * v2、加锁、提交 → T1 恢复、加锁、读到 T2 的值、提交（快照更新但号 v1 更小）
     * ⇒ {@code publish()} 保留号更大的 T2，读缓存被回滚。号在持锁时取，则记号的
     * 先后等于加锁先后、也就是提交先后。
     */
    private static void markLocked(Connection connection, boolean locked) {
        if (locked) LockedTransaction.markLocked(connection);
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

    /**
     * 用同一份 {@link ProgressionAnomalyService} 判定口径记录异常，保证「检测规则」只有一处实现。
     * 只读路径因此带一次条件写，但只在真的读到异常档案时才写行（正常档案零写放大）。
     */
    private void recordAnomalyIfUninterpretable(Connection connection, PlayerProfile profile) throws SQLException {
        String reason = ProgressionRules.classifyAnomaly(
                profile.ascension(), profile.level(), profile.experience());
        if (reason == null) return;
        // 与 JdbcProgressionAnomalyService 同一口径：H2 的 PostgreSQL 兼容模式不支持
        // 带冲突目标的 upsert，所以改成「已存在则 UPDATE，否则 INSERT」，两库等价。
        // 刷新快照必须同时清空上一次的复核结论（reviewer/review_note/reviewed_at）：
        // 档案又变成不可解释状态，旧署名不能替新事实背书，否则 [H] 门禁被上一次签字绕过。
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_progression_anomalies
                SET ascension = ?, level = ?, experience = ?, reason = ?,
                    detected_at = CURRENT_TIMESTAMP,
                    reviewer = NULL, review_note = NULL, reviewed_at = NULL
                WHERE player_uuid = ?
                """)) {
            update.setInt(1, profile.ascension());
            update.setInt(2, profile.level());
            update.setLong(3, profile.experience());
            update.setString(4, reason);
            update.setString(5, profile.playerId().toString());
            if (update.executeUpdate() == 1) return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_progression_anomalies
                (player_uuid, ascension, level, experience, reason)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, profile.playerId().toString());
            insert.setInt(2, profile.ascension());
            insert.setInt(3, profile.level());
            insert.setLong(4, profile.experience());
            insert.setString(5, reason);
            insert.executeUpdate();
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
        int levelsGained = localLevel - profile.level();
        // 技能点不在这里算：改由 settleSkillPointMilestones 按本地里程碑流水逐档结账
        // （见 withSkillPoints），这样重放同一结算不会二次发点。
        return new PlayerProfile(
                profile.playerId(), profile.archetype(), ascension, localLevel, experience, profile.energy(),
                profile.energyCap(), profile.unspentAttributePoints() + levelsGained,
                profile.skillPoints(), profile.attributes()
        );
    }

    /** 只在落库成功之后把技能点加上：禁止内存补偿发点。 */
    private static PlayerProfile withSkillPoints(PlayerProfile profile, int points) {
        if (points <= 0) return profile;
        return new PlayerProfile(
                profile.playerId(), profile.archetype(), profile.ascension(), profile.level(),
                profile.experience(), profile.energy(), profile.energyCap(),
                profile.unspentAttributePoints(), Math.addExact(profile.skillPoints(), points),
                profile.attributes()
        );
    }

    <T> T inTransaction(SqlOperation<T> operation) {
        return inTransaction(operation, NO_VERSION);
    }

    /** 不发布读缓存的路径（事务结束即丢弃版本号）。 */
    private static final long[] NO_VERSION = new long[1];

    /**
     * 提交版本号必须在**已经持有 {@code SELECT ... FOR UPDATE} 行锁之后**取得。
     *
     * <p>只把取号点从「{@code commit()} 之后」挪到「事务开头」并不够：事务开头还没加锁，
     * 会出现 —— T1 取号 v1、尚未加锁被抢占；T2 取号 v2、加锁、提交；T1 恢复、加锁、
     * 读到 T2 的值、提交。T1 的快照更新，但它的号 v1 更小，于是 {@code publish()} 保留
     * 号更大的 T2 的旧快照，读缓存被回滚。
     *
     * <p>做法：连接包一层 {@link LockedTransaction}，由它在收到
     * {@link LockedTransaction#markLocked(Connection)} 时才取号（{@link #loadForUpdate} /
     * {@link #loadSnapshot} 在 {@code FOR UPDATE} 之后调用它）。提交顺序因此与
     * 「取号顺序 == 加锁顺序」一致：后来的锁只能拿到更晚的号。
     *
     * <p>回滚路径不发布缓存（号已取但调用方不会走到 publish），取号浪费无害。
     *
     * @param versionStamp 出参：提交成功后回填持锁时取得的号；不需要发布的路径传
     *                     {@link #NO_VERSION}
     */
    <T> T inTransaction(SqlOperation<T> operation, long[] versionStamp) {
        try (Connection raw = dataSource.getConnection()) {
            Connection connection = LockedTransaction.wrap(raw, this::nextCommitVersion);
            try {
                T result = operation.apply(connection);
                connection.commit();
                versionStamp[0] = LockedTransaction.versionOf(connection);
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

    /**
     * 一次升级结算里单个里程碑的幂等依据：键是 (玩家, 转, 档位)，receipt 只作审计备注。
     *
     * @param milestoneKey 唯一键，形如 {@code local-milestone:<玩家>:<转>:<档位序号>}
     * @param receipt      业务凭据（副本 run_id / 事故 incident_key / 直接授予的 profile:&lt;uuid&gt;）
     */
    private record SkillPointContext(UUID playerId, String milestoneKey, String receipt, int points,
                                     int ascension, int milestoneIndex, int toLocalLevel) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
