package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.ActivityQuota;
import cn.originalrpg.api.model.ActivityRewardResult;
import cn.originalrpg.api.model.ActivityRewardRequest;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.EconomyProgress;
import cn.originalrpg.api.model.EconomyPurchaseResult;
import cn.originalrpg.api.model.DailyItemRewardResult;
import cn.originalrpg.api.service.ActivityEconomyService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcActivityEconomyService implements ActivityEconomyService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{3,96}");
    private static final BigDecimal MAX_GOLD = new BigDecimal("1000000000000.00");
    private static final long MAX_MERIT = 1_000_000_000L;
    private static final int MAX_DAILY_ITEM_CAP = 128;
    private static final int MAX_DAILY_ITEM_PAYLOAD = 262_144;

    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;

    public JdbcActivityEconomyService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
    }

    @Override
    public CompletionStage<EconomyProgress> progress(UUID playerId) {
        if (playerId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("playerId"));
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> progress(connection, playerId, false)));
    }

    @Override
    public CompletionStage<ActivityRewardResult> reward(
            UUID playerId,
            EconomyActivity activity,
            BigDecimal requestedGold,
            long requestedMerit,
            BigDecimal dailyGoldCap,
            long dailyMeritCap,
            String idempotencyKey,
            String reason
    ) {
        if (!validReward(playerId, activity, requestedGold, requestedMerit, dailyGoldCap, dailyMeritCap,
                idempotencyKey, reason)) {
            return CompletableFuture.completedFuture(new ActivityRewardResult(
                    ActivityRewardResult.Status.INVALID, null, zero(), 0));
        }
        return rewardBatch(playerId, activity, List.of(new ActivityRewardRequest(
                requestedGold, requestedMerit, idempotencyKey, reason)), dailyGoldCap, dailyMeritCap);
    }

    @Override
    public CompletionStage<ActivityRewardResult> rewardExact(
            UUID playerId,
            EconomyActivity activity,
            BigDecimal requestedGold,
            long requestedMerit,
            BigDecimal dailyGoldCap,
            long dailyMeritCap,
            String idempotencyKey,
            String reason
    ) {
        if (!validReward(playerId, activity, requestedGold, requestedMerit, dailyGoldCap, dailyMeritCap,
                idempotencyKey, reason)) {
            return CompletableFuture.completedFuture(new ActivityRewardResult(
                    ActivityRewardResult.Status.INVALID, null, zero(), 0));
        }
        return applyRewards(playerId, activity, List.of(new ActivityRewardRequest(
                requestedGold, requestedMerit, idempotencyKey, reason)), dailyGoldCap, dailyMeritCap, true);
    }

    @Override
    public CompletionStage<ActivityRewardResult> rewardBatch(
            UUID playerId,
            EconomyActivity activity,
            List<ActivityRewardRequest> rewards,
            BigDecimal dailyGoldCap,
            long dailyMeritCap
    ) {
        if (rewards == null || rewards.isEmpty() || rewards.size() > 128 || dailyGoldCap == null
                || rewards.stream().anyMatch(reward -> reward == null || !validReward(
                playerId, activity, reward.requestedGold(), reward.requestedMerit(), dailyGoldCap, dailyMeritCap,
                reward.idempotencyKey(), reward.reason()))) {
            return CompletableFuture.completedFuture(new ActivityRewardResult(
                    ActivityRewardResult.Status.INVALID, null, zero(), 0));
        }
        return applyRewards(playerId, activity, List.copyOf(rewards), dailyGoldCap, dailyMeritCap, false);
    }

    private CompletionStage<ActivityRewardResult> applyRewards(
            UUID playerId, EconomyActivity activity, List<ActivityRewardRequest> snapshot,
            BigDecimal dailyGoldCap, long dailyMeritCap, boolean requireExact
    ) {
        BigDecimal goldCap = money(dailyGoldCap);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            BigDecimal gold = lockAccount(connection, playerId);
            long merit = lockWallet(connection, playerId);
            LocalDate today = LocalDate.now();
            ActivityQuota quota = lockDaily(connection, playerId, activity, today);
            Map<String, PreviousReward> previous = previousRewards(connection, snapshot);
            HashSet<String> seen = new HashSet<>();
            ArrayList<AppliedReward> applied = new ArrayList<>(snapshot.size());
            BigDecimal runningGold = quota.goldEarned();
            long runningMerit = quota.meritEarned();
            BigDecimal totalRequestedGold = zero();
            long totalRequestedMerit = 0L;
            BigDecimal totalGrantedGold = zero();
            long totalGrantedMerit = 0L;
            BigDecimal duplicateGold = zero();
            long duplicateMerit = 0L;
            int duplicateCount = 0;

            if (requireExact) {
                ActivityRewardRequest reward = snapshot.getFirst();
                PreviousReward replay = previous.get(reward.idempotencyKey());
                if (replay != null) {
                    return new ActivityRewardResult(ActivityRewardResult.Status.DUPLICATE,
                            progress(connection, playerId, false), replay.gold(), replay.merit());
                }
                BigDecimal requestedGold = money(reward.requestedGold());
                if (requestedGold.compareTo(goldCap.subtract(runningGold).max(zero())) > 0
                        || reward.requestedMerit() > Math.max(0L, dailyMeritCap - runningMerit)) {
                    return new ActivityRewardResult(ActivityRewardResult.Status.DAILY_LIMIT,
                            progress(connection, playerId, false), zero(), 0L);
                }
                if (requestedGold.compareTo(MAX_GOLD.subtract(gold).max(zero())) > 0) {
                    return new ActivityRewardResult(ActivityRewardResult.Status.BALANCE_LIMIT,
                            progress(connection, playerId, false), zero(), 0L);
                }
                if (reward.requestedMerit() > Math.max(0L, MAX_MERIT - merit)) {
                    return new ActivityRewardResult(ActivityRewardResult.Status.MERIT_LIMIT,
                            progress(connection, playerId, false), zero(), 0L);
                }
            }

            boolean balanceLimited = false;
            boolean meritLimited = false;
            for (ActivityRewardRequest reward : snapshot) {
                PreviousReward replay = previous.get(reward.idempotencyKey());
                if (!seen.add(reward.idempotencyKey()) || replay != null) {
                    duplicateCount++;
                    if (replay != null) {
                        duplicateGold = duplicateGold.add(replay.gold());
                        duplicateMerit += replay.merit();
                    }
                    continue;
                }
                BigDecimal requestedGold = money(reward.requestedGold());
                BigDecimal dailyGrant = requestedGold.min(goldCap.subtract(runningGold).max(zero()));
                BigDecimal balanceRoom = MAX_GOLD.subtract(gold).subtract(totalGrantedGold).max(zero());
                BigDecimal grantedGold = dailyGrant.min(balanceRoom);
                if (grantedGold.compareTo(dailyGrant) < 0) balanceLimited = true;
                long dailyMeritGrant = Math.min(
                        reward.requestedMerit(), Math.max(0L, dailyMeritCap - runningMerit));
                long meritRoom = Math.max(0L, MAX_MERIT - merit - totalGrantedMerit);
                long grantedMerit = Math.min(dailyMeritGrant, meritRoom);
                if (grantedMerit < dailyMeritGrant) meritLimited = true;
                applied.add(new AppliedReward(reward, requestedGold, grantedGold, grantedMerit));
                runningGold = runningGold.add(grantedGold);
                runningMerit += grantedMerit;
                totalRequestedGold = totalRequestedGold.add(requestedGold);
                totalRequestedMerit += reward.requestedMerit();
                totalGrantedGold = totalGrantedGold.add(grantedGold);
                totalGrantedMerit += grantedMerit;
            }

            // A wallet-cap rejection is deliberately retryable. Unlike a
            // daily cap, it must not consume the event key: after the player
            // spends Gold, an exact commission or an operator recovery can
            // safely replay the original event.
            if (totalGrantedGold.signum() == 0 && totalGrantedMerit == 0 && balanceLimited) {
                return new ActivityRewardResult(ActivityRewardResult.Status.BALANCE_LIMIT,
                        progress(connection, playerId, false), zero(), 0L);
            }
            if (totalGrantedGold.signum() == 0 && totalGrantedMerit == 0 && meritLimited) {
                return new ActivityRewardResult(ActivityRewardResult.Status.MERIT_LIMIT,
                        progress(connection, playerId, false), zero(), 0L);
            }
            if (!applied.isEmpty()) {
                if (totalGrantedGold.signum() > 0) updateAccount(connection, playerId, gold.add(totalGrantedGold));
                if (totalGrantedMerit > 0) updateWallet(connection, playerId, merit + totalGrantedMerit);
                updateDaily(connection, playerId, activity, today, runningGold, runningMerit);
                insertRewardLedgers(connection, playerId, activity, applied);
            }

            ActivityRewardResult.Status status;
            BigDecimal reportedGold = totalGrantedGold;
            long reportedMerit = totalGrantedMerit;
            if (applied.isEmpty() && duplicateCount > 0) {
                status = ActivityRewardResult.Status.DUPLICATE;
                reportedGold = duplicateGold;
                reportedMerit = duplicateMerit;
            } else if (totalGrantedGold.signum() == 0 && totalGrantedMerit == 0) {
                status = ActivityRewardResult.Status.DAILY_LIMIT;
            } else if (totalGrantedGold.compareTo(totalRequestedGold) < 0
                    || totalGrantedMerit < totalRequestedMerit) {
                status = ActivityRewardResult.Status.PARTIAL;
            } else {
                status = ActivityRewardResult.Status.SUCCESS;
            }
            return new ActivityRewardResult(status, progress(connection, playerId, false), reportedGold, reportedMerit);
        }));
    }

    @Override
    public CompletionStage<EconomyPurchaseResult> purchase(
            UUID playerId,
            BigDecimal goldCost,
            long meritCost,
            String itemPayload,
            String idempotencyKey,
            String reason
    ) {
        if (!validPurchase(playerId, goldCost, meritCost, itemPayload, idempotencyKey, reason)) {
            return CompletableFuture.completedFuture(new EconomyPurchaseResult(
                    EconomyPurchaseResult.Status.INVALID, null, Optional.empty()));
        }
        BigDecimal normalizedGold = money(goldCost);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            BigDecimal gold = lockAccount(connection, playerId);
            long merit = lockWallet(connection, playerId);
            UUID previousDelivery = previousPurchase(connection, idempotencyKey);
            if (previousDelivery != null) {
                return new EconomyPurchaseResult(EconomyPurchaseResult.Status.DUPLICATE,
                        progress(connection, playerId, false), Optional.of(previousDelivery));
            }
            if (gold.compareTo(normalizedGold) < 0 || merit < meritCost) {
                return new EconomyPurchaseResult(EconomyPurchaseResult.Status.INSUFFICIENT_FUNDS,
                        progress(connection, playerId, false), Optional.empty());
            }

            UUID deliveryId = UUID.randomUUID();
            updateAccount(connection, playerId, gold.subtract(normalizedGold));
            updateWallet(connection, playerId, merit - meritCost);
            if (normalizedGold.signum() > 0) {
                insertGoldLedger(connection, idempotencyKey, playerId, null, normalizedGold, reason);
            }
            if (meritCost > 0) {
                insertMeritLedger(connection, idempotencyKey, playerId, meritCost, "DEBIT", reason);
            }
            try (PreparedStatement delivery = connection.prepareStatement("""
                    INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                    VALUES (?, ?, ?, ?, 'PENDING')
                    """)) {
                delivery.setString(1, deliveryId.toString());
                delivery.setString(2, playerId.toString());
                delivery.setString(3, itemPayload);
                delivery.setString(4, "economy-shop:" + reason);
                delivery.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_activity_purchases
                    (id, idempotency_key, player_uuid, gold_cost, merit_cost, reason, delivery_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, idempotencyKey);
                insert.setString(3, playerId.toString());
                insert.setBigDecimal(4, normalizedGold);
                insert.setLong(5, meritCost);
                insert.setString(6, reason);
                insert.setString(7, deliveryId.toString());
                insert.executeUpdate();
            }
            return new EconomyPurchaseResult(EconomyPurchaseResult.Status.SUCCESS,
                    progress(connection, playerId, false), Optional.of(deliveryId));
        }));
    }

    @Override
    public CompletionStage<DailyItemRewardResult> rewardDailyItem(
            UUID playerId,
            String rewardId,
            int requestedAmount,
            int dailyCap,
            int minimumUsed,
            String unitItemPayload,
            String idempotencyKey,
            String reason
    ) {
        if (playerId == null || !validIdentifier(rewardId, 64)
                || requestedAmount < 1 || requestedAmount > MAX_DAILY_ITEM_CAP
                || dailyCap < 1 || dailyCap > MAX_DAILY_ITEM_CAP
                || minimumUsed < 0 || minimumUsed > MAX_DAILY_ITEM_CAP
                || unitItemPayload == null || unitItemPayload.isBlank()
                || unitItemPayload.length() > MAX_DAILY_ITEM_PAYLOAD
                || !validText(idempotencyKey, reason)) {
            return CompletableFuture.completedFuture(DailyItemRewardResult.invalid());
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            LocalDate today = LocalDate.now();
            String requestHash = dailyItemRequestHash(
                    playerId, rewardId, requestedAmount, dailyCap,
                    unitItemPayload, reason);
            if (!reserveDailyItemReceipt(connection, playerId, rewardId, today,
                    requestedAmount, dailyCap, idempotencyKey, reason, requestHash)) {
                DailyItemRewardResult replay = previousDailyItemReward(
                        connection, playerId, rewardId, idempotencyKey, requestHash);
                if (replay == null) {
                    throw new IllegalStateException("Daily item reward receipt disappeared after conflict");
                }
                return replay;
            }

            int persisted = lockDailyItemUsage(connection, playerId, rewardId, today);
            int used = Math.max(persisted, minimumUsed);
            int granted = Math.min(requestedAmount, Math.max(0, dailyCap - used));
            int usedAfter = used + granted;
            updateDailyItemUsage(connection, playerId, rewardId, today, usedAfter);

            for (int index = 0; index < granted; index++) {
                UUID deliveryId = stableDeliveryId(idempotencyKey, index);
                try (PreparedStatement delivery = connection.prepareStatement("""
                        INSERT INTO rpg_market_deliveries
                        (id, player_uuid, item_payload, source, state)
                        VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING
                        """)) {
                    delivery.setString(1, deliveryId.toString());
                    delivery.setString(2, playerId.toString());
                    delivery.setString(3, unitItemPayload);
                    delivery.setString(4, "daily-item:" + rewardId);
                    if (delivery.executeUpdate() != 1
                            && !matchingDailyItemDelivery(
                            connection, deliveryId, playerId, unitItemPayload, rewardId)) {
                        throw new IllegalStateException("Daily item delivery ID collided with different content");
                    }
                }
            }
            completeDailyItemReceipt(
                    connection, idempotencyKey, requestHash, granted, usedAfter);
            DailyItemRewardResult.Status status = granted > 0
                    ? DailyItemRewardResult.Status.SUCCESS : DailyItemRewardResult.Status.DAILY_LIMIT;
            return new DailyItemRewardResult(
                    status, playerId, rewardId, today, granted, usedAfter, dailyCap);
        }));
    }

    private int lockDailyItemUsage(
            Connection connection, UUID playerId, String rewardId, LocalDate date
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_daily_item_rewards
                (player_uuid, reward_id, reward_date, used) VALUES (?, ?, ?, 0)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, rewardId);
            insert.setDate(3, Date.valueOf(date));
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT used FROM rpg_daily_item_rewards
                WHERE player_uuid = ? AND reward_id = ? AND reward_date = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, rewardId);
            query.setDate(3, Date.valueOf(date));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Daily item quota row was not created");
                return Math.max(0, result.getInt(1));
            }
        }
    }

    private void updateDailyItemUsage(
            Connection connection, UUID playerId, String rewardId, LocalDate date, int used
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_daily_item_rewards SET used = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND reward_id = ? AND reward_date = ?
                """)) {
            update.setInt(1, used);
            update.setString(2, playerId.toString());
            update.setString(3, rewardId);
            update.setDate(4, Date.valueOf(date));
            if (update.executeUpdate() != 1) throw new IllegalStateException("Daily item quota update failed");
        }
    }

    private DailyItemRewardResult previousDailyItemReward(
            Connection connection, UUID playerId, String rewardId, String key, String requestHash
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, reward_id, reward_date, granted, used_after, daily_cap, request_hash
                FROM rpg_daily_item_reward_receipts WHERE idempotency_key = ?
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                if (!playerId.toString().equals(result.getString("player_uuid"))
                        || !rewardId.equals(result.getString("reward_id"))
                        || !requestHash.equals(result.getString("request_hash"))) {
                    throw new IllegalStateException("Daily item reward idempotency key was reused");
                }
                return new DailyItemRewardResult(
                        DailyItemRewardResult.Status.DUPLICATE,
                        playerId,
                        rewardId,
                        result.getDate("reward_date").toLocalDate(),
                        result.getInt("granted"),
                        result.getInt("used_after"),
                        result.getInt("daily_cap"));
            }
        }
    }

    private boolean reserveDailyItemReceipt(
            Connection connection, UUID playerId, String rewardId, LocalDate date,
            int requested, int dailyCap, String key, String reason, String requestHash
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_daily_item_reward_receipts
                (idempotency_key, player_uuid, reward_id, reward_date, requested,
                 granted, used_after, daily_cap, request_hash, reason)
                VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, key);
            insert.setString(2, playerId.toString());
            insert.setString(3, rewardId);
            insert.setDate(4, Date.valueOf(date));
            insert.setInt(5, requested);
            insert.setInt(6, dailyCap);
            insert.setString(7, requestHash);
            insert.setString(8, reason);
            return insert.executeUpdate() == 1;
        }
    }

    private void completeDailyItemReceipt(
            Connection connection, String key, String requestHash, int granted, int usedAfter
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_daily_item_reward_receipts
                SET granted = ?, used_after = ?
                WHERE idempotency_key = ? AND request_hash = ?
                """)) {
            update.setInt(1, granted);
            update.setInt(2, usedAfter);
            update.setString(3, key);
            update.setString(4, requestHash);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Daily item reward receipt completion failed");
            }
        }
    }

    private boolean matchingDailyItemDelivery(
            Connection connection, UUID deliveryId, UUID playerId,
            String payload, String rewardId
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, item_payload, source FROM rpg_market_deliveries WHERE id = ?
                """)) {
            query.setString(1, deliveryId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        && playerId.toString().equals(result.getString("player_uuid"))
                        && payload.equals(result.getString("item_payload"))
                        && ("daily-item:" + rewardId).equals(result.getString("source"));
            }
        }
    }

    private static UUID stableDeliveryId(String idempotencyKey, int index) {
        return UUID.nameUUIDFromBytes(("daily-item:" + idempotencyKey + ":" + index)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String dailyItemRequestHash(
            UUID playerId, String rewardId, int requestedAmount, int dailyCap,
            String unitItemPayload, String reason
    ) {
        String canonical = playerId + "\n" + rewardId + "\n" + requestedAmount
                + "\n" + dailyCap + "\n" + reason + "\n" + unitItemPayload;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean validIdentifier(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && value.matches("[A-Za-z0-9._:-]+");
    }

    private EconomyProgress progress(Connection connection, UUID playerId, boolean lock) throws SQLException {
        BigDecimal gold = lock ? lockAccount(connection, playerId) : loadOrCreateAccount(connection, playerId);
        long merit = lock ? lockWallet(connection, playerId) : loadOrCreateWallet(connection, playerId);
        LocalDate today = LocalDate.now();
        Map<EconomyActivity, ActivityQuota> quotas = new EnumMap<>(EconomyActivity.class);
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT activity, gold_earned, merit_earned FROM rpg_activity_daily
                WHERE player_uuid = ? AND activity_date = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setDate(2, Date.valueOf(today));
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    quotas.put(EconomyActivity.valueOf(result.getString("activity")),
                            new ActivityQuota(money(result.getBigDecimal("gold_earned")), result.getLong("merit_earned")));
                }
            }
        }
        return new EconomyProgress(playerId, gold, merit, today, quotas);
    }

    private ActivityQuota lockDaily(Connection connection, UUID playerId, EconomyActivity activity, LocalDate date)
            throws SQLException {
        ActivityQuota quota = selectDaily(connection, playerId, activity, date, true);
        if (quota != null) return quota;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_activity_daily
                (player_uuid, activity, activity_date, gold_earned, merit_earned) VALUES (?, ?, ?, ?, 0)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, activity.name());
            insert.setDate(3, Date.valueOf(date));
            insert.setBigDecimal(4, zero());
            insert.executeUpdate();
        }
        return selectDaily(connection, playerId, activity, date, true);
    }

    private ActivityQuota selectDaily(
            Connection connection, UUID playerId, EconomyActivity activity, LocalDate date, boolean lock
    ) throws SQLException {
        String sql = "SELECT gold_earned, merit_earned FROM rpg_activity_daily "
                + "WHERE player_uuid = ? AND activity = ? AND activity_date = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            query.setString(2, activity.name());
            query.setDate(3, Date.valueOf(date));
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new ActivityQuota(money(result.getBigDecimal(1)), result.getLong(2)) : null;
            }
        }
    }

    private void updateDaily(
            Connection connection, UUID playerId, EconomyActivity activity, LocalDate date, BigDecimal gold, long merit
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_activity_daily SET gold_earned = ?, merit_earned = ?
                WHERE player_uuid = ? AND activity = ? AND activity_date = ?
                """)) {
            update.setBigDecimal(1, money(gold));
            update.setLong(2, merit);
            update.setString(3, playerId.toString());
            update.setString(4, activity.name());
            update.setDate(5, Date.valueOf(date));
            update.executeUpdate();
        }
    }

    private BigDecimal lockAccount(Connection connection, UUID playerId) throws SQLException {
        loadOrCreateAccount(connection, playerId);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing economy account after creation");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private BigDecimal loadOrCreateAccount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) return money(result.getBigDecimal(1));
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing economy account after upsert");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private long lockWallet(Connection connection, UUID playerId) throws SQLException {
        loadOrCreateWallet(connection, playerId);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing progression wallet after creation");
                return result.getLong(1);
            }
        }
    }

    private long loadOrCreateWallet(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) return result.getLong(1);
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES (?, 0) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing progression wallet after upsert");
                return result.getLong(1);
            }
        }
    }

    private void updateAccount(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
    }

    private void updateWallet(Connection connection, UUID playerId, long merit) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_progression_wallets SET merit = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setLong(1, merit);
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
    }

    private Map<String, PreviousReward> previousRewards(
            Connection connection, List<ActivityRewardRequest> rewards
    ) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(rewards.size(), "?"));
        Map<String, PreviousReward> previous = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT idempotency_key, granted_gold, granted_merit FROM rpg_activity_ledger "
                        + "WHERE idempotency_key IN (" + placeholders + ")")) {
            for (int index = 0; index < rewards.size(); index++) {
                query.setString(index + 1, rewards.get(index).idempotencyKey());
            }
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    previous.put(result.getString(1), new PreviousReward(
                            money(result.getBigDecimal(2)), result.getLong(3)));
                }
            }
        }
        return previous;
    }

    private void insertRewardLedgers(
            Connection connection, UUID playerId, EconomyActivity activity, List<AppliedReward> rewards
    ) throws SQLException {
        try (PreparedStatement gold = connection.prepareStatement("""
                    INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                    VALUES (?, ?, NULL, ?, ?, ?, ?)
                    """);
             PreparedStatement merit = connection.prepareStatement("""
                    INSERT INTO rpg_merit_ledger (id, idempotency_key, player_uuid, amount, direction, reason)
                    VALUES (?, ?, ?, ?, 'CREDIT', ?)
                    """);
             PreparedStatement activityLedger = connection.prepareStatement("""
                    INSERT INTO rpg_activity_ledger
                    (id, idempotency_key, player_uuid, activity, requested_gold, granted_gold,
                     requested_merit, granted_merit, reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
            for (AppliedReward applied : rewards) {
                ActivityRewardRequest reward = applied.reward();
                if (applied.grantedGold().signum() > 0) {
                    gold.setString(1, UUID.randomUUID().toString());
                    gold.setString(2, reward.idempotencyKey());
                    gold.setString(3, playerId.toString());
                    gold.setBigDecimal(4, applied.grantedGold());
                    gold.setBigDecimal(5, zero());
                    gold.setString(6, reward.reason());
                    gold.addBatch();
                }
                if (applied.grantedMerit() > 0) {
                    merit.setString(1, UUID.randomUUID().toString());
                    merit.setString(2, reward.idempotencyKey());
                    merit.setString(3, playerId.toString());
                    merit.setLong(4, applied.grantedMerit());
                    merit.setString(5, reward.reason());
                    merit.addBatch();
                }
                activityLedger.setString(1, UUID.randomUUID().toString());
                activityLedger.setString(2, reward.idempotencyKey());
                activityLedger.setString(3, playerId.toString());
                activityLedger.setString(4, activity.name());
                activityLedger.setBigDecimal(5, applied.requestedGold());
                activityLedger.setBigDecimal(6, applied.grantedGold());
                activityLedger.setLong(7, reward.requestedMerit());
                activityLedger.setLong(8, applied.grantedMerit());
                activityLedger.setString(9, reward.reason());
                activityLedger.addBatch();
            }
            gold.executeBatch();
            merit.executeBatch();
            activityLedger.executeBatch();
        }
    }

    private UUID previousPurchase(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT delivery_id FROM rpg_activity_purchases WHERE idempotency_key = ?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString(1)) : null;
            }
        }
    }

    private void insertGoldLedger(
            Connection connection, String key, UUID sender, UUID receiver, BigDecimal amount, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, sender == null ? null : sender.toString());
            insert.setString(4, receiver == null ? null : receiver.toString());
            insert.setBigDecimal(5, money(amount));
            insert.setBigDecimal(6, zero());
            insert.setString(7, reason);
            insert.executeUpdate();
        }
    }

    private void insertMeritLedger(
            Connection connection, String key, UUID playerId, long amount, String direction, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_merit_ledger (id, idempotency_key, player_uuid, amount, direction, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, playerId.toString());
            insert.setLong(4, amount);
            insert.setString(5, direction);
            insert.setString(6, reason);
            insert.executeUpdate();
        }
    }

    private void insertActivityLedger(
            Connection connection, String key, UUID playerId, EconomyActivity activity,
            BigDecimal requestedGold, BigDecimal grantedGold, long requestedMerit, long grantedMerit, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_activity_ledger
                (id, idempotency_key, player_uuid, activity, requested_gold, granted_gold,
                 requested_merit, granted_merit, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, playerId.toString());
            insert.setString(4, activity.name());
            insert.setBigDecimal(5, requestedGold);
            insert.setBigDecimal(6, grantedGold);
            insert.setLong(7, requestedMerit);
            insert.setLong(8, grantedMerit);
            insert.setString(9, reason);
            insert.executeUpdate();
        }
    }

    private boolean validReward(
            UUID playerId, EconomyActivity activity, BigDecimal gold, long merit,
            BigDecimal goldCap, long meritCap, String key, String reason
    ) {
        if (playerId == null || activity == null || gold == null || goldCap == null) return false;
        BigDecimal normalizedGold = money(gold);
        BigDecimal normalizedCap = money(goldCap);
        return gold.signum() >= 0 && normalizedGold.signum() >= 0 && normalizedGold.compareTo(MAX_GOLD) <= 0
                && merit >= 0 && merit <= MAX_MERIT
                && goldCap.signum() >= 0 && normalizedCap.signum() >= 0 && normalizedCap.compareTo(MAX_GOLD) <= 0
                && meritCap >= 0 && meritCap <= MAX_MERIT
                && (normalizedGold.signum() > 0 || merit > 0) && validText(key, reason);
    }

    private boolean validPurchase(
            UUID playerId, BigDecimal gold, long merit, String payload, String key, String reason
    ) {
        if (playerId == null || gold == null) return false;
        BigDecimal normalizedGold = money(gold);
        return gold.signum() >= 0 && normalizedGold.signum() >= 0 && normalizedGold.compareTo(MAX_GOLD) <= 0
                && merit >= 0 && merit <= MAX_MERIT && (normalizedGold.signum() > 0 || merit > 0)
                && payload != null && !payload.isBlank() && payload.length() <= 2_000_000
                && validText(key, reason);
    }

    private boolean validText(String key, String reason) {
        return key != null && KEY.matcher(key).matches() && reason != null && !reason.isBlank() && reason.length() <= 128;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
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
                throw new IllegalStateException("Activity economy transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open activity economy transaction", exception);
        }
    }

    private record PreviousReward(BigDecimal gold, long merit) {
    }

    private record AppliedReward(
            ActivityRewardRequest reward, BigDecimal requestedGold, BigDecimal grantedGold, long grantedMerit
    ) {
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
