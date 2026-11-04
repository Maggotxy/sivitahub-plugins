package cn.originalrpg.api.service;

import cn.originalrpg.api.session.SessionActor;

import cn.originalrpg.api.model.MarketDelivery;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.model.MarketOperationResult;
import cn.originalrpg.api.model.MarketGoods;
import cn.originalrpg.api.model.MarketDeliveryClaim;
import cn.originalrpg.api.model.MarketDeliveryAuthorization;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MarketService {
    @SessionActor(argument = 1)
    default CompletionStage<MarketOperationResult> prepare(
            UUID listingId,
            UUID sellerId,
            String itemPayload,
            BigDecimal price,
            BigDecimal feeRate,
            int maximumOpenListings
    ) {
        return prepare(listingId, sellerId, itemPayload, price, feeRate, maximumOpenListings, Optional.empty());
    }

    @SessionActor(argument = 1)
    default CompletionStage<MarketOperationResult> prepare(
            UUID listingId,
            UUID sellerId,
            String itemPayload,
            BigDecimal price,
            BigDecimal feeRate,
            int maximumOpenListings,
            Optional<UUID> itemIdentity
    ) {
        return prepare(listingId, sellerId, itemPayload, price, feeRate,
                maximumOpenListings, itemIdentity, Optional.empty());
    }

    /**
     * Persists matching facts alongside the item escrow. Empty facts keep an
     * item sellable through ordinary listings but exclude it from order matching.
     * Call from a trusted adapter after checking the actual item on the Paper
     * thread. Never derive these facts from player chat or translated item names.
     */
    @SessionActor(argument = 1)
    CompletionStage<MarketOperationResult> prepare(
            UUID listingId, UUID sellerId, String itemPayload, BigDecimal price,
            BigDecimal feeRate, int maximumOpenListings, Optional<UUID> itemIdentity,
            Optional<MarketGoods> goods);

    @SessionActor(argument = 1)
    CompletionStage<MarketOperationResult> activate(UUID listingId, UUID sellerId);

    @SessionActor(argument = 1)
    CompletionStage<MarketOperationResult> quarantinePrepared(UUID listingId, UUID sellerId);

    CompletionStage<List<MarketListing>> activeListings(int limit);

    /**
     * Returns one stable newest-first page without loading the whole market into a Paper shard.
     */
    CompletionStage<List<MarketListing>> activeListings(int offset, int limit);

    /**
     * Returns the seller's currently active escrow listings. The database remains authoritative.
     */
    CompletionStage<List<MarketListing>> activeListings(UUID sellerId, int limit);

    CompletionStage<List<MarketListing>> preparedListings(UUID sellerId);

    CompletionStage<Optional<MarketListing>> listing(UUID listingId);

    @SessionActor(argument = 1)
    CompletionStage<MarketOperationResult> purchase(UUID listingId, UUID buyerId, String idempotencyKey);

    @SessionActor(argument = 1)
    CompletionStage<MarketOperationResult> cancel(UUID listingId, UUID sellerId);

    /**
     * Atomically expires a bounded batch and creates exactly one durable return delivery per listing.
     * Concurrent shards may call this method; conditional state transitions prevent duplicate returns.
     */
    CompletionStage<Integer> expireListings(Instant createdBefore, int limit);

    CompletionStage<List<MarketDelivery>> pendingDeliveries(UUID playerId, int limit);

    /**
     * Reserves an existing permitted mailbox row for this player action. Call
     * from the authenticated actor context; JDBC executes asynchronously and
     * remains the authority across nodes. Retry an uncertain reservation with the same token.
     * A reservation may expire only before physical staging has been authorized.
     * The returned payload must not enter an inventory before authorizeDelivery.
     */
    @SessionActor(argument = 1)
    default CompletionStage<MarketDeliveryClaim> claimDelivery(UUID deliveryId, UUID playerId, UUID claimToken) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Mailbox claim protocol is unavailable"));
    }

    /**
     * Grants one physical admission by conditionally changing CLAIMED to STAGING.
     * The original actor/session and claim token must still own the reservation.
     * Only AUTHORIZED permits the receiving callback to insert an item on the
     * Paper thread. Persist both delivery ID and claim token on that item, save
     * player state, then acknowledgeDelivery. Replays return INDETERMINATE and
     * must inspect durable physical receipts; an expired STAGING row is never
     * automatically reissued, because a timeout cannot prove absence of an item.
     */
    @SessionActor(argument = 1)
    default CompletionStage<MarketDeliveryAuthorization> authorizeDelivery(UUID deliveryId, UUID playerId, UUID claimToken) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("Mailbox claim protocol is unavailable"));
    }

    /**
     * Releases only an unstarted CLAIMED reservation owned by this actor/token.
     * This cannot undo STAGING. Use after inventory capacity or payload validation
     * fails, before requesting physical admission; no physical item may exist.
     */
    @SessionActor(argument = 1)
    default CompletionStage<Boolean> releaseDeliveryClaim(UUID deliveryId, UUID playerId, UUID claimToken) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }

    /**
     * Confirms a unique saved physical receipt. The current authenticated actor
     * may recover a prior session's receipt using its exact delivery/token pair.
     * True means this same pair is durably DELIVERED, including a lost-response
     * replay. False or failure requires both PDC markers to remain frozen. The
     * adapter must scan inventory and cursor and refuse malformed/duplicate
     * receipts; this database operation does not inspect or transfer playerdata.
     */
    @SessionActor(argument = 1)
    default CompletionStage<Boolean> acknowledgeDelivery(UUID deliveryId, UUID playerId, UUID claimToken) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }

    /**
     * Legacy recovery only: confirms a permitted delivery whose claim token is
     * NULL. New physical deliveries must use the claim/authorize/token protocol.
     * Repeating a committed acknowledgement returns true. Unknown, quarantined,
     * legacy paid-material, or differently owned deliveries return false; callers
     * must retain the physical delivery marker until this method returns true.
     */
    @SessionActor(argument = 1)
    CompletionStage<Boolean> markDelivered(UUID deliveryId, UUID playerId);

    CompletionStage<Boolean> enqueueDelivery(
            UUID deliveryId,
            UUID playerId,
            String itemPayload,
            String source
    );
}
