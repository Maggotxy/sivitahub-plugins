package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommunityApprovalOutcome;
import cn.originalrpg.api.model.CommunityRole;
import cn.originalrpg.api.model.CommunityRolePower;
import cn.originalrpg.api.model.CommunityStorageRequest;
import cn.originalrpg.api.model.CommunityStorageResult;
import cn.originalrpg.api.model.CommunityStorageSnapshot;
import cn.originalrpg.api.model.MarketGoods;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.model.MarketOperationResult;
import cn.originalrpg.api.rules.CommunityStorageRules;
import cn.originalrpg.api.service.CommunityStorageService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static cn.originalrpg.api.model.CommunityStorageResult.Status;

/** Construction-material custody policy over the existing market escrow and delivery tables. */
public final class JdbcCommunityStorageService implements CommunityStorageService {
    private static final String ACTION = "community.withdrawal";
    private final DataSource dataSource;
    private final Executor executor;
    private final boolean enabled;
    private final int capacity;
    private final MechanismRegistry mechanisms;

    public JdbcCommunityStorageService(DataSource dataSource, Executor executor, boolean enabled,
                                       int capacity, MechanismRegistry mechanisms) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.executor = Objects.requireNonNull(executor);
        this.enabled = enabled;
        this.capacity = Math.clamp(capacity, 1, 500);
        this.mechanisms = mechanisms;
    }

    @Override public boolean enabled() { return enabled; }

    @Override
    public CompletionStage<Optional<CommunityStorageSnapshot>> snapshot(UUID playerId) {
        if (playerId == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(connection -> {
            Community community = communityForMember(connection, playerId, false);
            if (community == null) return Optional.empty();
            List<MarketListing> supplies = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT * FROM rpg_market_listings WHERE custody_community_id = ?
                        AND state IN ('PREPARED', 'ACTIVE', 'QUARANTINED') ORDER BY created_at, id LIMIT 500
                    """)) {
                query.setString(1, community.id().toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) supplies.add(CommunityStorageTransactions.listing(rows));
                }
            }
            List<CommunityStorageRequest> requests = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT * FROM rpg_community_storage_requests
                    WHERE community_id = ? AND state = 'PENDING' ORDER BY created_at, request_id LIMIT 500
                    """)) {
                query.setString(1, community.id().toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) requests.add(view(connection, readRequest(rows)));
                }
            }
            return Optional.of(new CommunityStorageSnapshot(community.id(), community.name(), community.tag(),
                    capacity, supplies, requests));
        });
    }

    @Override
    public CompletionStage<MarketOperationResult> prepare(UUID listingId, UUID donorId, String itemPayload, MarketGoods goods) {
        if (!enabled || mechanisms != null && !mechanisms.requireEnabled(MechanismRegistry.MARKET_ESCROW)
                || listingId == null || donorId == null || itemPayload == null || itemPayload.isBlank()
                || itemPayload.length() > 64_000 || !CommunityStorageRules.accepts(goods)) {
            return completedMarket(MarketOperationResult.Status.INVALID, null);
        }
        return async(connection -> {
            Community community = communityForMember(connection, donorId, true);
            if (community == null) return market(MarketOperationResult.Status.NOT_OWNER, null);
            MarketListing existing = listing(connection, community.id(), listingId, true);
            if (existing != null) {
                boolean exact = existing.sellerId().equals(donorId) && existing.itemPayload().equals(itemPayload)
                        && existing.goods().filter(goods::equals).isPresent();
                return market(exact ? MarketOperationResult.Status.DUPLICATE : MarketOperationResult.Status.INVALID, existing);
            }
            if (heldCount(connection, "custody_community_id", community.id()) >= capacity
                    || heldCount(connection, "seller_uuid", donorId) >= 128) {
                return market(MarketOperationResult.Status.LISTING_LIMIT, null);
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO rpg_market_listings
                        (id, seller_uuid, item_payload, price, fee_rate, state, custody_community_id,
                         item_matcher, item_quantity)
                    VALUES (?, ?, ?, 0, 0, 'PREPARED', ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, listingId.toString());
                insert.setString(2, donorId.toString());
                insert.setString(3, itemPayload);
                insert.setString(4, community.id().toString());
                insert.setString(5, goods.matcher());
                insert.setInt(6, goods.quantity());
                if (insert.executeUpdate() != 1) return market(MarketOperationResult.Status.DUPLICATE_ITEM, null);
            }
            CommunityAuditWriter.append(connection, community.id(), listingId, donorId, "STORAGE_PREPARED",
                    goods.matcher() + " x" + goods.quantity());
            return market(MarketOperationResult.Status.SUCCESS, listing(connection, community.id(), listingId, false));
        });
    }

    @Override
    public CompletionStage<MarketOperationResult> activate(UUID listingId, UUID donorId) {
        return finishPreparation(listingId, donorId, false);
    }

    @Override
    public CompletionStage<MarketOperationResult> quarantinePrepared(UUID listingId, UUID donorId) {
        return finishPreparation(listingId, donorId, true);
    }

    private CompletionStage<MarketOperationResult> finishPreparation(UUID listingId, UUID donorId, boolean quarantine) {
        if (listingId == null || donorId == null) return completedMarket(MarketOperationResult.Status.INVALID, null);
        // Recovery does not create new custody and must remain available when new deposits are disabled.
        return async(connection -> {
            UUID communityId = listingCommunity(connection, listingId);
            if (communityId == null || community(connection, communityId, true) == null) {
                return market(MarketOperationResult.Status.NOT_FOUND, null);
            }
            MarketListing listing = listing(connection, communityId, listingId, true);
            if (listing == null) return market(MarketOperationResult.Status.NOT_FOUND, null);
            if (!listing.sellerId().equals(donorId)) return market(MarketOperationResult.Status.NOT_OWNER, listing);
            var desired = quarantine ? MarketListing.State.QUARANTINED : MarketListing.State.ACTIVE;
            if (listing.state() == desired) return market(MarketOperationResult.Status.SUCCESS, listing);
            if (listing.state() != MarketListing.State.PREPARED) return market(MarketOperationResult.Status.NOT_ACTIVE, listing);
            if (!listing.goods().filter(CommunityStorageRules::accepts).isPresent()) {
                return market(MarketOperationResult.Status.ITEM_NOT_TRADEABLE, listing);
            }
            try (var update = connection.prepareStatement("""
                    UPDATE rpg_market_listings SET state = ?
                    WHERE id = ? AND custody_community_id = ? AND state = 'PREPARED'
                    """)) {
                update.setString(1, desired.name());
                update.setString(2, listingId.toString());
                update.setString(3, communityId.toString());
                requireOne(update.executeUpdate(), "Resolving community preparation");
            }
            CommunityAuditWriter.append(connection, communityId, listingId, donorId,
                    quarantine ? "STORAGE_QUARANTINED" : "STORAGE_STORED",
                    quarantine ? "Missing physical marker and saved removal receipt" : "Physical removal receipt persisted");
            return market(MarketOperationResult.Status.SUCCESS, listing(connection, communityId, listingId, false));
        });
    }

    @Override
    public CompletionStage<List<MarketListing>> preparedDeposits(UUID donorId) {
        if (donorId == null) return CompletableFuture.completedFuture(List.of());
        return async(connection -> {
            List<MarketListing> deposits = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT * FROM rpg_market_listings
                    WHERE seller_uuid = ? AND custody_community_id IS NOT NULL AND state = 'PREPARED'
                    ORDER BY created_at, id
                    """)) {
                query.setString(1, donorId.toString());
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) deposits.add(CommunityStorageTransactions.listing(rows));
                }
            }
            return List.copyOf(deposits);
        });
    }

    @Override
    public CompletionStage<CommunityStorageResult> requestWithdrawal(UUID actorId, UUID listingId, UUID recipientId,
                                                                    String idempotencyKey) {
        if (!enabled) return completed(Status.DISABLED);
        if (actorId == null || listingId == null || recipientId == null || idempotencyKey == null
                || !idempotencyKey.matches("[A-Za-z0-9:_-]{1,96}")) return completed(Status.INVALID);
        return async(connection -> {
            Community community = communityForMember(connection, actorId, true);
            if (community == null) return CommunityStorageResult.of(Status.NOT_MEMBER);
            Member actor = member(connection, community.id(), actorId);
            if (!governor(actor)) return CommunityStorageResult.of(Status.NOT_ALLOWED);
            Member recipient = member(connection, community.id(), recipientId);
            if (recipient == null) return CommunityStorageResult.of(Status.TARGET_NOT_MEMBER);
            Request replay = queryRequest(connection, "idempotency_key", idempotencyKey, false);
            if (replay != null) {
                if (!replay.communityId().equals(community.id()) || !replay.proposerId().equals(actorId)
                        || !replay.recipientId().equals(recipientId) || !replay.listingId().equals(listingId)) {
                    return CommunityStorageResult.of(Status.INVALID);
                }
                return result(connection, stateStatus(replay, Status.ACTIVE_REQUEST), replay);
            }
            Request pending = queryRequest(connection, "active_listing_id", listingId.toString(), true);
            if (pending != null) {
                if (!pending.communityId().equals(community.id())) return CommunityStorageResult.of(Status.NOT_FOUND);
                return result(connection, Status.ACTIVE_REQUEST, pending);
            }
            MarketListing item = listing(connection, community.id(), listingId, true);
            if (item == null || item.state() != MarketListing.State.ACTIVE
                    || item.goods().filter(CommunityStorageRules::accepts).isEmpty()) {
                return CommunityStorageResult.of(Status.NOT_FOUND);
            }
            UUID id = UUID.randomUUID();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO rpg_community_storage_requests
                        (request_id, community_id, listing_id, active_listing_id, proposer_uuid,
                         recipient_uuid, recipient_joined_at, state, idempotency_key)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """)) {
                insert.setString(1, id.toString());
                insert.setString(2, community.id().toString());
                insert.setString(3, listingId.toString());
                insert.setString(4, listingId.toString());
                insert.setString(5, actorId.toString());
                insert.setString(6, recipientId.toString());
                insert.setTimestamp(7, Timestamp.from(recipient.joinedAt()));
                insert.setString(8, idempotencyKey);
                insert.executeUpdate();
            }
            Request request = queryRequest(connection, "request_id", id.toString(), false);
            sign(connection, request, actorId);
            CommunityAuditWriter.append(connection, community.id(), id, actorId, "STORAGE_REQUESTED",
                    "listing=" + listingId + ";recipient=" + recipientId);
            return result(connection, Status.SUCCESS, request);
        });
    }

    @Override
    public CompletionStage<CommunityStorageResult> approveWithdrawal(UUID actorId, UUID requestId) {
        return decide(actorId, requestId, false);
    }

    @Override
    public CompletionStage<CommunityStorageResult> cancelWithdrawal(UUID actorId, UUID requestId) {
        return decide(actorId, requestId, true);
    }

    private CompletionStage<CommunityStorageResult> decide(UUID actorId, UUID requestId, boolean cancel) {
        if (!enabled) return completed(Status.DISABLED);
        if (actorId == null || requestId == null) return completed(Status.INVALID);
        return async(connection -> {
            Request located = queryRequest(connection, "request_id", requestId.toString(), false);
            if (located == null) return CommunityStorageResult.of(Status.NOT_FOUND);
            Community community = community(connection, located.communityId(), true);
            if (community == null) return CommunityStorageResult.of(Status.NOT_MEMBER);
            Member actor = member(connection, community.id(), actorId);
            if (!governor(actor)) return CommunityStorageResult.of(Status.NOT_ALLOWED);
            Request request = queryRequest(connection, "request_id", requestId.toString(), true);
            if (request.state() != CommunityStorageRequest.State.PENDING) {
                return result(connection, stateStatus(request, Status.INVALID), request);
            }
            if (cancel) {
                finish(connection, request, false, actorId, "STORAGE_REQUEST_CANCELLED");
                return result(connection, Status.CANCELLED, queryRequest(connection, "request_id", requestId.toString(), false));
            }
            Member recipient = member(connection, community.id(), request.recipientId());
            Member proposer = member(connection, community.id(), request.proposerId());
            var signatures = approvals(connection, request);
            if (recipient == null || !recipient.joinedAt().equals(request.recipientJoinedAt())
                    || !governor(proposer) || signatures.size() != approvalCount(connection, request)) {
                finish(connection, request, false, actorId, "STORAGE_REQUEST_INVALIDATED");
                return result(connection, Status.CONDITIONS_CHANGED, queryRequest(connection, "request_id", requestId.toString(), false));
            }
            if (signatures.stream().anyMatch(signature -> signature.actor().equals(actorId))) {
                return result(connection, Status.ALREADY_APPROVED, request);
            }
            MarketListing item = listing(connection, community.id(), request.listingId(), true);
            if (item == null || item.state() != MarketListing.State.ACTIVE
                    || item.goods().filter(CommunityStorageRules::accepts).isEmpty()) {
                finish(connection, request, false, actorId, "STORAGE_REQUEST_INVALIDATED");
                return result(connection, Status.CONDITIONS_CHANGED, queryRequest(connection, "request_id", requestId.toString(), false));
            }
            sign(connection, request, actorId);
            if (approvals(connection, request).size() < CommunityRolePower.REQUIRED_APPROVALS) {
                throw new SQLException("Warehouse signature did not satisfy the independent approval requirement");
            }
            CommunityStorageTransactions.deliver(connection, community.id(), item, recipient.id(), request.id(), actorId,
                    "community-storage:" + request.id(), "STORAGE_WITHDRAWN");
            finish(connection, request, true, actorId, "STORAGE_REQUEST_COMPLETED");
            return result(connection, Status.DELIVERED, queryRequest(connection, "request_id", requestId.toString(), false));
        });
    }

    private Community communityForMember(Connection connection, UUID player, boolean lock) throws SQLException {
        UUID id;
        try (var query = connection.prepareStatement("SELECT community_id FROM rpg_community_members WHERE player_uuid = ?")) {
            query.setString(1, player.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                id = UUID.fromString(row.getString(1));
            }
        }
        Community community = community(connection, id, lock);
        return community != null && member(connection, id, player) != null ? community : null;
    }

    private Community community(Connection connection, UUID id, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement("SELECT id, name, tag FROM rpg_communities"
                + " WHERE id = ? AND dissolved_at IS NULL" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new Community(id, row.getString("name"), row.getString("tag")) : null;
            }
        }
    }

    private Member member(Connection connection, UUID community, UUID player) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT role, joined_at FROM rpg_community_members WHERE community_id = ? AND player_uuid = ?
                """)) {
            query.setString(1, community.toString());
            query.setString(2, player.toString());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? new Member(player, CommunityRole.parse(row.getString("role")).orElseThrow(),
                        row.getTimestamp("joined_at").toInstant()) : null;
            }
        }
    }

    private UUID listingCommunity(Connection connection, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT custody_community_id FROM rpg_market_listings WHERE id = ?")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next() || row.getString(1) == null) return null;
                return UUID.fromString(row.getString(1));
            }
        }
    }

    private MarketListing listing(Connection connection, UUID community, UUID id, boolean lock) throws SQLException {
        try (var query = connection.prepareStatement("SELECT * FROM rpg_market_listings"
                + " WHERE id = ? AND custody_community_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, id.toString());
            query.setString(2, community.toString());
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) return null;
                if (!CommunityStorageTransactions.validSupply(row)) throw new SQLException("Invalid community supply facts: " + id);
                return CommunityStorageTransactions.listing(row);
            }
        }
    }

    private int heldCount(Connection connection, String column, UUID id) throws SQLException {
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM rpg_market_listings WHERE " + column
                + " = ? AND custody_community_id IS NOT NULL AND state IN ('PREPARED','ACTIVE','QUARANTINED')")) {
            query.setString(1, id.toString());
            try (ResultSet row = query.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private Request queryRequest(Connection connection, String column, String value, boolean lock) throws SQLException {
        // The column is one of three internal constants, never player input.
        try (var query = connection.prepareStatement("SELECT * FROM rpg_community_storage_requests WHERE "
                + column + " = ?" + (lock ? " FOR UPDATE" : ""))) {
            query.setString(1, value);
            try (ResultSet row = query.executeQuery()) { return row.next() ? readRequest(row) : null; }
        }
    }

    private static Request readRequest(ResultSet row) throws SQLException {
        Timestamp complete = row.getTimestamp("completed_at");
        return new Request(UUID.fromString(row.getString("request_id")), UUID.fromString(row.getString("community_id")),
                UUID.fromString(row.getString("listing_id")), UUID.fromString(row.getString("proposer_uuid")),
                UUID.fromString(row.getString("recipient_uuid")), row.getTimestamp("recipient_joined_at").toInstant(),
                CommunityStorageRequest.State.valueOf(row.getString("state")), row.getTimestamp("created_at").toInstant(),
                complete == null ? null : complete.toInstant());
    }

    private void sign(Connection connection, Request request, UUID actor) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO rpg_community_approvals
                    (approval_id, community_id, actor_uuid, action, status, requester_uuid, subject, idempotency_key)
                VALUES (?, ?, ?, ?, 'APPROVED', ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, request.communityId().toString());
            insert.setString(3, actor.toString());
            insert.setString(4, ACTION);
            insert.setString(5, request.proposerId().toString());
            insert.setString(6, request.id().toString());
            insert.setString(7, "storage:" + request.id() + ":" + actor);
            insert.executeUpdate();
        }
    }

    private List<CommunityApprovalOutcome.GrantedApproval> approvals(Connection connection, Request request) throws SQLException {
        List<CommunityApprovalOutcome.GrantedApproval> result = new ArrayList<>();
        try (var query = connection.prepareStatement("""
                SELECT a.actor_uuid, m.role FROM rpg_community_approvals a
                JOIN rpg_community_members m ON m.community_id = a.community_id AND m.player_uuid = a.actor_uuid
                WHERE a.community_id = ? AND a.action = ? AND a.subject = ? AND a.status = 'APPROVED'
                    AND m.joined_at <= a.created_at ORDER BY a.created_at, a.actor_uuid
                """)) {
            query.setString(1, request.communityId().toString());
            query.setString(2, ACTION);
            query.setString(3, request.id().toString());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    CommunityRole role = CommunityRole.parse(rows.getString("role")).orElse(null);
                    if (CommunityRolePower.countsAsApproval(role)) result.add(new CommunityApprovalOutcome.GrantedApproval(
                            UUID.fromString(rows.getString("actor_uuid")), role));
                }
            }
        }
        return List.copyOf(result);
    }

    private int approvalCount(Connection connection, Request request) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_community_approvals
                WHERE community_id = ? AND action = ? AND subject = ? AND status = 'APPROVED'
                """)) {
            query.setString(1, request.communityId().toString());
            query.setString(2, ACTION);
            query.setString(3, request.id().toString());
            try (ResultSet row = query.executeQuery()) { row.next(); return row.getInt(1); }
        }
    }

    private void finish(Connection connection, Request request, boolean completed, UUID actor, String transition) throws SQLException {
        try (var update = connection.prepareStatement("""
                UPDATE rpg_community_storage_requests SET state = ?, active_listing_id = NULL, completed_at = CURRENT_TIMESTAMP
                WHERE request_id = ? AND state = 'PENDING'
                """)) {
            update.setString(1, completed ? "COMPLETED" : "CANCELLED");
            update.setString(2, request.id().toString());
            requireOne(update.executeUpdate(), "Finalizing storage request");
        }
        CommunityAuditWriter.append(connection, request.communityId(), request.id(), actor, transition,
                "listing=" + request.listingId() + ";recipient=" + request.recipientId());
    }

    private CommunityStorageRequest view(Connection connection, Request request) throws SQLException {
        return new CommunityStorageRequest(request.id(), request.communityId(), request.listingId(), request.proposerId(),
                request.recipientId(), request.state(), request.createdAt(), Optional.ofNullable(request.completedAt()),
                approvals(connection, request));
    }

    private CommunityStorageResult result(Connection connection, Status status, Request request) throws SQLException {
        return new CommunityStorageResult(status, Optional.of(view(connection, request)));
    }

    private static Status stateStatus(Request request, Status otherwise) {
        return request.state() == CommunityStorageRequest.State.COMPLETED ? Status.DELIVERED
                : request.state() == CommunityStorageRequest.State.CANCELLED ? Status.CANCELLED : otherwise;
    }

    private static boolean governor(Member member) {
        return member != null && CommunityRolePower.countsAsApproval(member.role());
    }

    private static CompletionStage<CommunityStorageResult> completed(Status status) {
        return CompletableFuture.completedFuture(CommunityStorageResult.of(status));
    }

    private static MarketOperationResult market(MarketOperationResult.Status status, MarketListing item) {
        return new MarketOperationResult(status, item, BigDecimal.ZERO);
    }

    private static CompletionStage<MarketOperationResult> completedMarket(MarketOperationResult.Status status, MarketListing item) {
        return CompletableFuture.completedFuture(market(status, item));
    }

    private static void requireOne(int changed, String operation) throws SQLException {
        if (changed != 1) throw new SQLException(operation + " affected " + changed + " rows");
    }

    private <T> CompletionStage<T> async(SqlWork<T> work) {
        return AsyncJdbc.supply(executor, () -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException failure) {
                    try { connection.rollback(); }
                    catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("Community storage transaction failed", failure);
            }
        });
    }

    @FunctionalInterface private interface SqlWork<T> { T apply(Connection connection) throws SQLException; }
    private record Community(UUID id, String name, String tag) {}
    private record Member(UUID id, CommunityRole role, Instant joinedAt) {}
    private record Request(UUID id, UUID communityId, UUID listingId, UUID proposerId, UUID recipientId,
                           Instant recipientJoinedAt, CommunityStorageRequest.State state,
                           Instant createdAt, Instant completedAt) {}
}
