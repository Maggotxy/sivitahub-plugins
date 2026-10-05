package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.Archetype;
import cn.originalrpg.api.model.CoreAttribute;
import cn.originalrpg.api.model.EconomyActivity;
import cn.originalrpg.api.model.IncidentActivityCompensation;
import cn.originalrpg.api.model.IncidentConditionalActivityAudit;
import cn.originalrpg.api.model.IncidentConditionalActivityCompensation;
import cn.originalrpg.api.model.IncidentCompensationAudit;
import cn.originalrpg.api.model.IncidentCompensationRequest;
import cn.originalrpg.api.model.IncidentCompensationResult;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.AdminIncidentCompensationService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** Atomic, immutable and replay-safe recovery for a documented incident. */
public final class JdbcAdminIncidentCompensationService implements AdminIncidentCompensationService {
    private static final Pattern INCIDENT_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{2,47}");
    private static final Pattern EVIDENCE_ID = Pattern.compile("[a-z0-9][a-z0-9_./:-]{2,127}");
    private static final BigDecimal MAX_INCIDENT_GOLD = new BigDecimal("1000000000.00");
    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000000.00");
    private static final long MAX_EXPERIENCE = 10_000_000L;
    private static final long MAX_INCIDENT_MERIT = 1_000_000L;
    private static final long MAX_MERIT_BALANCE = 1_000_000_000L;

    private final DataSource dataSource;
    private final Executor executor;
    private final JdbcProfileService profiles;
    private final BigDecimal startingBalance;

