package cn.originalrpg.api.service;

import cn.originalrpg.api.model.MarketDelivery;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.model.MarketOperationResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MarketService {
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

    CompletionStage<MarketOperationResult> prepare(
            UUID listingId,
            UUID sellerId,
            String itemPayload,
            BigDecimal price,
            BigDecimal feeRate,
            int maximumOpenListings,
            Optional<UUID> itemIdentity
    );

    CompletionStage<MarketOperationResult> activate(UUID listingId, UUID sellerId);

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

    CompletionStage<MarketOperationResult> purchase(UUID listingId, UUID buyerId, String idempotencyKey);

    CompletionStage<MarketOperationResult> cancel(UUID listingId, UUID sellerId);

    /**
     * Atomically expires a bounded batch and creates exactly one durable return delivery per listing.
     * Concurrent shards may call this method; conditional state transitions prevent duplicate returns.
     */
    CompletionStage<Integer> expireListings(Instant createdBefore, int limit);

    CompletionStage<List<MarketDelivery>> pendingDeliveries(UUID playerId, int limit);

    CompletionStage<Boolean> markDelivered(UUID deliveryId, UUID playerId);

    CompletionStage<Boolean> enqueueDelivery(
            UUID deliveryId,
            UUID playerId,
            String itemPayload,
            String source
    );
}
