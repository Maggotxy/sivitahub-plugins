package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SupportCreditResult;
import cn.originalrpg.api.service.SupportCreditService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 宪法 C-012 的物理载体：{@code support_credit} 独立账本（余额表 + 幂等流水表）。
 *
 * <p>资金动作三件套在这里是硬编码次序，不是约定：</p>
 * <ol>
 *   <li><b>幂等键</b>：{@code rpg_support_credit_ledger.idempotency_key} 的唯一约束；
 *       插不进就说明本次请求已经入账，直接返回 DUPLICATE 且不再动余额。</li>
 *   <li><b>审计流水</b>：每一次余额变动都先有一行流水；没有流水行的余额变动不可能发生。</li>
 *   <li><b>事务</b>：流水行与余额行在同一 JDBC 事务里 commit/rollback，
 *       失败时两者一起消失，因此不存在「扣了钱没有流水」或「有流水没扣钱」。</li>
 * </ol>
 *
 * <p>隔离的口径是「不碰别的表」：本类只有 {@code rpg_support_credit_accounts} 与
 * {@code rpg_support_credit_ledger} 两张表的 SQL，任何一句 SQL 都不出现
 * {@code rpg_accounts} / {@code rpg_merit_ledger} / {@code rpg_credit_ledger}，
 * 因此「充值影响战力经济」在这条代码路径上没有落点。</p>
 */
public final class JdbcSupportCreditService implements SupportCreditService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    private static final Pattern REASON = Pattern.compile("[\\p{L}\\p{N}._:-]{1,128}");
    /** 单次入账/消费上限：挡住把 long 溢出当余额上限的请求。 */
    private static final long MAX_AMOUNT = 1_000_000_000L;
    /** 与 {@code rpg_support_credit_accounts.balance} 列语义一致的余额上限。 */
    private static final long MAX_BALANCE = 1_000_000_000_000L;

    private final DataSource dataSource;
    private final Executor executor;

    public JdbcSupportCreditService(DataSource dataSource, Executor executor) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public CompletionStage<Long> balance(UUID playerId) {
        if (playerId == null) return java.util.concurrent.CompletableFuture.completedFuture(0L);
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> readBalance(connection, playerId)));
    }

    @Override
    public CompletionStage<SupportCreditResult> grant(
            UUID playerId, long amount, String idempotencyKey, String reason
    ) {
        if (!valid(playerId, amount, idempotencyKey, reason)) return invalid();
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> apply(
                connection, playerId, amount, "GRANT", idempotencyKey, reason)));
    }

    @Override
    public CompletionStage<SupportCreditResult> spend(
            UUID playerId, long amount, SupportCreditTarget target, String idempotencyKey, String reason
    ) {
        // C-012 禁止清单在开事务之前就拦下：非法通道不得触碰账本，连一行流水都不该有。
        if (target == null || !target.allowedByC012()) return forbidden();
        if (!valid(playerId, amount, idempotencyKey, reason)) return invalid();
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> apply(
                connection, playerId, amount, "SPEND", idempotencyKey, reason)));
    }

    private SupportCreditResult apply(
            Connection connection, UUID playerId, long amount, String direction,
            String idempotencyKey, String reason
    ) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        // 幂等优先于余额判定：重放必须报 DUPLICATE，而不是报余额不足把请求误判成失败。
        // 返回的是「原请求玩家」的当前余额，且只在幂等键本来就属于该玩家时才算重放；
        // 幂等键属于别人时是键复用错误，必须拒绝而不是泄露他人余额。
        Replay replay = replay(connection, idempotencyKey);
        if (replay != null) {
            if (!replay.playerId().equals(playerId)) {
                return new SupportCreditResult(SupportCreditResult.Status.INVALID, 0L, 0L);
            }
            return new SupportCreditResult(SupportCreditResult.Status.DUPLICATE, replay.balance(), 0L);
        }
        loadOrCreateAccount(connection, playerId);
        long current = lockBalance(connection, playerId);
        if (direction.equals("SPEND")) {
            if (current < amount) {
                return new SupportCreditResult(SupportCreditResult.Status.INSUFFICIENT_FUNDS, current, 0L);
            }
        } else if (current > MAX_BALANCE - amount) {
            return new SupportCreditResult(SupportCreditResult.Status.BALANCE_LIMIT, current, 0L);
        }
        long next = direction.equals("SPEND") ? current - amount : current + amount;
        // 先落流水，再动余额：唯一约束冲突意味着并发重放，此时整体回滚由调用方连接处理。
        insertLedger(connection, playerId, direction, amount, idempotencyKey, reason);
        updateBalance(connection, playerId, next);
        return new SupportCreditResult(SupportCreditResult.Status.SUCCESS, next, amount);
    }

    /** 幂等键命中时返回「原请求玩家 + 该玩家的当前余额」；未命中返回 null。 */
    private Replay replay(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT player_uuid FROM rpg_support_credit_ledger WHERE idempotency_key = ?")) {
            query.setString(1, idempotencyKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                UUID owner = UUID.fromString(result.getString("player_uuid"));
                return new Replay(owner, readBalance(connection, owner));
            }
        }
    }

    /** 幂等重放的归属与余额快照。 */
    private record Replay(UUID playerId, long balance) {
    }

    private long readBalance(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_support_credit_accounts WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Math.max(0L, result.getLong(1)) : 0L;
            }
        }
    }

    private void loadOrCreateAccount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_support_credit_accounts (player_uuid, balance) VALUES (?, 0)
                ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.executeUpdate();
        }
    }

    private long lockBalance(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT balance FROM rpg_support_credit_accounts WHERE player_uuid = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new IllegalStateException("Support credit account row disappeared");
                return Math.max(0L, result.getLong(1));
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, long balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_support_credit_accounts
                SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?
                """)) {
            update.setLong(1, balance);
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Support credit balance update was lost");
        }
    }

    private void insertLedger(
            Connection connection, UUID playerId, String direction, long amount,
            String idempotencyKey, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_support_credit_ledger
                (id, idempotency_key, player_uuid, amount, direction, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, idempotencyKey);
            insert.setString(3, playerId.toString());
            insert.setLong(4, amount);
            insert.setString(5, direction);
            insert.setString(6, reason);
            insert.executeUpdate();
        }
    }

    private static boolean valid(UUID playerId, long amount, String key, String reason) {
        return playerId != null && amount > 0 && amount <= MAX_AMOUNT
                && key != null && KEY.matcher(key).matches()
                && reason != null && REASON.matcher(reason).matches();
    }

    private static CompletionStage<SupportCreditResult> invalid() {
        return java.util.concurrent.CompletableFuture.completedFuture(
                new SupportCreditResult(SupportCreditResult.Status.INVALID, 0L, 0L));
    }

    private static CompletionStage<SupportCreditResult> forbidden() {
        return java.util.concurrent.CompletableFuture.completedFuture(
                new SupportCreditResult(SupportCreditResult.Status.CROSS_CURRENCY_FORBIDDEN, 0L, 0L));
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
                throw new IllegalStateException("Support credit transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open support credit transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