    public JdbcAdminIncidentCompensationService(
            DataSource dataSource, Executor executor, JdbcProfileService profiles, BigDecimal startingBalance
    ) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.startingBalance = money(java.util.Objects.requireNonNull(startingBalance, "startingBalance"));
    }

    @Override
    public CompletionStage<IncidentCompensationResult> apply(IncidentCompensationRequest request) {
        if (!valid(request)) {
            return CompletableFuture.completedFuture(new IncidentCompensationResult(
                    IncidentCompensationResult.Status.INVALID, Optional.empty()));
        }
        NormalizedRequest normalized = normalize(request);
        return AsyncJdbc.supply(executor, () -> {
                    long[] version = new long[1];
                    try {
                        IncidentCompensationResult result =
                                inTransaction(connection -> apply(connection, normalized), version);
                        return new Committed<>(result, version[0]);
                    } catch (WalletLimitRejection rejection) {
                        // lockAccount may have provisioned a first account row. The
                        // exception makes inTransaction roll that row back together
                        // with every other tentative write, leaving this incident key
                        // retryable after the player makes room in their wallet.
                        return new Committed<>(
                                new IncidentCompensationResult(rejection.status(), Optional.empty()),
                                version[0]);
                    }
                })
                .thenApply(committed -> {
                    // A replay receipt may be hours old. Never put its historic
                    // after-profile over newer progression in the live cache —— 用提交
                    // 版本号做判据（见 JdbcProfileService.publish），晚到的旧快照不会回滚新快照。
                    IncidentCompensationResult result = committed.value();
                    if (result.status() == IncidentCompensationResult.Status.APPLIED) {
                        result.audit().ifPresent(audit ->
                                profiles.cacheCommitted(audit.afterProfile(), committed.version()));
                    }
                    return result;
                });
    }

    @Override
    public CompletionStage<IncidentCompensationResult> audit(String incidentKey) {
        if (incidentKey == null || !INCIDENT_KEY.matcher(incidentKey).matches()) {
            return CompletableFuture.completedFuture(new IncidentCompensationResult(
                    IncidentCompensationResult.Status.INVALID, Optional.empty()));
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> {
            IncidentCompensationAudit audit = loadAudit(connection, incidentKey);
            return new IncidentCompensationResult(audit == null
                    ? IncidentCompensationResult.Status.NOT_FOUND
                    : IncidentCompensationResult.Status.DUPLICATE, Optional.ofNullable(audit));
        }));
    }

    private IncidentCompensationResult apply(Connection connection, NormalizedRequest request) throws SQLException {
        IncidentCompensationAudit existing = loadAudit(connection, request.request().incidentKey());
        if (existing != null) return replayResult(existing, request.hash());
        if (!profileExists(connection, request.request().playerId())) {
            return new IncidentCompensationResult(
                    IncidentCompensationResult.Status.NOT_FOUND, Optional.empty());
        }

        IncidentCompensationRequest value = request.request();
        // The profile row serializes incident requests for one player without
        // mutating progression. Recheck the immutable receipt after acquiring
        // it so a concurrent exact replay is reported as DUPLICATE, even when
        // the first request filled the player's Gold balance to its limit.
        PlayerProfile before = profiles.loadForUpdate(connection, value.playerId());
        existing = loadAudit(connection, value.incidentKey());
        if (existing != null) return replayResult(existing, request.hash());

        // Economy mutations take the account and Merit locks before touching
        // activity ledgers. Match that global order to avoid a wallet/ledger
        // deadlock with a concurrent original reward, and make its committed
        // entries visible before deciding which conditional lines need repair.
        BigDecimal balance = lockAccount(connection, value.playerId());
        long merit = lockWallet(connection, value.playerId());
        List<ConditionalDecision> conditional = resolveConditionalActivities(connection, value);
        BigDecimal appliedConditionalGold = conditional.stream()
                .filter(ConditionalDecision::applied)
                .map(decision -> decision.reward().gold())
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        long appliedConditionalMerit = conditional.stream()
                .filter(ConditionalDecision::applied)
                .mapToLong(decision -> decision.reward().merit()).sum();
        BigDecimal appliedGold = request.unconditionalGold().add(appliedConditionalGold);
        long appliedMerit = Math.addExact(request.unconditionalMerit(), appliedConditionalMerit);

        // Reject before claiming the incident or changing progression, quota,
        // wallets or ledgers. Throwing is deliberate: it also rolls back a
        // first-time account row created by lockAccount.
        if (balance.add(appliedGold).compareTo(MAX_BALANCE) > 0) {
            throw WalletLimitRejection.GOLD;
        }
        if (appliedMerit > 0 && (merit >= MAX_MERIT_BALANCE
                || appliedMerit > MAX_MERIT_BALANCE - merit)) {
            throw WalletLimitRejection.MERIT;
        }
        if (!claimIncident(connection, request)) {
            existing = loadAudit(connection, value.incidentKey());
            if (existing == null) throw new IllegalStateException("Incident claim disappeared");
            return replayResult(existing, request.hash());
        }

        // G-107 下半：结算的技能点里程碑以「事件 id + 来源」为幂等依据，
        // 同一 incident_key 重放只生效一次（incident 表本身也保证只结算一次）。
        PlayerProfile after = value.experience() == 0 ? before
                : profiles.grantExperienceInTransaction(connection, value.playerId(), value.experience(),
                        UUID.nameUUIDFromBytes(("incident:" + value.incidentKey()).getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)), value.incidentKey());
        if (appliedGold.signum() > 0) {
            updateAccount(connection, value.playerId(), balance.add(appliedGold));
        }
        if (appliedMerit > 0) {
            updateWallet(connection, value.playerId(), Math.addExact(merit, appliedMerit));
        }
        for (IncidentActivityCompensation line : value.activities()) {
            updateDaily(connection, value.playerId(), value.activityDate(), line);
            insertLedgers(connection, request.hash(), value, line);
            insertLine(connection, value.incidentKey(), "ACTIVITY", line.activity().name(),
                    line.activity(), line.gold(), line.merit());
        }
        for (ConditionalDecision decision : conditional) {
            IncidentConditionalActivityCompensation reward = decision.reward();
            if (decision.applied()) {
                updateDaily(connection, value.playerId(), value.activityDate(), new IncidentActivityCompensation(
                        reward.activity(), reward.gold(), reward.merit()));
                insertOriginalLedgers(connection, value, reward);
            }
            insertLine(connection, value.incidentKey(), decision.applied() ? "COND_APPLIED" : "COND_PRESENT",
                    reward.idempotencyKey(), reward.activity(), reward.gold(), reward.merit());
        }
        for (String evidence : value.evidenceIds()) {
            insertLine(connection, value.incidentKey(), "EVIDENCE", evidence, null,
                    BigDecimal.ZERO.setScale(2), 0L);
        }
        finalizeClaim(connection, value.incidentKey(), before, after, appliedGold, appliedMerit);
        IncidentCompensationAudit audit = loadAudit(connection, value.incidentKey());
        if (audit == null) throw new IllegalStateException("Committed incident receipt is missing");
        return new IncidentCompensationResult(IncidentCompensationResult.Status.APPLIED, Optional.of(audit));
    }

    private IncidentCompensationResult replayResult(IncidentCompensationAudit existing, String requestHash) {
        IncidentCompensationResult.Status status = existing.requestHash().equals(requestHash)
                ? IncidentCompensationResult.Status.DUPLICATE
                : IncidentCompensationResult.Status.CONFLICT;
        return new IncidentCompensationResult(status, Optional.of(existing));
    }

    /**
     * Lock and validate all three authoritative ledgers before mutating any
     * balance. A partially present or differently shaped original reward is a
     * collision, never evidence that it is safe to mint the missing pieces.
     */
    private List<ConditionalDecision> resolveConditionalActivities(
            Connection connection, IncidentCompensationRequest request
    ) throws SQLException {
        List<ConditionalDecision> decisions = new ArrayList<>();
        for (IncidentConditionalActivityCompensation reward : request.conditionalActivities()) {
            OriginalActivityLedger activity = originalActivityLedger(connection, reward.idempotencyKey());
            OriginalGoldLedger gold = originalGoldLedger(connection, reward.idempotencyKey());
            OriginalMeritLedger merit = originalMeritLedger(connection, reward.idempotencyKey());
            boolean any = activity != null || gold != null || merit != null;
            boolean all = activity != null && gold != null && merit != null;
            if (any && (!all || !matchesOriginal(request.playerId(), reward, activity, gold, merit))) {
                throw new IllegalStateException("Conflicting original incident ledger: " + reward.idempotencyKey());
            }
            decisions.add(new ConditionalDecision(reward, !any));
        }
        return decisions;
    }

    private boolean matchesOriginal(
            UUID playerId, IncidentConditionalActivityCompensation reward, OriginalActivityLedger activity,
            OriginalGoldLedger gold, OriginalMeritLedger merit
    ) {
        return activity.playerId().equals(playerId) && activity.activity() == reward.activity()
                && activity.requestedGold().compareTo(reward.gold()) == 0
                && activity.grantedGold().compareTo(reward.gold()) == 0
                && activity.requestedMerit() == reward.merit() && activity.grantedMerit() == reward.merit()
                && activity.reason().equals(reward.reason())
                && gold.senderId() == null && playerId.equals(gold.receiverId())
                && gold.amount().compareTo(reward.gold()) == 0 && gold.fee().signum() == 0
                && gold.reason().equals(reward.reason())
                && merit.playerId().equals(playerId) && merit.amount() == reward.merit()
                && merit.direction().equals("CREDIT") && merit.reason().equals(reward.reason());
    }

    private OriginalActivityLedger originalActivityLedger(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, activity, requested_gold, granted_gold,
                       requested_merit, granted_merit, reason
                FROM rpg_activity_ledger WHERE idempotency_key = ? FOR UPDATE
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new OriginalActivityLedger(UUID.fromString(result.getString(1)),
                        EconomyActivity.valueOf(result.getString(2)), money(result.getBigDecimal(3)),
                        money(result.getBigDecimal(4)), result.getLong(5), result.getLong(6), result.getString(7));
            }
        }
    }

    private OriginalGoldLedger originalGoldLedger(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT sender_uuid, receiver_uuid, amount, fee, reason
                FROM rpg_ledger WHERE idempotency_key = ? FOR UPDATE
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                String sender = result.getString(1);
                String receiver = result.getString(2);
                return new OriginalGoldLedger(sender == null ? null : UUID.fromString(sender),
                        receiver == null ? null : UUID.fromString(receiver), money(result.getBigDecimal(3)),
                        money(result.getBigDecimal(4)), result.getString(5));
            }
        }
    }

    private OriginalMeritLedger originalMeritLedger(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, amount, direction, reason
                FROM rpg_merit_ledger WHERE idempotency_key = ? FOR UPDATE
                """)) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                return new OriginalMeritLedger(UUID.fromString(result.getString(1)), result.getLong(2),
                        result.getString(3), result.getString(4));
            }
        }
    }

    private boolean profileExists(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM rpg_profiles WHERE player_uuid = ?")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean claimIncident(Connection connection, NormalizedRequest normalized) throws SQLException {
        IncidentCompensationRequest request = normalized.request();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_incident_compensations (
                    incident_key, request_hash, player_uuid, activity_date, experience,
                    total_gold, total_merit, before_profile, after_profile, actor, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '', '', ?, ?) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, request.incidentKey());
            insert.setString(2, normalized.hash());
            insert.setString(3, request.playerId().toString());
            insert.setDate(4, Date.valueOf(request.activityDate()));
            insert.setLong(5, request.experience());
            insert.setBigDecimal(6, normalized.totalGold());
            insert.setLong(7, normalized.totalMerit());
            insert.setString(8, request.actor());
            insert.setString(9, request.reason());
            return insert.executeUpdate() == 1;
        }
    }

    private void finalizeClaim(
            Connection connection, String incidentKey, PlayerProfile before, PlayerProfile after,
            BigDecimal totalGold, long totalMerit
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_incident_compensations
                SET before_profile = ?, after_profile = ?, total_gold = ?, total_merit = ?
                WHERE incident_key = ? AND before_profile = '' AND after_profile = ''
                """)) {
            update.setString(1, encodeProfile(before));
            update.setString(2, encodeProfile(after));
            update.setBigDecimal(3, money(totalGold));
            update.setLong(4, totalMerit);
            update.setString(5, incidentKey);
            if (update.executeUpdate() != 1) throw new IllegalStateException("Incident receipt changed");
        }
    }

    private BigDecimal lockAccount(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT balance FROM rpg_accounts WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing incident compensation account");
                return money(result.getBigDecimal(1));
            }
        }
    }

    private long lockWallet(Connection connection, UUID playerId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_progression_wallets (player_uuid, merit) VALUES (?, 0) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT merit FROM rpg_progression_wallets WHERE player_uuid = ? FOR UPDATE")) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing incident compensation wallet");
                return result.getLong(1);
            }
        }
    }

    private void updateAccount(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Incident account update changed");
        }
    }

    private void updateWallet(Connection connection, UUID playerId, long merit) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_progression_wallets SET merit = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setLong(1, merit);
            update.setString(2, playerId.toString());
            if (update.executeUpdate() != 1) throw new SQLException("Incident wallet update changed");
        }
    }

    private void updateDaily(
            Connection connection, UUID playerId, LocalDate activityDate, IncidentActivityCompensation line
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_activity_daily
                    (player_uuid, activity, activity_date, gold_earned, merit_earned)
                VALUES (?, ?, ?, 0, 0) ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, playerId.toString());
            insert.setString(2, line.activity().name());
            insert.setDate(3, Date.valueOf(activityDate));
            insert.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_activity_daily
                SET gold_earned = gold_earned + ?, merit_earned = merit_earned + ?
                WHERE player_uuid = ? AND activity = ? AND activity_date = ?
                """)) {
            update.setBigDecimal(1, line.gold());
            update.setLong(2, line.merit());
            update.setString(3, playerId.toString());
            update.setString(4, line.activity().name());
            update.setDate(5, Date.valueOf(activityDate));
            if (update.executeUpdate() != 1) throw new SQLException("Incident daily quota update changed");
        }
    }

    private void insertLedgers(
            Connection connection, String requestHash, IncidentCompensationRequest request,
            IncidentActivityCompensation line
    ) throws SQLException {
        String key = ledgerKey(requestHash, line.activity());
        String reason = "incident-compensation:" + request.incidentKey() + ":"
                + line.activity().name().toLowerCase(java.util.Locale.ROOT);
        if (line.gold().signum() > 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_ledger
                        (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                    VALUES (?, ?, NULL, ?, ?, 0, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, key);
                insert.setString(3, request.playerId().toString());
                insert.setBigDecimal(4, line.gold());
                insert.setString(5, reason);
                insert.executeUpdate();
            }
        }
        if (line.merit() > 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_merit_ledger
                        (id, idempotency_key, player_uuid, amount, direction, reason)
                    VALUES (?, ?, ?, ?, 'CREDIT', ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, key);
                insert.setString(3, request.playerId().toString());
                insert.setLong(4, line.merit());
                insert.setString(5, reason);
                insert.executeUpdate();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_activity_ledger (
                    id, idempotency_key, player_uuid, activity, requested_gold, granted_gold,
                    requested_merit, granted_merit, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, request.playerId().toString());
            insert.setString(4, line.activity().name());
            insert.setBigDecimal(5, line.gold());
            insert.setBigDecimal(6, line.gold());
            insert.setLong(7, line.merit());
            insert.setLong(8, line.merit());
            insert.setString(9, reason);
            insert.executeUpdate();
        }
    }

    private void insertOriginalLedgers(
            Connection connection, IncidentCompensationRequest request,
            IncidentConditionalActivityCompensation reward
    ) throws SQLException {
        if (reward.gold().signum() > 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_ledger
                        (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                    VALUES (?, ?, NULL, ?, ?, 0, ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, reward.idempotencyKey());
                insert.setString(3, request.playerId().toString());
                insert.setBigDecimal(4, reward.gold());
                insert.setString(5, reward.reason());
                insert.executeUpdate();
            }
        }
        if (reward.merit() > 0) {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_merit_ledger
                        (id, idempotency_key, player_uuid, amount, direction, reason)
                    VALUES (?, ?, ?, ?, 'CREDIT', ?)
                    """)) {
                insert.setString(1, UUID.randomUUID().toString());
                insert.setString(2, reward.idempotencyKey());
                insert.setString(3, request.playerId().toString());
                insert.setLong(4, reward.merit());
                insert.setString(5, reward.reason());
                insert.executeUpdate();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_activity_ledger (
                    id, idempotency_key, player_uuid, activity, requested_gold, granted_gold,
                    requested_merit, granted_merit, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, reward.idempotencyKey());
            insert.setString(3, request.playerId().toString());
            insert.setString(4, reward.activity().name());
            insert.setBigDecimal(5, reward.gold());
            insert.setBigDecimal(6, reward.gold());
            insert.setLong(7, reward.merit());
            insert.setLong(8, reward.merit());
            insert.setString(9, reward.reason());
            insert.executeUpdate();
        }
    }

    private void insertLine(
            Connection connection, String incidentKey, String kind, String id, EconomyActivity activity,
            BigDecimal gold, long merit
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_incident_compensation_lines
                    (incident_key, line_kind, line_id, activity, gold, merit)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, incidentKey);
            insert.setString(2, kind);
            insert.setString(3, id);
            insert.setString(4, activity == null ? null : activity.name());
            insert.setBigDecimal(5, gold);
            insert.setLong(6, merit);
            insert.executeUpdate();
        }
    }

    private IncidentCompensationAudit loadAudit(Connection connection, String incidentKey) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM rpg_incident_compensations WHERE incident_key = ?")) {
            query.setString(1, incidentKey);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                List<IncidentActivityCompensation> activities = new ArrayList<>();
                List<IncidentConditionalActivityAudit> conditional = new ArrayList<>();
                List<String> evidence = new ArrayList<>();
                loadLines(connection, incidentKey, activities, conditional, evidence);
                UUID playerId = UUID.fromString(result.getString("player_uuid"));
                return new IncidentCompensationAudit(
                        incidentKey, playerId, result.getDate("activity_date").toLocalDate(),
                        result.getLong("experience"), money(result.getBigDecimal("total_gold")),
                        result.getLong("total_merit"), activities, conditional, evidence,
                        result.getString("actor"), result.getString("reason"),
                        result.getString("request_hash"), decodeProfile(playerId, result.getString("before_profile")),
                        decodeProfile(playerId, result.getString("after_profile")),
                        result.getTimestamp("applied_at").toInstant());
            }
        }
    }

    private void loadLines(
            Connection connection, String incidentKey, List<IncidentActivityCompensation> activities,
            List<IncidentConditionalActivityAudit> conditional, List<String> evidence
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT line_kind, line_id, activity, gold, merit
                FROM rpg_incident_compensation_lines
                WHERE incident_key = ? ORDER BY line_kind, line_id
                """)) {
            query.setString(1, incidentKey);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    if (result.getString("line_kind").equals("EVIDENCE")) {
                        evidence.add(result.getString("line_id"));
                    } else if (result.getString("line_kind").startsWith("COND_")) {
                        conditional.add(new IncidentConditionalActivityAudit(
                                result.getString("line_id"), EconomyActivity.valueOf(result.getString("activity")),
                                money(result.getBigDecimal("gold")), result.getLong("merit"),
                                result.getString("line_kind").equals("COND_APPLIED")
                                        ? IncidentConditionalActivityAudit.Status.APPLIED
                                        : IncidentConditionalActivityAudit.Status.ALREADY_PRESENT));
                    } else {
                        activities.add(new IncidentActivityCompensation(
                                EconomyActivity.valueOf(result.getString("activity")),
                                result.getBigDecimal("gold"), result.getLong("merit")));
                    }
                }
            }
        }
    }

    private static boolean valid(IncidentCompensationRequest request) {
        if (request == null || !INCIDENT_KEY.matcher(request.incidentKey()).matches()
                || request.experience() < 0 || request.experience() > MAX_EXPERIENCE
                || request.activities().isEmpty() && request.conditionalActivities().isEmpty()
                || request.activities().size() > 8
                || request.conditionalActivities().size() > 32
                || request.evidenceIds().size() > 32 || request.actor().isEmpty() || request.actor().length() > 64
                || request.reason().isEmpty() || request.reason().length() > 256
                || request.evidenceIds().stream().anyMatch(value -> !EVIDENCE_ID.matcher(value).matches())) {
            return false;
        }
        BigDecimal totalGold = BigDecimal.ZERO.setScale(2);
        long totalMerit = 0L;
        try {
            for (IncidentActivityCompensation line : request.activities()) {
                totalGold = totalGold.add(line.gold());
                totalMerit = Math.addExact(totalMerit, line.merit());
            }
            for (IncidentConditionalActivityCompensation line : request.conditionalActivities()) {
                if (!EVIDENCE_ID.matcher(line.evidenceId()).matches()
                        || !Pattern.compile("[A-Za-z0-9._:-]{3,96}").matcher(line.idempotencyKey()).matches()
                        || line.reason().isEmpty() || line.reason().length() > 128) return false;
                totalGold = totalGold.add(line.gold());
                totalMerit = Math.addExact(totalMerit, line.merit());
            }
        } catch (ArithmeticException exception) {
            return false;
        }
        return totalGold.compareTo(MAX_INCIDENT_GOLD) <= 0 && totalMerit <= MAX_INCIDENT_MERIT;
    }

    private static NormalizedRequest normalize(IncidentCompensationRequest request) {
        List<IncidentActivityCompensation> activities = request.activities().stream()
                .sorted(Comparator.comparing(line -> line.activity().name())).toList();
        List<IncidentConditionalActivityCompensation> conditional = request.conditionalActivities().stream()
                .sorted(Comparator.comparing(IncidentConditionalActivityCompensation::idempotencyKey)).toList();
        List<String> evidence = request.evidenceIds().stream().sorted().toList();
        IncidentCompensationRequest normalized = new IncidentCompensationRequest(
                request.incidentKey(), request.playerId(), request.activityDate(), request.experience(),
                activities, conditional, evidence, request.actor(), request.reason());
        BigDecimal unconditionalGold = activities.stream().map(IncidentActivityCompensation::gold)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        long unconditionalMerit = activities.stream().mapToLong(IncidentActivityCompensation::merit).sum();
        BigDecimal maximumGold = conditional.stream().map(IncidentConditionalActivityCompensation::gold)
                .reduce(unconditionalGold, BigDecimal::add);
        long maximumMerit = Math.addExact(unconditionalMerit,
                conditional.stream().mapToLong(IncidentConditionalActivityCompensation::merit).sum());
        return new NormalizedRequest(normalized, requestHash(normalized), money(maximumGold), maximumMerit,
                money(unconditionalGold), unconditionalMerit);
    }

    static String requestHash(IncidentCompensationRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append(request.incidentKey()).append('|').append(request.playerId()).append('|')
                .append(request.activityDate()).append('|').append(request.experience()).append('|')
                .append(request.reason());
        request.activities().stream().sorted(Comparator.comparing(line -> line.activity().name()))
                .forEach(line -> canonical.append("|A:").append(line.activity()).append(':')
                        .append(line.gold().toPlainString()).append(':').append(line.merit()));
        request.conditionalActivities().stream()
                .sorted(Comparator.comparing(IncidentConditionalActivityCompensation::idempotencyKey))
                .forEach(line -> canonical.append("|C:").append(line.evidenceId()).append(':')
                        .append(line.activity()).append(':').append(line.gold().toPlainString()).append(':')
                        .append(line.merit()).append(':').append(line.idempotencyKey()).append(':')
                        .append(line.reason()));
        request.evidenceIds().stream().sorted().forEach(value -> canonical.append("|E:").append(value));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String ledgerKey(String requestHash, EconomyActivity activity) {
        return "incident:" + requestHash.substring(0, 24) + ":"
                + activity.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String encodeProfile(PlayerProfile profile) {
        return String.join(",", profile.archetype().name(), Integer.toString(profile.ascension()),
                Integer.toString(profile.level()), Long.toString(profile.experience()),
                Integer.toString(profile.energy()), Integer.toString(profile.energyCap()),
                Integer.toString(profile.unspentAttributePoints()), Integer.toString(profile.skillPoints()),
                Integer.toString(profile.attribute(CoreAttribute.DEFENSE)),
                Integer.toString(profile.attribute(CoreAttribute.STRENGTH)),
                Integer.toString(profile.attribute(CoreAttribute.AGILITY)),
                Integer.toString(profile.attribute(CoreAttribute.INTELLIGENCE)),
                Integer.toString(profile.attribute(CoreAttribute.STAMINA)));
    }

    private static PlayerProfile decodeProfile(UUID playerId, String encoded) {
        String[] values = encoded.split(",", -1);
        if (values.length != 13) throw new IllegalStateException("Invalid incident profile receipt");
        EnumMap<CoreAttribute, Integer> attributes = new EnumMap<>(CoreAttribute.class);
        attributes.put(CoreAttribute.DEFENSE, Integer.parseInt(values[8]));
        attributes.put(CoreAttribute.STRENGTH, Integer.parseInt(values[9]));
        attributes.put(CoreAttribute.AGILITY, Integer.parseInt(values[10]));
        attributes.put(CoreAttribute.INTELLIGENCE, Integer.parseInt(values[11]));
        attributes.put(CoreAttribute.STAMINA, Integer.parseInt(values[12]));
        return new PlayerProfile(playerId, Archetype.valueOf(values[0]), Integer.parseInt(values[1]),
                Integer.parseInt(values[2]), Long.parseLong(values[3]), Integer.parseInt(values[4]),
                Integer.parseInt(values[5]), Integer.parseInt(values[6]), Integer.parseInt(values[7]), attributes);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        return inTransaction(operation, new long[1]);
    }

    /**
     * @param versionStamp 出参：提交成功后回填「**持锁时（commit 之前）**」取得的提交版本号
     *                     （{@link JdbcProfileService#nextCommitVersion()}）。取号必须在 commit
     *                     之前 —— commit 一返回行锁就释放，之后再取号会与提交顺序倒置。
     */
    private <T> T inTransaction(SqlOperation<T> operation, long[] versionStamp) {
        try (Connection connection = dataSource.getConnection()) {
            long version;
            try {
                // Production Hikari is configured this way. Enforce the same
                // contract for tests and any future direct DataSource so an
                // incident can never degrade into per-statement autocommit.
                if (connection.getAutoCommit()) connection.setAutoCommit(false);
                version = profiles.nextCommitVersion();
                T value = operation.apply(connection);
                connection.commit();
                versionStamp[0] = version;
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Incident compensation transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open incident compensation transaction", exception);
        }
    }

    private record NormalizedRequest(
            IncidentCompensationRequest request, String hash, BigDecimal totalGold, long totalMerit,
            BigDecimal unconditionalGold, long unconditionalMerit
    ) {
    }

    private record ConditionalDecision(IncidentConditionalActivityCompensation reward, boolean applied) {
    }

    private record OriginalActivityLedger(
            UUID playerId, EconomyActivity activity, BigDecimal requestedGold, BigDecimal grantedGold,
            long requestedMerit, long grantedMerit, String reason
    ) {
    }

    private record OriginalGoldLedger(
            UUID senderId, UUID receiverId, BigDecimal amount, BigDecimal fee, String reason
    ) {
    }

    private record OriginalMeritLedger(UUID playerId, long amount, String direction, String reason) {
    }

    /** Internal control flow used to guarantee a rollback-only cap rejection. */
    private static final class WalletLimitRejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final WalletLimitRejection GOLD = new WalletLimitRejection(
                IncidentCompensationResult.Status.BALANCE_LIMIT);
        private static final WalletLimitRejection MERIT = new WalletLimitRejection(
                IncidentCompensationResult.Status.MERIT_LIMIT);
        private final IncidentCompensationResult.Status status;

        private WalletLimitRejection(IncidentCompensationResult.Status status) {
            super(null, null, false, false);
            this.status = status;
        }

        private IncidentCompensationResult.Status status() {
            return status;
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
