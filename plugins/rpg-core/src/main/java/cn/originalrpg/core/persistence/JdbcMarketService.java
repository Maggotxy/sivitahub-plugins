package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.MarketDelivery;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.model.MarketOperationResult;
import cn.originalrpg.api.service.MarketService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public final class JdbcMarketService implements MarketService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("1000000000000.00");
    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000000000.00");
    private static final BigDecimal MINIMUM_FEE_RATE = new BigDecimal("0.05");
    private static final int MAX_PAYLOAD_LENGTH = 2_000_000;

    private final DataSource dataSource;
    private final Executor executor;
    private final BigDecimal startingBalance;
    private final EquipmentStateLedger lifecycle = new EquipmentStateLedger();

    public JdbcMarketService(DataSource dataSource, Executor executor, BigDecimal startingBalance) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.startingBalance = money(startingBalance);
    }

    @Override
    public CompletionStage<MarketOperationResult> prepare(
            UUID listingId,
            UUID sellerId,
            String itemPayload,
            BigDecimal price,
            BigDecimal feeRate,
            int maximumOpenListings,
            Optional<UUID> itemIdentity
    ) {
        if (!validListingInput(listingId, sellerId, itemPayload, price, feeRate)
                || maximumOpenListings < 1 || maximumOpenListings > 100 || itemIdentity == null) {
            return completed(MarketOperationResult.Status.INVALID, null, BigDecimal.ZERO);
        }
        BigDecimal normalizedPrice = money(price);
        BigDecimal normalizedRate = feeRate.setScale(6, RoundingMode.HALF_UP);
        return async(connection -> {
            loadOrCreateAccount(connection, sellerId);
            lockBalance(connection, sellerId);
            MarketListing existing = selectListing(connection, listingId, true);
            if (existing != null) {
                return new MarketOperationResult(MarketOperationResult.Status.DUPLICATE, existing, BigDecimal.ZERO);
            }
            if (openListingCount(connection, sellerId) >= maximumOpenListings) {
                return new MarketOperationResult(
                        MarketOperationResult.Status.LISTING_LIMIT, null, BigDecimal.ZERO.setScale(2));
            }
            // Phase 2 (C-009): only tradeable lifecycle states may open a new
            // escrow. Bound, sold, salvaged or quarantined gear cannot escape
            // into the market; a missing row adopts the legacy-compatible seed.
            if (itemIdentity.isPresent() && !lifecycle.beginEscrow(connection, itemIdentity.get(), sellerId)) {
                return new MarketOperationResult(
                        MarketOperationResult.Status.ITEM_NOT_TRADEABLE, null, BigDecimal.ZERO.setScale(2));
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_market_listings
                    (id, seller_uuid, item_payload, price, fee_rate, state, item_identity, open_item_identity)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, listingId.toString());
                insert.setString(2, sellerId.toString());
                insert.setString(3, itemPayload);
                insert.setBigDecimal(4, normalizedPrice);
                insert.setBigDecimal(5, normalizedRate);
                insert.setString(6, MarketListing.State.PREPARED.name());
                String identity = itemIdentity.map(UUID::toString).orElse(null);
                insert.setString(7, identity);
                insert.setString(8, identity);
                if (insert.executeUpdate() != 1) {
                    MarketListing replayed = selectListing(connection, listingId, false);
                    if (replayed != null) return new MarketOperationResult(
                            MarketOperationResult.Status.DUPLICATE, replayed, BigDecimal.ZERO);
                    return new MarketOperationResult(
                            MarketOperationResult.Status.DUPLICATE_ITEM, null, BigDecimal.ZERO.setScale(2));
                }
            }
            return new MarketOperationResult(MarketOperationResult.Status.SUCCESS,
                    selectListing(connection, listingId, false), BigDecimal.ZERO);
        });
    }

    @Override
    public CompletionStage<MarketOperationResult> activate(UUID listingId, UUID sellerId) {
        if (listingId == null || sellerId == null) {
            return completed(MarketOperationResult.Status.INVALID, null, BigDecimal.ZERO);
        }
        return async(connection -> {
            MarketListing listing = selectListing(connection, listingId, true);
            if (listing == null) return result(MarketOperationResult.Status.NOT_FOUND, null);
            if (!listing.sellerId().equals(sellerId)) return result(MarketOperationResult.Status.NOT_OWNER, listing);
            if (listing.state() == MarketListing.State.ACTIVE) return result(MarketOperationResult.Status.SUCCESS, listing);
            if (listing.state() != MarketListing.State.PREPARED) return result(MarketOperationResult.Status.NOT_ACTIVE, listing);
            updateListingState(connection, listingId, MarketListing.State.ACTIVE, null);
            return result(MarketOperationResult.Status.SUCCESS, selectListing(connection, listingId, false));
        });
    }

    @Override
    public CompletionStage<MarketOperationResult> quarantinePrepared(UUID listingId, UUID sellerId) {
        if (listingId == null || sellerId == null) {
            return completed(MarketOperationResult.Status.INVALID, null, BigDecimal.ZERO);
        }
        return async(connection -> {
            MarketListing listing = selectListing(connection, listingId, true);
            if (listing == null) return result(MarketOperationResult.Status.NOT_FOUND, null);
            if (!listing.sellerId().equals(sellerId)) return result(MarketOperationResult.Status.NOT_OWNER, listing);
            if (listing.state() == MarketListing.State.QUARANTINED) {
                return result(MarketOperationResult.Status.SUCCESS, listing);
            }
            if (listing.state() != MarketListing.State.PREPARED) {
                return result(MarketOperationResult.Status.NOT_ACTIVE, listing);
            }
            // Phase 2 (C-013): freezing the escrow freezes the identity, so the
            // item cannot be mutated by a paid operation while risk-control holds it.
            Optional<UUID> identity = itemIdentityOf(connection, listingId);
            if (identity.isPresent() && !lifecycle.quarantineEscrow(connection, identity.get())) {
                return notTradeable(listing, BigDecimal.ZERO.setScale(2));
            }
            updateListingState(connection, listingId, MarketListing.State.QUARANTINED, null);
            return result(MarketOperationResult.Status.SUCCESS, selectListing(connection, listingId, false));
        });
    }

    @Override
    public CompletionStage<List<MarketListing>> activeListings(int limit) {
        return activeListings(0, limit);
    }

    @Override
    public CompletionStage<List<MarketListing>> activeListings(int offset, int limit) {
        int boundedOffset = Math.clamp(offset, 0, 1_000_000);
        int bounded = Math.clamp(limit, 1, 100);
        return async(connection -> listings(connection,
                "SELECT * FROM rpg_market_listings WHERE state = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                statement -> {
                    statement.setString(1, MarketListing.State.ACTIVE.name());
                    statement.setInt(2, bounded);
                    statement.setInt(3, boundedOffset);
                }));
    }

    @Override
    public CompletionStage<List<MarketListing>> activeListings(UUID sellerId, int limit) {
        if (sellerId == null) return CompletableFuture.completedFuture(List.of());
        int bounded = Math.clamp(limit, 1, 100);
        return async(connection -> listings(connection, """
                SELECT * FROM rpg_market_listings
                WHERE seller_uuid = ? AND state = ?
                ORDER BY created_at DESC, id LIMIT ?
                """, statement -> {
            statement.setString(1, sellerId.toString());
            statement.setString(2, MarketListing.State.ACTIVE.name());
            statement.setInt(3, bounded);
        }));
    }

    @Override
    public CompletionStage<List<MarketListing>> preparedListings(UUID sellerId) {
        if (sellerId == null) return CompletableFuture.completedFuture(List.of());
        return async(connection -> listings(connection,
                "SELECT * FROM rpg_market_listings WHERE seller_uuid = ? AND state = ? ORDER BY created_at",
                statement -> {
                    statement.setString(1, sellerId.toString());
                    statement.setString(2, MarketListing.State.PREPARED.name());
                }));
    }

    @Override
    public CompletionStage<Optional<MarketListing>> listing(UUID listingId) {
        if (listingId == null) return CompletableFuture.completedFuture(Optional.empty());
        return async(connection -> Optional.ofNullable(selectListing(connection, listingId, false)));
    }

    @Override
    public CompletionStage<MarketOperationResult> purchase(UUID listingId, UUID buyerId, String idempotencyKey) {
        if (listingId == null || buyerId == null || idempotencyKey == null
                || idempotencyKey.isBlank() || idempotencyKey.length() > 96) {
            return completed(MarketOperationResult.Status.INVALID, null, BigDecimal.ZERO);
        }
        return async(connection -> {
            MarketListing listing = selectListing(connection, listingId, true);
            if (listing == null) return result(MarketOperationResult.Status.NOT_FOUND, null);
            BigDecimal fee = money(listing.price().multiply(listing.feeRate()));
            if (ledgerExists(connection, idempotencyKey)) {
                return new MarketOperationResult(MarketOperationResult.Status.DUPLICATE, listing, fee);
            }
            if (listing.state() != MarketListing.State.ACTIVE) {
                return new MarketOperationResult(MarketOperationResult.Status.NOT_ACTIVE, listing, fee);
            }
            if (listing.sellerId().equals(buyerId)) {
                return new MarketOperationResult(MarketOperationResult.Status.OWN_LISTING, listing, fee);
            }

            loadOrCreateAccount(connection, buyerId);
            loadOrCreateAccount(connection, listing.sellerId());
            UUID first = buyerId.toString().compareTo(listing.sellerId().toString()) < 0 ? buyerId : listing.sellerId();
            UUID second = first.equals(buyerId) ? listing.sellerId() : buyerId;
            BigDecimal firstBalance = lockBalance(connection, first);
            BigDecimal secondBalance = lockBalance(connection, second);
            BigDecimal buyerBalance = first.equals(buyerId) ? firstBalance : secondBalance;
            BigDecimal sellerBalance = first.equals(listing.sellerId()) ? firstBalance : secondBalance;
            BigDecimal total = listing.price().add(fee);
            if (buyerBalance.compareTo(total) < 0) {
                return new MarketOperationResult(MarketOperationResult.Status.INSUFFICIENT_FUNDS, listing, fee);
            }
            BigDecimal newSellerBalance = sellerBalance.add(listing.price());
            if (newSellerBalance.compareTo(MAX_BALANCE) > 0) {
                return new MarketOperationResult(MarketOperationResult.Status.BALANCE_LIMIT, listing, fee);
            }
            // Phase 2 (C-009): the escrow row is locked by the listing above, so
            // the lifecycle check is serialized against any concurrent mutation of
            // the same identity. Placed before the first balance write because a
            // refusal must not move money in the same transaction.
            Optional<UUID> identity = itemIdentityOf(connection, listing.id());
            if (identity.isPresent() && !lifecycle.completeSale(connection, identity.get(), buyerId)) {
                return notTradeable(listing, fee);
            }
            updateBalance(connection, buyerId, buyerBalance.subtract(total));
            updateBalance(connection, listing.sellerId(), newSellerBalance);
            insertLedger(connection, idempotencyKey, buyerId, listing.sellerId(), listing.price(), fee,
                    "market-purchase:" + listing.id());
            updateListingState(connection, listing.id(), MarketListing.State.SOLD, buyerId);
            insertDelivery(connection, buyerId, listing.itemPayload(), "market-purchase:" + listing.id());
            return new MarketOperationResult(MarketOperationResult.Status.SUCCESS,
                    selectListing(connection, listing.id(), false), fee);
        });
    }

    @Override
    public CompletionStage<MarketOperationResult> cancel(UUID listingId, UUID sellerId) {
        if (listingId == null || sellerId == null) {
            return completed(MarketOperationResult.Status.INVALID, null, BigDecimal.ZERO);
        }
        return async(connection -> {
            MarketListing listing = selectListing(connection, listingId, true);
            if (listing == null) return result(MarketOperationResult.Status.NOT_FOUND, null);
            if (!listing.sellerId().equals(sellerId)) return result(MarketOperationResult.Status.NOT_OWNER, listing);
            if (listing.state() != MarketListing.State.ACTIVE) return result(MarketOperationResult.Status.NOT_ACTIVE, listing);
            // Phase 2: releasing the escrow returns the identity to the seller so
            // it becomes tradeable again. The item is already in the mailbox lane,
            // so a refusal here would strand gear the seller can still collect;
            // a non-LISTED row therefore means an out-of-band freeze already
            // holds it and the return is a no-op that the delivery still covers.
            Optional<UUID> identity = itemIdentityOf(connection, listing.id());
            if (identity.isPresent()) lifecycle.returnToSeller(connection, identity.get(), sellerId);
            updateListingState(connection, listing.id(), MarketListing.State.CANCELLED, null);
            insertDelivery(connection, sellerId, listing.itemPayload(), "market-cancel:" + listing.id());
            return result(MarketOperationResult.Status.SUCCESS, selectListing(connection, listing.id(), false));
        });
    }

    @Override
    public CompletionStage<Integer> expireListings(Instant createdBefore, int limit) {
        if (createdBefore == null) return CompletableFuture.completedFuture(0);
        int bounded = Math.clamp(limit, 1, 500);
        return async(connection -> {
            List<UUID> candidates = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT id FROM rpg_market_listings
                    WHERE state = ? AND created_at < ?
                    ORDER BY created_at, id LIMIT ?
                    """)) {
                query.setString(1, MarketListing.State.ACTIVE.name());
                query.setTimestamp(2, Timestamp.from(createdBefore));
                query.setInt(3, bounded);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) candidates.add(UUID.fromString(result.getString(1)));
                }
            }
            int expired = 0;
            for (UUID listingId : candidates) {
                MarketListing listing = selectListing(connection, listingId, true);
                if (listing == null || listing.state() != MarketListing.State.ACTIVE
                        || !listing.createdAt().isBefore(createdBefore)) continue;
                if (!transitionListingState(connection, listing.id(), MarketListing.State.ACTIVE,
                        MarketListing.State.EXPIRED, null)) continue;
                // Same rationale as cancel(): expiry always returns the physical
                // item, so the lifecycle release is best-effort and never blocks it.
                Optional<UUID> identity = itemIdentityOf(connection, listing.id());
                if (identity.isPresent()) lifecycle.returnToSeller(connection, identity.get(), listing.sellerId());
                insertDelivery(connection, listing.sellerId(), listing.itemPayload(),
                        "market-expire:" + listing.id());
                expired++;
            }
            return expired;
        });
    }

    @Override
    public CompletionStage<List<MarketDelivery>> pendingDeliveries(UUID playerId, int limit) {
        if (playerId == null) return CompletableFuture.completedFuture(List.of());
        int bounded = Math.clamp(limit, 1, 100);
        return async(connection -> {
            List<MarketDelivery> deliveries = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT id, player_uuid, item_payload, source, created_at
                    FROM rpg_market_deliveries
                    WHERE player_uuid = ? AND state = 'PENDING'
                    ORDER BY created_at LIMIT ?
                    """)) {
                query.setString(1, playerId.toString());
                query.setInt(2, bounded);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        deliveries.add(new MarketDelivery(
                                UUID.fromString(result.getString("id")),
                                UUID.fromString(result.getString("player_uuid")),
                                result.getString("item_payload"),
                                result.getString("source"),
                                result.getTimestamp("created_at").toInstant()
                        ));
                    }
                }
            }
            return List.copyOf(deliveries);
        });
    }

    @Override
    public CompletionStage<Boolean> markDelivered(UUID deliveryId, UUID playerId) {
        if (deliveryId == null || playerId == null) return CompletableFuture.completedFuture(false);
        return async(connection -> {
            String source;
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT source FROM rpg_market_deliveries
                    WHERE id = ? AND player_uuid = ? AND state = 'PENDING' FOR UPDATE
                    """)) {
                query.setString(1, deliveryId.toString());
                query.setString(2, playerId.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) return false;
                    source = result.getString(1);
                }
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE rpg_market_deliveries SET state = 'DELIVERED', delivered_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND player_uuid = ? AND state = 'PENDING'
                    """)) {
                update.setString(1, deliveryId.toString());
                update.setString(2, playerId.toString());
                if (update.executeUpdate() != 1) return false;
            }
            // Phase 2 (C-001): collecting a purchased item ends the custody
            // transfer, so the identity moves SOLD -> UNBOUND under the buyer —
            // escrowed gear was unbound when listed and combat may bind it later.
            // Best-effort by necessity: the physical item is already in the
            // player's inventory when this receipt is written, and refusing
            // confirmation would leave the delivery PENDING for a re-claim.
            // A refusal (frozen or terminal row) therefore leaves the state
            // untouched for staff to see against the SOLD listing.
            if (source != null && source.startsWith("market-purchase:")) {
                Optional<UUID> listingId = parseUuid(source.substring("market-purchase:".length()));
                if (listingId.isPresent()) {
                    Optional<UUID> identity = itemIdentityOf(connection, listingId.get());
                    if (identity.isPresent()) {
                        lifecycle.collectPurchase(connection, identity.get(), playerId);
                    }
                }
            }
            return true;
        });
    }

    private static Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public CompletionStage<Boolean> enqueueDelivery(
            UUID deliveryId, UUID playerId, String itemPayload, String source
    ) {
        if (deliveryId == null || playerId == null || itemPayload == null || itemPayload.isBlank()
                || itemPayload.length() > MAX_PAYLOAD_LENGTH || source == null || source.isBlank()
                || source.length() > 96) {
            return CompletableFuture.completedFuture(false);
        }
        return async(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                    VALUES (?, ?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING
                    """)) {
                insert.setString(1, deliveryId.toString());
                insert.setString(2, playerId.toString());
                insert.setString(3, itemPayload);
                insert.setString(4, source);
                if (insert.executeUpdate() == 1) return true;
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT player_uuid, item_payload, source FROM rpg_market_deliveries WHERE id = ?
                    """)) {
                query.setString(1, deliveryId.toString());
                try (ResultSet result = query.executeQuery()) {
                    return result.next()
                            && playerId.toString().equals(result.getString("player_uuid"))
                            && itemPayload.equals(result.getString("item_payload"))
                            && source.equals(result.getString("source"));
                }
            }
        });
    }

    private boolean validListingInput(UUID listingId, UUID sellerId, String payload, BigDecimal price, BigDecimal feeRate) {
        if (price == null) return false;
        BigDecimal normalizedPrice = money(price);
        return listingId != null && sellerId != null && payload != null && !payload.isBlank()
                && payload.length() <= MAX_PAYLOAD_LENGTH && normalizedPrice.signum() > 0
                && normalizedPrice.compareTo(MAX_PRICE) <= 0 && feeRate != null
                && feeRate.compareTo(MINIMUM_FEE_RATE) >= 0
                && feeRate.compareTo(new BigDecimal("0.50")) <= 0;
    }

    private MarketListing selectListing(Connection connection, UUID id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM rpg_market_listings WHERE id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, id.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? mapListing(result) : null;
            }
        }
    }

    /**
     * Item identity of a listing the caller already holds locked. Legacy listings
     * written before the identity column carried payloads resolve to empty, which
     * every lifecycle gate below treats as "nothing to enforce".
     */
    private Optional<UUID> itemIdentityOf(Connection connection, UUID listingId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT item_identity FROM rpg_market_listings WHERE id = ?")) {
            query.setString(1, listingId.toString());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String identity = result.getString(1);
                if (identity == null || identity.isBlank()) return Optional.empty();
                return Optional.of(UUID.fromString(identity));
            }
        }
    }

    private static MarketOperationResult notTradeable(MarketListing listing, BigDecimal fee) {
        return new MarketOperationResult(MarketOperationResult.Status.ITEM_NOT_TRADEABLE, listing, fee);
    }

    private List<MarketListing> listings(Connection connection, String sql, StatementBinder binder) throws SQLException {
        List<MarketListing> listings = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            binder.bind(query);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) listings.add(mapListing(result));
            }
        }
        return List.copyOf(listings);
    }

    private MarketListing mapListing(ResultSet result) throws SQLException {
        String buyer = result.getString("buyer_uuid");
        Timestamp completed = result.getTimestamp("completed_at");
        return new MarketListing(
                UUID.fromString(result.getString("id")),
                UUID.fromString(result.getString("seller_uuid")),
                result.getString("item_payload"),
                money(result.getBigDecimal("price")),
                result.getBigDecimal("fee_rate").setScale(6, RoundingMode.HALF_UP),
                MarketListing.State.valueOf(result.getString("state")),
                buyer == null ? null : UUID.fromString(buyer),
                result.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant()
        );
    }

    private void updateListingState(Connection connection, UUID id, MarketListing.State state, UUID buyer) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_listings SET state = ?, buyer_uuid = ?,
                    open_item_identity = CASE WHEN ? IN ('PREPARED', 'ACTIVE')
                                              THEN item_identity ELSE NULL END,
                    completed_at = CASE WHEN ? IN ('SOLD', 'CANCELLED', 'EXPIRED', 'QUARANTINED')
                                        THEN CURRENT_TIMESTAMP ELSE completed_at END
                WHERE id = ?
                """)) {
            update.setString(1, state.name());
            update.setString(2, buyer == null ? null : buyer.toString());
            update.setString(3, state.name());
            update.setString(4, state.name());
            update.setString(5, id.toString());
            update.executeUpdate();
        }
    }

    private boolean transitionListingState(
            Connection connection,
            UUID id,
            MarketListing.State expected,
            MarketListing.State state,
            UUID buyer
    ) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE rpg_market_listings SET state = ?, buyer_uuid = ?, open_item_identity = NULL,
                    completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND state = ?
                """)) {
            update.setString(1, state.name());
            update.setString(2, buyer == null ? null : buyer.toString());
            update.setString(3, id.toString());
            update.setString(4, expected.name());
            return update.executeUpdate() == 1;
        }
    }

    private int openListingCount(Connection connection, UUID sellerId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT COUNT(*) FROM rpg_market_listings
                WHERE seller_uuid = ? AND state IN ('PREPARED', 'ACTIVE')
                """)) {
            query.setString(1, sellerId.toString());
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private void insertDelivery(Connection connection, UUID playerId, String payload, String source) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_market_deliveries (id, player_uuid, item_payload, source, state)
                VALUES (?, ?, ?, ?, 'PENDING')
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, playerId.toString());
            insert.setString(3, payload);
            insert.setString(4, source);
            insert.executeUpdate();
        }
    }

    private boolean ledgerExists(Connection connection, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM rpg_ledger WHERE idempotency_key = ?")) {
            query.setString(1, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        }
    }

    private void insertLedger(
            Connection connection, String key, UUID sender, UUID receiver,
            BigDecimal amount, BigDecimal fee, String reason
    ) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO rpg_ledger (id, idempotency_key, sender_uuid, receiver_uuid, amount, fee, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, UUID.randomUUID().toString());
            insert.setString(2, key);
            insert.setString(3, sender.toString());
            insert.setString(4, receiver.toString());
            insert.setBigDecimal(5, money(amount));
            insert.setBigDecimal(6, money(fee));
            insert.setString(7, reason);
            insert.executeUpdate();
        }
    }

    private BigDecimal loadOrCreateAccount(Connection connection, UUID playerId) throws SQLException {
        BigDecimal existing = selectBalance(connection, playerId, false);
        if (existing != null) return existing;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO rpg_accounts (player_uuid, balance) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            insert.setString(1, playerId.toString());
            insert.setBigDecimal(2, startingBalance);
            insert.executeUpdate();
        }
        return selectBalance(connection, playerId, false);
    }

    private BigDecimal lockBalance(Connection connection, UUID playerId) throws SQLException {
        return selectBalance(connection, playerId, true);
    }

    private BigDecimal selectBalance(Connection connection, UUID playerId, boolean lock) throws SQLException {
        String sql = "SELECT balance FROM rpg_accounts WHERE player_uuid = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, playerId.toString());
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? money(result.getBigDecimal(1)) : null;
            }
        }
    }

    private void updateBalance(Connection connection, UUID playerId, BigDecimal balance) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE rpg_accounts SET balance = ?, updated_at = CURRENT_TIMESTAMP WHERE player_uuid = ?")) {
            update.setBigDecimal(1, money(balance));
            update.setString(2, playerId.toString());
            update.executeUpdate();
        }
    }

    private static MarketOperationResult result(MarketOperationResult.Status status, MarketListing listing) {
        return new MarketOperationResult(status, listing, BigDecimal.ZERO.setScale(2));
    }

    private static CompletionStage<MarketOperationResult> completed(
            MarketOperationResult.Status status, MarketListing listing, BigDecimal fee
    ) {
        return CompletableFuture.completedFuture(new MarketOperationResult(status, listing, fee));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private <T> CompletionStage<T> async(SqlOperation<T> operation) {
        return AsyncJdbc.supply(executor, () -> inTransaction(operation));
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
                throw new IllegalStateException("Market transaction failed", exception);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open market transaction", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
