package cn.originalrpg.core.persistence;

import cn.originalrpg.api.service.SeasonMeritWalletService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 赛季层功勋钱包。C-010 要求永久层（{@code rpg_progression_wallets.merit}）与赛季层物理隔离，
 * 但两者都是同一份功勋的不同展示维度，因此服务只做「搬家」，不做「发钱」：
 *
 * <ul>
 *   <li>{@link #deposit} 从永久层扣，向赛季层加，净供给恒为 0；</li>
 *   <li>{@link #withdraw} 从赛季层扣，向永久层加，同上；</li>
 *   <li>两层都在同一个事务里变更，并各自写 {@code rpg_merit_ledger} 审计流水，
 *       幂等键用调用方的 {@code idempotencyKey}，重放返回既有余额而不是再搬一次。</li>
 * </ul>
 *
 * <p>钱包行只会被 {@link JdbcSeasonSettlementService} 在换季时归零；
 * 换季之后旧赛季的赛季层余额保留在账本里可查，不做任何自动折算。</p>
 */
public final class JdbcSeasonMeritWalletService implements SeasonMeritWalletService {
    private static final Pattern SEASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");
    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9._:-]{1,96}");
    private static final int REASON_MAX = 128;

    private final DataSource dataSource;
    private final Executor executor;
    private final String seasonId;

    public JdbcSeasonMeritWalletService(DataSource dataSource, Executor executor, String seasonId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
        String normalized = seasonId == null ? "" : seasonId.strip().toLowerCase(java.util.Locale.ROOT);
        if (!SEASON_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid active season id: " + seasonId);
        }
        this.seasonId = normalized;
    }

    public String seasonId() {
        return seasonId;
    }

    @Override
    public CompletionStage<Long> seasonMerit(UUID playerId) {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        return AsyncJdbc.supply(executor, () -> inTransaction(
                connection -> readSeasonMerit(connection, playerId)));
    }

    @Override
    public CompletionStage<Long> deposit(UUID playerId, long amount, String idempotencyKey, String reason) {
        if (playerId == null || amount <= 0 || !KEY.matcher(safe(idempotencyKey)).matches()
                || safe(reason).length() < 1 || safe(reason).length() > REASON_MAX) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid season Merit deposit"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(
                connection -> move(connection, playerId, amount, idempotencyKey, reason, true)));
    }

    @Override
    public CompletionStage<Long> withdraw(UUID playerId, String idempotencyKey, String reason) {
        if (playerId == null || !KEY.matcher(safe(idempotencyKey)).matches()
                || safe(reason).length() < 1 || safe(reason).length() > REASON_MAX) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid season Merit withdrawal"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            long available = readSeasonMerit(connection, playerId);
            if (available <= 0) return 0L;
            return move(connection, playerId, available, idempotencyKey, reason, false);
        }));
    }

    private long move(
            Connection connection, UUID playerId, long amount, String key, String reason, boolean deposit
    ) throws SQLException {
        long existing = replayedAmount(connection, key);
        if (existing >= 0) return readSeasonMerit(connection, playerId);
        provision(connection, playerId);
        String player = playerId.toString();
        if (deposit) {
            debitPermanent(connection, player, amount, key, reason);
            creditSeason(connection, player, amount);
        } else {
            debitSeason(connection, player, amount);
            creditPermanent(connection, player, amount, key, reason);
        }
        return readSeasonMerit(connection, playerId);
    }

    /** 幂等键命中即返回重放，不再搬第二遍。 */
    private long replayedAmount(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT amount FROM rpg_merit_ledger WHERE idempotency_key = ?
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : -1L;
            }
        }
    }

    private void provision(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_progression_wallets (player_uuid, merit, season_id) VALUES (?, 0, ?)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, seasonId);
            insert.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_merit_wallets (player_uuid, season_id, season_merit)
                VALUES (?, ?, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, seasonId);
            insert.executeUpdate();
        }
    }

    private long readSeasonMerit(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT season_merit FROM rpg_season_merit_wallets WHERE player_uuid = ?
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Math.max(0L, result.getLong(1)) : 0L;
            }
        }
    }

    private void debitPermanent(Connection connection, String player, long amount, String key, String reason)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_progression_wallets SET merit = merit - ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND merit >= ?
                """)) {
            update.setLong(1, amount);
            update.setString(2, player);
            update.setLong(3, amount);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Insufficient permanent Merit to move into the season wallet");
            }
        }
        ledger(connection, key, player, amount, "DEBIT", reason);
    }

    private void creditPermanent(Connection connection, String player, long amount, String key, String reason)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_progression_wallets SET merit = merit + ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ?
                """)) {
            update.setLong(1, amount);
            update.setString(2, player);
            if (update.executeUpdate() != 1) throw new IllegalStateException("Season Merit wallet is missing");
        }
        ledger(connection, key, player, amount, "CREDIT", reason);
    }

    private void creditSeason(Connection connection, String player, long amount) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_merit_wallets
                SET season_merit = season_merit + ?, season_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ?
                """)) {
            update.setLong(1, amount);
            update.setString(2, seasonId);
            update.setString(3, player);
            if (update.executeUpdate() != 1) throw new IllegalStateException("Season Merit wallet is missing");
        }
    }

    private void debitSeason(Connection connection, String player, long amount) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_merit_wallets SET season_merit = season_merit - ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND season_merit >= ?
                """)) {
            update.setLong(1, amount);
            update.setString(2, player);
            update.setLong(3, amount);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Season Merit changed during withdrawal");
            }
        }
    }

    private void ledger(Connection connection, String key, String player, long amount, String direction, String reason)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_merit_ledger (id, idempotency_key, player_uuid, amount, direction, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, player);
            insert.setLong(4, amount);
            insert.setString(5, direction);
            insert.setString(6, reason);
            insert.executeUpdate();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                if (connection.getAutoCommit()) connection.setAutoCommit(false);
                T value = operation.apply(connection);
                connection.commit();
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Season Merit wallet transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open season Merit wallet transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
