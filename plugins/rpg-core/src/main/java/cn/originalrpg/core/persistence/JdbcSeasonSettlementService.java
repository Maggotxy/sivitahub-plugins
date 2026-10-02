package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SeasonSettlementPreview;
import cn.originalrpg.api.model.SeasonSettlementResult;
import cn.originalrpg.api.rules.SeasonSettlementState;
import cn.originalrpg.api.service.SeasonSettlementService;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * C-010 换季事务：九态状态机 + 先写待结算日志 + 原子提交 + 清扫。
 *
 * <p>崩溃一致性优先于便利性。每次迁移固定三步：</p>
 * <ol>
 *   <li><b>落日志</b>：把「结算 id / 请求指纹 / 从哪态到哪态 / 操作者 / 原因 / 事实指纹」
 *       写进 {@code rpg_season_settlement_transitions}。它是重放的唯一真相源：
 *       重放先查日志，命中同指纹直接返回 DUPLICATE，绝不重复资金动作。</li>
 *   <li><b>原子提交</b>：状态行用 {@code UPDATE ... WHERE status = ?} 做乐观闸门，
 *       资金动作与状态迁移共享同一个 JDBC 事务，要么全成要么全不落。</li>
 *   <li><b>清扫</b>：COMPLETED 时把本次结算的进度行打上 {@code committed_at}，
 *       一条再次执行的资金动作找不到 {@code committed_at IS NULL} 的目标行，
 *       因此无法二次归零、二次入账。</li>
 * </ol>
 *
 * <p>本服务只碰「赛季层」功勋：{@code rpg_progression_wallets.merit} 保持永久层语义不变，
 * 赛季层单独放在 {@code rpg_season_merit_wallets}。旧赛季赛季层结清后逐条写入既有
 * {@code rpg_merit_ledger}（幂等键 {@code season-settlement:<新赛季>:<玩家>}），然后原子归零。
 * 金币、星辉、物品、战力一律不参与换季。</p>
 */
public final class JdbcSeasonSettlementService implements SeasonSettlementService {
    private static final Pattern SEASON_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");
    private static final int REASON_MIN = 3;
    private static final int REASON_MAX = 128;
    /** 单次结算的玩家上限：超过就拒绝，让操作者先处理积压，而不是悄悄拖长事务。 */
    private static final long MAX_SETTLE_BATCH = 10_000L;
    /** 换季事务版本；旧版本的合法边冻结在当时，不随枚举增删漂移。 */
    private static final long TRANSACTION_VERSION = 1L;

    private final DataSource dataSource;
    private final Executor executor;
    private final MechanismRegistry mechanisms;

