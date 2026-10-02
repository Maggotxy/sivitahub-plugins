package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommissionEvidenceClaimResult;
import cn.originalrpg.api.model.CommissionEvidenceGrantResult;
import cn.originalrpg.api.model.CommissionEvidenceSnapshot;
import cn.originalrpg.api.service.CommissionEvidenceService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

public final class JdbcCommissionEvidenceService implements CommissionEvidenceService {
    private static final Pattern ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final int HARD_CAP = 32;

    private final DataSource dataSource;
    private final Executor executor;

    public JdbcCommissionEvidenceService(DataSource dataSource, Executor executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletionStage<CommissionEvidenceSnapshot> snapshot(UUID playerId, LocalDate date, int dailyCap) {
        if (!validBase(playerId, date, dailyCap)) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Invalid commission evidence snapshot request"));
        return AsyncJdbc.supply(executor, () -> inTransaction(connection ->
                snapshot(connection, playerId, date, dailyCap)));
    }

    @Override
    public CompletionStage<CommissionEvidenceGrantResult> grant(
            UUID playerId, LocalDate date, String source, int requested, int sourceDailyCap, int dailyCap
    ) {
        if (!validBase(playerId, date, dailyCap) || source == null || !ID.matcher(source).matches()
                || requested < 1 || requested > HARD_CAP || sourceDailyCap < 1 || sourceDailyCap > HARD_CAP) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid commission evidence grant"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            int earned = lockDaily(connection, playerId, date);
            int sourceEarned = sourceEarned(connection, playerId, date, source);
            int granted = Math.max(0, Math.min(requested,
                    Math.min(sourceDailyCap - sourceEarned, dailyCap - earned)));
            if (granted > 0) {
                updateDaily(connection, playerId, date, earned + granted);
                upsertSource(connection, playerId, date, source, sourceEarned + granted);
            }
            return new CommissionEvidenceGrantResult(granted,
                    snapshot(connection, playerId, date, dailyCap));
        }));
    }

    @Override
    public CompletionStage<CommissionEvidenceClaimResult> claim(
            UUID playerId, LocalDate date, UUID receiptId, String commissionId, int evidenceCost,
            int totalDailyLimit, int offerDailyLimit, int dailyCap
    ) {
        if (!validBase(playerId, date, dailyCap) || receiptId == null || commissionId == null
                || !ID.matcher(commissionId).matches() || evidenceCost < 1 || evidenceCost > HARD_CAP
                || totalDailyLimit < 1 || totalDailyLimit > HARD_CAP
                || offerDailyLimit < 1 || offerDailyLimit > HARD_CAP) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid commission evidence claim"));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            int earned = lockDaily(connection, playerId, date);
            ExistingClaim existing = existingClaim(connection, receiptId);
            if (existing != null) return claimReplay(connection, playerId, date, commissionId,
                    evidenceCost, dailyCap, existing);

            Usage usage = usage(connection, playerId, date);
            CommissionEvidenceClaimResult.Status status;
            if (usage.submissions() >= totalDailyLimit) {
                status = CommissionEvidenceClaimResult.Status.TOTAL_LIMIT;
            } else if (usage.offerClaims().getOrDefault(commissionId, 0) >= offerDailyLimit) {
                status = CommissionEvidenceClaimResult.Status.OFFER_LIMIT;
            } else if (earned - usage.used() < evidenceCost) {
                status = CommissionEvidenceClaimResult.Status.INSUFFICIENT_EVIDENCE;
            } else {
                int inserted = insertClaim(connection, playerId, date, receiptId, commissionId, evidenceCost);
                if (inserted == 0) {
                    return claimReplay(connection, playerId, date, commissionId, evidenceCost,
                            dailyCap, existingClaim(connection, receiptId));
                }
                status = CommissionEvidenceClaimResult.Status.SUCCESS;
            }
            return new CommissionEvidenceClaimResult(status, snapshot(connection, playerId, date, dailyCap));
        }));
    }

    @Override
    public CompletionStage<Boolean> release(UUID playerId, LocalDate date, UUID receiptId) {
        if (playerId == null || date == null || receiptId == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Invalid commission evidence release"));
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            lockDaily(connection, playerId, date);
            ExistingClaim claim = existingClaim(connection, receiptId);
            if (claim == null || !claim.playerId().equals(playerId) || !claim.date().equals(date)) return false;
            if (claim.state().equals("RELEASED")) return true;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_commission_claims SET state = 'RELEASED', updated_at = CURRENT_TIMESTAMP
                    WHERE receipt_id = ? AND state = 'ACTIVE'
                    """)) {
                update.setString(1, receiptId.toString());
                return update.executeUpdate() == 1;
            }
        }));
    }

    private CommissionEvidenceClaimResult claimReplay(
            Connection connection, UUID playerId, LocalDate date, String commissionId,
            int evidenceCost, int dailyCap, ExistingClaim existing
    ) throws SQLException {
        boolean matches = existing != null && existing.playerId().equals(playerId)
                && existing.date().equals(date) && existing.commissionId().equals(commissionId)
                && existing.evidenceCost() == evidenceCost && existing.state().equals("ACTIVE");
        return new CommissionEvidenceClaimResult(matches
                ? CommissionEvidenceClaimResult.Status.SUCCESS
                : CommissionEvidenceClaimResult.Status.INVALID,
                snapshot(connection, playerId, date, dailyCap));
    }

    private int lockDaily(Connection connection, UUID playerId, LocalDate date) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_commission_evidence_daily (player_uuid, evidence_date, earned)
                VALUES (?, ?, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setDate(2, Date.valueOf(date));
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT earned FROM rpg_commission_evidence_daily
                WHERE player_uuid = ? AND evidence_date = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            query.setDate(2, Date.valueOf(date));
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Commission evidence row disappeared");
                return result.getInt(1);
            }
        }
    }

    private void updateDaily(Connection connection, UUID playerId, LocalDate date, int earned) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_commission_evidence_daily SET earned = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND evidence_date = ?
                """)) {
            update.setInt(1, earned);
            update.setString(2, playerId.toString());
            update.setDate(3, Date.valueOf(date));
            update.executeUpdate();
        }
    }

    private int sourceEarned(Connection connection, UUID playerId, LocalDate date, String source) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT earned FROM rpg_commission_evidence_sources
                WHERE player_uuid = ? AND evidence_date = ? AND source = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setDate(2, Date.valueOf(date));
            query.setString(3, source);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private void upsertSource(
            Connection connection, UUID playerId, LocalDate date, String source, int earned
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_commission_evidence_sources (player_uuid, evidence_date, source, earned)
                VALUES (?, ?, ?, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setDate(2, Date.valueOf(date));
            insert.setString(3, source);
            insert.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_commission_evidence_sources SET earned = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND evidence_date = ? AND source = ?
                """)) {
            update.setInt(1, earned);
            update.setString(2, playerId.toString());
            update.setDate(3, Date.valueOf(date));
            update.setString(4, source);
            update.executeUpdate();
        }
    }

    private int insertClaim(
            Connection connection, UUID playerId, LocalDate date, UUID receiptId,
            String commissionId, int evidenceCost
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_commission_claims
                    (receipt_id, player_uuid, evidence_date, commission_id, evidence_cost, state)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE') ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, receiptId.toString());
            insert.setString(2, playerId.toString());
            insert.setDate(3, Date.valueOf(date));
            insert.setString(4, commissionId);
            insert.setInt(5, evidenceCost);
            return insert.executeUpdate();
        }
    }

    private ExistingClaim existingClaim(Connection connection, UUID receiptId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, evidence_date, commission_id, evidence_cost, state
                FROM rpg_commission_claims WHERE receipt_id = ?
                """)) {
            query.setString(1, receiptId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new ExistingClaim(UUID.fromString(result.getString(1)), result.getDate(2).toLocalDate(),
                        result.getString(3), result.getInt(4), result.getString(5));
            }
        }
    }

    private CommissionEvidenceSnapshot snapshot(
            Connection connection, UUID playerId, LocalDate date, int dailyCap
    ) throws SQLException {
        int earned = 0;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT earned FROM rpg_commission_evidence_daily
                WHERE player_uuid = ? AND evidence_date = ?
                """)) {
            query.setString(1, playerId.toString());
            query.setDate(2, Date.valueOf(date));
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) earned = result.getInt(1);
            }
        }
        Usage usage = usage(connection, playerId, date);
        return new CommissionEvidenceSnapshot(earned, Math.min(earned, usage.used()),
                usage.submissions(), dailyCap, usage.offerClaims());
    }

    private Usage usage(Connection connection, UUID playerId, LocalDate date) throws SQLException {
        int used = 0;
        int submissions = 0;
        Map<String, Integer> offerClaims = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT commission_id, evidence_cost FROM rpg_commission_claims
                WHERE player_uuid = ? AND evidence_date = ? AND state = 'ACTIVE'
                """)) {
            query.setString(1, playerId.toString());
            query.setDate(2, Date.valueOf(date));
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String commissionId = result.getString(1);
                    used += result.getInt(2);
                    submissions++;
                    offerClaims.merge(commissionId, 1, Integer::sum);
                }
            }
        }
        return new Usage(used, submissions, Map.copyOf(offerClaims));
    }

    private static boolean validBase(UUID playerId, LocalDate date, int dailyCap) {
        return playerId != null && date != null && dailyCap >= 1 && dailyCap <= HARD_CAP;
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Commission evidence transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open commission evidence transaction", exception);
        }
    }

    private record Usage(int used, int submissions, Map<String, Integer> offerClaims) {}

    private record ExistingClaim(
            UUID playerId, LocalDate date, String commissionId, int evidenceCost, String state
    ) {}

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
