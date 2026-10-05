package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.DungeonRewardDelivery;
import cn.originalrpg.api.model.DungeonRewardRequest;
import cn.originalrpg.api.model.DungeonRewardSettlement;
import cn.originalrpg.api.model.DungeonTicketCandidate;
import cn.originalrpg.api.model.PlayerProfile;
import cn.originalrpg.api.service.DungeonRewardService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * One transaction owns dungeon XP, hidden-ticket pity, the completion receipt,
 * and every mailbox row. The profile row is the per-player serialization lock.
 */
public final class JdbcDungeonRewardService implements DungeonRewardService {
    private static final Pattern ID = Pattern.compile("[a-z0-9._-]{2,64}");
    private static final int MAX_PAYLOAD_LENGTH = 1_000_000;

    private final DataSource dataSource;
    private final Executor executor;
    private final JdbcProfileService profiles;
    /** C-005 Kill Switch；为 null 表示未接入注册表（仅未部署注册表的单测）。 */
    private final MechanismRegistry mechanisms;
    /** G-102 Transactional Outbox 写入口；为 null 表示单实例部署未启用。 */
    private final OutboxRegistry outbox;

    public JdbcDungeonRewardService(
            DataSource dataSource,
            Executor executor,
            JdbcProfileService profiles
    ) {
        this(dataSource, executor, profiles, null);
    }

    public JdbcDungeonRewardService(
            DataSource dataSource,
            Executor executor,
            JdbcProfileService profiles,
            MechanismRegistry mechanisms
    ) {
        this(dataSource, executor, profiles, mechanisms, null);
    }