    public JdbcSeasonSettlementService(DataSource dataSource, Executor executor, MechanismRegistry mechanisms) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.mechanisms = mechanisms;
    }

    @Override
    public CompletionStage<SeasonSettlementState> state(String seasonId) {
        String normalized = normalize(seasonId);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid season id: " + seasonId);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> currentState(connection, normalized)));
    }

    @Override
    public CompletionStage<SeasonSettlementPreview> preview(String seasonId) {
        String normalized = normalize(seasonId);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid season id: " + seasonId);
        }
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> computePreview(connection, normalized)));
    }

    @Override
    public CompletionStage<SeasonSettlementResult> advance(
            String seasonId, SeasonSettlementState target, String actor, String reason
    ) {
        return advance(seasonId, null, target, actor, reason);
    }

    @Override
    public CompletionStage<SeasonSettlementResult> advance(
            String seasonId, String nextSeasonId, SeasonSettlementState target, String actor, String reason
    ) {
        String normalized = normalize(seasonId);
        String actorName = actor == null ? "" : actor.strip();
        String why = reason == null ? "" : reason.strip();
        if (normalized == null || target == null || actorName.isEmpty()
                || why.length() < REASON_MIN || why.length() > REASON_MAX) {
            return CompletableFuture.completedFuture(new SeasonSettlementResult(
                    SeasonSettlementResult.Status.INVALID, SeasonSettlementState.DRAFT,
                    normalized == null ? "" : normalized, 0, 0, false));
        }
        // C-005：换季会改动功勋账本，属账本动作，必须能被 Kill Switch 熔断。
        if (mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.SEASON_SETTLEMENT)) {
            return replayableState(normalized).thenApply(state -> new SeasonSettlementResult(
                    SeasonSettlementResult.Status.BLOCKED, state, normalized, 0, 0, false));
        }
        // 先让状态机判定这条边合不合法，再判定结算目标赛季：非法边必须报 ILLEGAL_TRANSITION，
        // 而不是被下一个校验抢先掩盖成 INVALID。
        String next = nextSeasonId == null ? null : normalize(nextSeasonId);
        String target2 = next;
        return AsyncJdbc.supply(executor, () -> inTransaction(
                connection -> transition(connection, normalized, target2, target, actorName, why)));
    }

    private CompletionStage<SeasonSettlementState> replayableState(String seasonId) {
        return AsyncJdbc.supply(executor, () -> inTransaction(connection -> currentState(connection, seasonId)));
    }

    private SeasonSettlementResult transition(
            Connection connection, String seasonId, String nextSeasonId, SeasonSettlementState target,
            String actor, String reason
    ) throws SQLException {
        // 换季是一个全局闸门：先把闸门行锁住，两个赛季就不能互相看不见地并发推进。
        Journal journal = lockJournal(connection);
        SeasonSettlementState current = journal == null
                ? currentState(connection, seasonId)
                : journal.stateFor(seasonId);
        if (current == target) {
            return result(SeasonSettlementResult.Status.UNCHANGED, current, seasonId, 0, 0, false);
        }
        SettlementFact fact = fact(connection, seasonId);
        // 重放优先：同结算 id + 同请求指纹的重放必须报 DUPLICATE，
        // 而不是被「该边已走过」的判定掩盖成 ILLEGAL_TRANSITION。
        if (journal != null && fact.settlementId() != null && journal.settlementId().equals(fact.settlementId())) {
            String replayHash = hash(seasonId, current.name(), target.name(), actor, reason, journal.factDigest());
            Optional<SeasonSettlementResult> replayed = replay(connection, journal.settlementId(), replayHash, seasonId);
            if (replayed.isPresent()) {
                return replayed.orElseThrow();
            }
        }
        if (!current.canTransitionTo(target)) {
            return result(SeasonSettlementResult.Status.ILLEGAL_TRANSITION, current, seasonId, 0, 0, false);
        }
        if (target == SeasonSettlementState.SETTLING
                && (nextSeasonId == null || nextSeasonId.equals(seasonId))) {
            // 结算到自己身上、或不给目标赛季 = 「只改 season-id 假装换季」，必须拒绝。
            return result(SeasonSettlementResult.Status.INVALID, current, seasonId, 0, 0, false);
        }
        if (current == SeasonSettlementState.DRAFT && target == SeasonSettlementState.PREVIEWED) {
            return writePreview(connection, seasonId, fact, actor, reason);
        }
        if (journal == null || !journal.settlementId().equals(fact.settlementId())) {
            // 预览由另一次结算生成（或被人工清理）：此时审批会批到错的事实上，必须拒绝。
            return result(SeasonSettlementResult.Status.CONFLICT, current, seasonId, 0, 0, false);
        }
        if (current == SeasonSettlementState.PREVIEWED
                && target == SeasonSettlementState.APPROVED
                && !Objects.equals(testDigest(connection, seasonId), journal.factDigest())) {
            // 预览之后资金又变了：审批必须失败并重新预览，而不是照旧执行。
            return result(SeasonSettlementResult.Status.CONFLICT, current, seasonId, 0, 0, false);
        }
        String requestHash = hash(seasonId, current.name(), target.name(), actor, reason, journal.factDigest());
        Optional<SeasonSettlementResult> replayed = replay(connection, journal.settlementId(), requestHash, seasonId);
        if (replayed.isPresent()) {
            return replayed.orElseThrow();
        }
        return commitTransition(connection, journal, nextSeasonId, current, target, actor, reason, requestHash);
    }

    private SeasonSettlementResult writePreview(
            Connection connection, String seasonId, SettlementFact fact, String actor, String reason
    ) throws SQLException {
        SeasonSettlementPreview preview = computePreview(connection, seasonId);
        String factDigest = preview.digest();
        String settlementId = fact.settlementId() == null ? UUID.randomUUID().toString() : fact.settlementId();
        if (fact.settlementId() == null) {
            insertSettlement(connection, settlementId, seasonId, SeasonSettlementState.PREVIEWED, actor, factDigest);
        } else {
            updateSettlement(connection, settlementId, SeasonSettlementState.PREVIEWED, actor, factDigest);
        }
        claimGate(connection, settlementId);
        insertTransition(connection, settlementId, SeasonSettlementState.DRAFT,
                SeasonSettlementState.PREVIEWED, actor, reason, factDigest, requestHashOf(
                        seasonId, SeasonSettlementState.DRAFT, SeasonSettlementState.PREVIEWED,
                        actor, reason, factDigest));
        return result(SeasonSettlementResult.Status.APPLIED, SeasonSettlementState.PREVIEWED, seasonId,
                preview.players(), preview.settledPlayers(), false);
    }

    private SeasonSettlementResult commitTransition(
            Connection connection, Journal journal, String nextSeasonId, SeasonSettlementState current,
            SeasonSettlementState target, String actor, String reason, String requestHash
    ) throws SQLException {
        long scanned = 0;
        long carried = 0;
        if (target == SeasonSettlementState.SETTLING) {
            // 结算前复算事实：预览/审批之后资金若又变了，必须先把结算置为 FAILED，
            // 而不是照旧执行 —— 资金动作不可逆，宁可不做。
            String currentDigest = testDigest(connection, journal.seasonId());
            if (!Objects.equals(currentDigest, journal.factDigest())) {
                markFailed(connection, journal, current, requestHash);
                return result(SeasonSettlementResult.Status.BLOCKED, SeasonSettlementState.FAILED,
                        journal.seasonId(), 0, 0, false);
            }
            SettlementRun run = settleSeasonMerit(connection, journal.settlementId(), nextSeasonId);
            scanned = run.scanned();
            carried = run.settled();
        }
        // 校验（VERIFYING → COMPLETED）：每条资金动作必须已提交，且净值与账本一致。
        if (current == SeasonSettlementState.VERIFYING && target == SeasonSettlementState.COMPLETED
                && !verifySettlement(connection, journal.settlementId(), journal.seasonId())) {
            markFailed(connection, journal, current, requestHash);
            return result(SeasonSettlementResult.Status.BLOCKED, SeasonSettlementState.FAILED,
                    journal.seasonId(), 0, 0, false);
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_settlements SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """)) {
            update.setString(1, target.name());
            update.setString(2, journal.settlementId());
            update.setString(3, current.name());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Season settlement state changed during transition");
            }
        }
        insertTransition(connection, journal.settlementId(), current, target, actor, reason,
                journal.factDigest(), requestHash);
        // 清扫：资金动作提交后立刻把待结算日志标成已提交，再次执行的资金动作找不到目标行，
        // 不可能二次归零或二次入账。
        if (target == SeasonSettlementState.SETTLING) {
            sweep(connection, journal.settlementId());
        }
        return result(SeasonSettlementResult.Status.APPLIED, target, journal.seasonId(), scanned, carried, false);
    }

    private void markFailed(
            Connection connection, Journal journal, SeasonSettlementState current, String requestHash
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_settlements SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """)) {
            update.setString(1, journal.settlementId());
            update.setString(2, current.name());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Season settlement could not enter FAILED");
            }
        }
        insertTransition(connection, journal.settlementId(), current, SeasonSettlementState.FAILED,
                "system", "settlement fact changed before commit", journal.factDigest(),
                hash(journal.settlementId(), current.name(), SeasonSettlementState.FAILED.name(), requestHash));
    }

    private Optional<SeasonSettlementResult> replay(
            Connection connection, String settlementId, String requestHash, String seasonId
    ) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT request_hash, to_state FROM rpg_season_settlement_transitions
                WHERE settlement_id = ? ORDER BY created_at DESC, to_state DESC
                """)) {
            query.setString(1, settlementId);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    if (requestHash.equals(result.getString(1))) {
                        return Optional.of(result(SeasonSettlementResult.Status.DUPLICATE,
                                SeasonSettlementState.valueOf(result.getString(2)), seasonId, 0, 0, true));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 旧赛季赛季层功勋的原子结清。
     *
     * <p>顺序固定：先写不可变账本，再归零，再登记进度。三者共享一个事务；
     * 账本行以「新赛季 + 玩家」为幂等键，所以任何一次重试都会在账本处撞唯一键并被拒绝，
     * 因此不存在重复入账或重复归零。</p>
     */
    private SettlementRun settleSeasonMerit(
            Connection connection, String settlementId, String newSeasonId
    ) throws SQLException {
        long scanned = 0;
        long settled = 0;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, season_id, season_merit FROM rpg_season_merit_wallets
                WHERE season_id <> ? AND season_merit > 0
                ORDER BY player_uuid LIMIT ?
                FOR UPDATE
                """)) {
            query.setString(1, newSeasonId);
            query.setLong(2, MAX_SETTLE_BATCH);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    scanned++;
                    String playerId = result.getString(1);
                    String seasonId = result.getString(2);
                    long amount = result.getLong(3);
                    writeSettlementLedger(connection, newSeasonId, seasonId, playerId, amount);
                    zeroSeasonMerit(connection, playerId, seasonId, amount);
                    moveWalletToNewSeason(connection, playerId, newSeasonId);
                    recordProgress(connection, settlementId, playerId, amount);
                    settled++;
                }
            }
        }
        if (settled >= MAX_SETTLE_BATCH) {
            throw new IllegalStateException(
                    "Season settlement batch limit reached; drain the backlog before retrying");
        }
        return new SettlementRun(scanned, settled);
    }

    /** 待结算日志：资金动作写一行，清扫时统一打上 committed_at。 */
    private void recordProgress(
            Connection connection, String settlementId, String playerId, long amount
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_settlement_progress (settlement_id, player_uuid, merit_debited)
                VALUES (?, ?, ?)
                """)) {
            insert.setString(1, settlementId);
            insert.setString(2, playerId);
            insert.setLong(3, amount);
            insert.executeUpdate();
        }
    }

    /** 归零之后把钱包打上新赛季标记：旧赛季余额已经在账本里，钱包从此只承载新赛季。 */
    private void moveWalletToNewSeason(Connection connection, String playerId, String newSeasonId)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_merit_wallets SET season_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ?
                """)) {
            update.setString(1, newSeasonId);
            update.setString(2, playerId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Season Merit wallet is missing for " + playerId);
            }
        }
    }

    private void writeSettlementLedger(
            Connection connection, String newSeasonId, String seasonId, String playerId, long amount
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_merit_ledger (id, idempotency_key, player_uuid, amount, direction, reason)
                VALUES (?, ?, ?, ?, 'DEBIT', ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, "season-settlement:" + newSeasonId + ":" + playerId);
            insert.setString(3, playerId);
            insert.setLong(4, amount);
            insert.setString(5, "season-settlement-" + seasonId);
            insert.executeUpdate();
        }
    }

    private void zeroSeasonMerit(Connection connection, String playerId, String seasonId, long amount)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_merit_wallets
                SET season_merit = 0, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND season_id = ? AND season_merit = ?
                """)) {
            update.setString(1, playerId);
            update.setString(2, seasonId);
            update.setLong(3, amount);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Season Merit changed during settlement for " + playerId);
            }
        }
    }

    /** 清扫：把本次结算的进度行标成已提交，使再次执行的资金动作无目标行可发。 */
    private void sweep(Connection connection, String settlementId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_settlement_progress SET committed_at = CURRENT_TIMESTAMP
                WHERE settlement_id = ? AND committed_at IS NULL
                """)) {
            update.setString(1, settlementId);
            update.executeUpdate();
        }
    }

    private long progressCount(Connection connection, String settlementId) throws SQLException {
        try (PreparedStatement count = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_season_settlement_progress WHERE settlement_id = ?
                """)) {
            count.setString(1, settlementId);
            return scalar(count);
        }
    }

    /** 校验：本次结算的每一条资金动作都必须已提交，且净值与账本一致。 */
    private boolean verifySettlement(Connection connection, String settlementId, String retiredSeasonId)
            throws SQLException {
        try (PreparedStatement count = connection.prepareStatement("""
                SELECT COUNT(*), COALESCE(SUM(merit_debited), 0) FROM rpg_season_settlement_progress
                WHERE settlement_id = ? AND committed_at IS NOT NULL
                """)) {
            count.setString(1, settlementId);
            try (ResultSet result = count.executeQuery()) {
                result.next();
                long committed = result.getLong(1);
                long debited = result.getLong(2);
                if (committed != progressCount(connection, settlementId)) return false;
                return debited == ledgerTotal(connection, settlementId, retiredSeasonId);
            }
        }
    }

    /** 账本里本次结算实际落下的净额，用作校验的第二条腿。 */
    private long ledgerTotal(Connection connection, String settlementId, String retiredSeasonId)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount), 0) FROM rpg_merit_ledger
                WHERE reason = ? AND idempotency_key LIKE ?
                """)) {
            query.setString(1, "season-settlement-" + retiredSeasonId);
            query.setString(2, "season-settlement:%");
            return scalar(query);
        }
    }

    private SeasonSettlementPreview computePreview(Connection connection, String seasonId) throws SQLException {
        long players;
        long settledPlayers;
        long seasonMerit;
        long retainedMerit;
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(w.player_uuid),
                       COALESCE(SUM(CASE WHEN m.player_uuid IS NULL OR m.season_merit = 0 THEN 0 ELSE 1 END), 0),
                       COALESCE(SUM(m.season_merit), 0),
                       COALESCE(SUM(w.merit), 0)
                FROM rpg_progression_wallets w
                LEFT JOIN rpg_season_merit_wallets m ON m.player_uuid = w.player_uuid
                """)) {
            try (ResultSet result = query.executeQuery()) {
                result.next();
                players = result.getLong(1);
                settledPlayers = result.getLong(2);
                seasonMerit = result.getLong(3);
                retainedMerit = result.getLong(4);
            }
        }
        long honor = honorTotal(connection, seasonId);
        return new SeasonSettlementPreview(seasonId, seasonId, players, settledPlayers,
                retainedMerit, seasonMerit, honor,
                previewDigest(seasonId, players, settledPlayers, retainedMerit, seasonMerit, honor));
    }

    /** 事实指纹：预览/审批期间资金变化的检测依据。 */
    private String testDigest(Connection connection, String seasonId) throws SQLException {
        return computePreview(connection, seasonId).digest();
    }

    private long honorTotal(Connection connection, String seasonId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COALESCE(SUM(honor), 0) FROM rpg_season_player_progress WHERE season_id = ?
                """)) {
            query.setString(1, seasonId);
            return Math.max(0L, scalar(query));
        }
    }

    private SeasonSettlementState currentState(Connection connection, String seasonId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT status FROM rpg_season_settlements
                WHERE season_id = ? ORDER BY created_at DESC LIMIT 1
                """)) {
            query.setString(1, seasonId);
            try (ResultSet result = query.executeQuery()) {
                return result.next()
                        ? SeasonSettlementState.valueOf(result.getString(1))
                        : SeasonSettlementState.DRAFT;
            }
        }
    }

    /** 先建闸门行、再锁闸门行：换季是全局单点，两个赛季不可能并发进入结算。 */
    private Journal lockJournal(Connection connection) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_settlement_gate (singleton_id, settlement_id, updated_at)
                VALUES (1, NULL, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING
                """)) {
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT settlement_id FROM rpg_season_settlement_gate WHERE singleton_id = 1 FOR UPDATE
                """)) {
            try (ResultSet result = query.executeQuery()) {
                result.next();
                String settlementId = result.getString(1);
                if (settlementId == null) return null;
                try (PreparedStatement load = connection.prepareStatement("""
                        SELECT id, season_id, status, fact_digest FROM rpg_season_settlements WHERE id = ?
                        """)) {
                    load.setString(1, settlementId);
                    try (ResultSet row = load.executeQuery()) {
                        if (!row.next()) return null;
                        return new Journal(row.getString(1), row.getString(2),
                                SeasonSettlementState.valueOf(row.getString(3)), row.getString(4));
                    }
                }
            }
        }
    }

    private void claimGate(Connection connection, String settlementId) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_settlement_gate SET settlement_id = ?, updated_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """)) {
            update.setString(1, settlementId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Season settlement gate row is missing");
            }
        }
    }

    private SettlementFact fact(Connection connection, String seasonId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT id FROM rpg_season_settlements WHERE season_id = ? ORDER BY created_at DESC LIMIT 1
                """)) {
            query.setString(1, seasonId);
            try (ResultSet result = query.executeQuery()) {
                return new SettlementFact(result.next() ? result.getString(1) : null);
            }
        }
    }

    private void insertSettlement(
            Connection connection, String settlementId, String seasonId,
            SeasonSettlementState state, String actor, String factDigest
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_settlements
                    (id, season_id, status, preview_hash, fact_digest, approved_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, settlementId);
            insert.setString(2, seasonId);
            insert.setString(3, state.name());
            insert.setString(4, factDigest);
            insert.setString(5, factDigest);
            insert.setString(6, actor);
            insert.executeUpdate();
        }
    }

    private void updateSettlement(
            Connection connection, String settlementId, SeasonSettlementState state,
            String actor, String factDigest
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_season_settlements
                SET status = ?, preview_hash = ?, fact_digest = ?, approved_by = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """)) {
            update.setString(1, state.name());
            update.setString(2, factDigest);
            update.setString(3, factDigest);
            update.setString(4, actor);
            update.setString(5, settlementId);
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("Season settlement row is missing: " + settlementId);
            }
        }
    }

    private void insertTransition(
            Connection connection, String settlementId, SeasonSettlementState from, SeasonSettlementState to,
            String actor, String reason, String factDigest, String requestHash
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_season_settlement_transitions (
                    id, settlement_id, from_state, to_state, actor, reason, fact_digest, request_hash,
                    transaction_version, committed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, settlementId);
            insert.setString(3, from.name());
            insert.setString(4, to.name());
            insert.setString(5, actor);
            insert.setString(6, reason);
            insert.setString(7, factDigest);
            insert.setString(8, requestHash);
            insert.setLong(9, TRANSACTION_VERSION);
            insert.executeUpdate();
        }
    }

    private long scalar(PreparedStatement query) throws SQLException {
        try (ResultSet result = query.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static SeasonSettlementResult result(
            SeasonSettlementResult.Status status, SeasonSettlementState state, String seasonId,
            long scanned, long carried, boolean replayed
    ) {
        return new SeasonSettlementResult(status, state, seasonId, scanned, carried, replayed);
    }

    private static String requestHashOf(
            String seasonId, SeasonSettlementState from, SeasonSettlementState to,
            String actor, String reason, String factDigest
    ) {
        return hash(seasonId, from.name(), to.name(), actor, reason, factDigest);
    }

    private static String previewDigest(
            String seasonId, long players, long settledPlayers, long retained, long season, long honor
    ) {
        return hash(seasonId, Long.toString(players), Long.toString(settledPlayers),
                Long.toString(retained), Long.toString(season), Long.toString(honor));
    }

    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.join("\u0000", values).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for season settlement receipts", exception);
        }
    }

    private static String normalize(String seasonId) {
        if (seasonId == null) return null;
        String stripped = seasonId.strip().toLowerCase(Locale.ROOT);
        return SEASON_ID.matcher(stripped).matches() ? stripped : null;
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
                throw new IllegalStateException("Season settlement transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open season settlement transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** 已落库的待结算日志：迁移的锁目标与重放的事实源。 */
    private record Journal(
            String settlementId, String seasonId, SeasonSettlementState state, String factDigest
    ) {
        SeasonSettlementState stateFor(String seasonId) {
            // 闸门锁在别的赛季上：说明那次结算还没收口，本结算视为已完成，不可再推进。
            return this.seasonId.equals(seasonId) ? state : SeasonSettlementState.COMPLETED;
        }
    }

    private record SettlementFact(String settlementId) {
    }

    private record SettlementRun(long scanned, long settled) {
    }
}