    public JdbcDungeonRewardService(
            DataSource dataSource,
            Executor executor,
            JdbcProfileService profiles,
            MechanismRegistry mechanisms,
            OutboxRegistry outbox
    ) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
        this.mechanisms = mechanisms;
        this.outbox = outbox;
    }

    @Override
    public CompletionStage<DungeonRewardSettlement> settle(DungeonRewardRequest request) {
        validate(request);
        if (!dungeonFaucetEnabled()) {
            // C-005 fail-closed：机制关闭时零输出。调用方保留恢复日志，
            // 重新开启开关后由幂等 receipt 决定重放或首次结算。
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Dungeon reward faucet is disabled by mechanism "
                            + MechanismRegistry.DUNGEON_GOLD + " (C-005 kill switch)"));
        }
        return AsyncJdbc.supply(executor, () -> {
            long[] version = new long[1];
            DungeonRewardSettlement result = inTransaction(connection -> settle(connection, request), version);
            return new Committed<>(result, version[0]);
        }).thenApply(committed -> {
            profiles.cacheCommitted(committed.value().profile(), committed.version());
            return committed.value();
        });
    }

    private boolean dungeonFaucetEnabled() {
        return mechanisms == null || mechanisms.requireEnabled(MechanismRegistry.DUNGEON_GOLD);
    }

    @Override
    public CompletionStage<Boolean> hasAnyCompletion(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(false);
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement query = connection.prepareStatement("""
                         SELECT 1 FROM rpg_dungeon_reward_receipts
                         WHERE player_uuid = ? LIMIT 1
                         """)) {
                query.setString(1, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    return result.next();
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not query dungeon completion evidence", exception);
            }
        });
    }

    private DungeonRewardSettlement settle(Connection connection, DungeonRewardRequest request) throws SQLException {
        // Serializes same-player runs and makes a concurrent replay observe the
        // first receipt under READ COMMITTED before attempting any mutation.
        PlayerProfile current = profiles.loadForUpdate(connection, request.playerId());
        Receipt existing = receipt(connection, request.runId(), request.playerId());
        if (existing != null) {
            if (!existing.dungeonId().equals(request.dungeonId())
                    || existing.experienceAwarded() != request.experience()) {
                throw new IllegalStateException("Dungeon reward run ID was reused with different content");
            }
            return new DungeonRewardSettlement(
                    DungeonRewardSettlement.Status.REPLAYED,
                    current,
                    existing.queuedDeliveries(),
                    existing.ticketDropped(),
                    existing.ticketFailures());
        }

        TicketOutcome ticket = request.ticketCandidate().isPresent()
                ? settleTicketPity(connection, request, request.ticketCandidate().orElseThrow())
                : TicketOutcome.none();
        List<DungeonRewardDelivery> queued = new ArrayList<>(request.deliveries());
        if (ticket.dropped()) queued.add(request.ticketCandidate().orElseThrow().delivery());
        for (DungeonRewardDelivery delivery : queued) {
            enqueueDelivery(connection, request.playerId(), delivery);
        }

        // 升级与技能点里程碑在同一事务内结算；幂等键用业务 receipt id（run_id），
        // 同一 run 重放不会二次发点（里程碑流水表 (source, idempotency_key) 唯一键）。
        PlayerProfile updated = profiles.grantExperienceInTransaction(
                connection, request.playerId(), request.experience(),
                request.runId(), request.runId().toString());
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_dungeon_reward_receipts (
                    run_id, player_uuid, dungeon_id, experience_awarded,
                    queued_deliveries, ticket_dropped, ticket_failures
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, request.runId().toString());
            insert.setString(2, request.playerId().toString());
            insert.setString(3, request.dungeonId());
            insert.setLong(4, request.experience());
            insert.setInt(5, queued.size());
            insert.setBoolean(6, ticket.dropped());
            insert.setInt(7, ticket.failures());
            insert.executeUpdate();
        }
        // G-102：副本结算事件与 receipt 在同一事务登记，供多服阶段做跨服通知。
        // 单实例下 outbox 为 null（未启用），此处不产生任何写入。
        if (outbox != null) {
            outbox.publish(connection, "dungeon.completion",
                    request.runId().toString(),
                    JdbcOutboxRegistry.idempotencyKey("dungeon.completion",
                            request.runId().toString(), "settled"),
                    "{\"runId\":\"" + request.runId() + "\",\"player\":\""
                            + request.playerId() + "\",\"dungeon\":\"" + request.dungeonId() + "\"}");
        }
        return new DungeonRewardSettlement(
                DungeonRewardSettlement.Status.SETTLED,
                updated,
                queued.size(),
                ticket.dropped(),
                ticket.failures());
    }

    private TicketOutcome settleTicketPity(
            Connection connection,
            DungeonRewardRequest request,
            DungeonTicketCandidate candidate
    ) throws SQLException {
        int failures = ticketFailures(connection, request.playerId(), candidate.pityGroup());
        boolean guaranteed = failures + 1 >= candidate.guarantee();
        boolean dropped = guaranteed || deterministicRoll(
                request.runId(), request.playerId(), candidate.pityGroup()) < candidate.chance();
        int updatedFailures = dropped ? 0 : failures + 1;
        int updated;
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_dungeon_ticket_pity
                SET failures = ?, updated_at = CURRENT_TIMESTAMP
                WHERE player_uuid = ? AND pity_group = ?
                """)) {
            update.setInt(1, updatedFailures);
            update.setString(2, request.playerId().toString());
            update.setString(3, candidate.pityGroup());
            updated = update.executeUpdate();
        }
        if (updated == 0) try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_dungeon_ticket_pity (player_uuid, pity_group, failures)
                VALUES (?, ?, ?)
                """)) {
            insert.setString(1, request.playerId().toString());
            insert.setString(2, candidate.pityGroup());
            insert.setInt(3, updatedFailures);
            insert.executeUpdate();
        }
        return new TicketOutcome(dropped, updatedFailures);
    }

    private int ticketFailures(Connection connection, UUID playerId, String pityGroup) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT failures FROM rpg_dungeon_ticket_pity
                WHERE player_uuid = ? AND pity_group = ? FOR UPDATE
                """)) {
            query.setString(1, playerId.toString());
            query.setString(2, pityGroup);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? Math.max(0, result.getInt(1)) : 0;
            }
        }
    }

    private void enqueueDelivery(
            Connection connection,
            UUID playerId,
            DungeonRewardDelivery delivery
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING
                """)) {
            insert.setString(1, delivery.deliveryId().toString());
            insert.setString(2, playerId.toString());
            insert.setString(3, delivery.itemPayload());
            insert.setString(4, delivery.source());
            if (insert.executeUpdate() == 1) return;
        }
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_uuid, item_payload, source FROM rpg_market_deliveries WHERE id = ?
                """)) {
            query.setString(1, delivery.deliveryId().toString());
            try (ResultSet result = query.executeQuery()) {
                if (result.next()
                        && playerId.toString().equals(result.getString("player_uuid"))
                        && delivery.itemPayload().equals(result.getString("item_payload"))
                        && delivery.source().equals(result.getString("source"))) return;
            }
        }
        throw new IllegalStateException("Dungeon delivery ID collided with different content");
    }

    private Receipt receipt(Connection connection, UUID runId, UUID playerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT dungeon_id, experience_awarded, queued_deliveries, ticket_dropped, ticket_failures
                FROM rpg_dungeon_reward_receipts WHERE run_id = ? AND player_uuid = ?
                """)) {
            query.setString(1, runId.toString());
            query.setString(2, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? new Receipt(
                        result.getString("dungeon_id"),
                        result.getLong("experience_awarded"),
                        result.getInt("queued_deliveries"),
                        result.getBoolean("ticket_dropped"),
                        result.getInt("ticket_failures")) : null;
            }
        }
    }

    private static double deterministicRoll(UUID runId, UUID playerId, String pityGroup) {
        long mixed = runId.getMostSignificantBits()
                ^ Long.rotateLeft(runId.getLeastSignificantBits(), 11)
                ^ Long.rotateLeft(playerId.getMostSignificantBits(), 23)
                ^ Long.rotateLeft(playerId.getLeastSignificantBits(), 37)
                ^ ((long) pityGroup.hashCode() * 0x9E3779B97F4A7C15L);
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static void validate(DungeonRewardRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        if (!ID.matcher(request.dungeonId()).matches() || request.experience() > 1_000_000_000L
                || request.deliveries().size() > 16) {
            throw new IllegalArgumentException("Invalid dungeon reward request bounds");
        }
        java.util.stream.Stream.concat(
                        request.deliveries().stream(),
                        request.ticketCandidate().stream().map(DungeonTicketCandidate::delivery))
                .forEach(delivery -> {
                    if (delivery.itemPayload().length() > MAX_PAYLOAD_LENGTH
                            || delivery.source().length() > 96) {
                        throw new IllegalArgumentException("Dungeon reward delivery exceeds storage bounds");
                    }
                });
        request.ticketCandidate().ifPresent(candidate -> {
            if (!ID.matcher(candidate.pityGroup()).matches()) {
                throw new IllegalArgumentException("Invalid dungeon ticket pity group");
            }
        });
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
                version = profiles.nextCommitVersion();
                T value = operation.apply(connection);
                connection.commit();
                versionStamp[0] = version;
                return value;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Dungeon reward transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open dungeon reward transaction", exception);
        }
    }

    private record Receipt(
            String dungeonId,
            long experienceAwarded,
            int queuedDeliveries,
            boolean ticketDropped,
            int ticketFailures
    ) {
    }

    private record TicketOutcome(boolean dropped, int failures) {
        private static TicketOutcome none() {
            return new TicketOutcome(false, 0);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
