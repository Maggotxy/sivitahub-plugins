package cn.originalrpg.api.service;


import cn.originalrpg.api.model.BuyOrderOperationResult;
import cn.originalrpg.api.model.MarketOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Funded buy requests and maker-supplied crafting commissions in the single
 * market ledger. The buyer reserves the full price budget when placing an order.
 * A fill consumes an existing ACTIVE sell listing, advances its equipment
 * identity, burns the appropriate fee, and creates the buyer's mailbox delivery
 * in one core transaction. There is deliberately no raw-payload fill API.
 */
public interface MarketBuyOrderService {
    default CompletionStage<BuyOrderOperationResult> place(
            UUID orderId, UUID buyerId, String matcher, long price, int quantity, String key) {
        return place(orderId, buyerId, matcher, price, quantity, MarketOrder.Kind.BUY, key);
    }

    CompletionStage<BuyOrderOperationResult> place(
            UUID orderId, UUID buyerId, String matcher, long price, int quantity,
            MarketOrder.Kind kind, String idempotencyKey);

    /**
     * Transfers the whole listing stack. Partial fills of an order are allowed;
     * splitting a serialized escrow stack is not. The caller owns the listing
     * and explicitly accepts the order's tax-inclusive price instead of its ask.
     */
    CompletionStage<BuyOrderOperationResult> fillListing(
            UUID orderId, UUID sellerId, UUID listingId, String idempotencyKey);

    CompletionStage<BuyOrderOperationResult> cancel(UUID orderId, UUID buyerId, String idempotencyKey);

    /** Returns funded, unfilled quantities through receipt-backed reversals. */
    CompletionStage<Integer> expire(Instant createdBefore, int limit);

    CompletionStage<Optional<MarketOrder>> order(UUID orderId);

    CompletionStage<List<MarketOrder>> activeOrders(String matcher, int limit);

    CompletionStage<List<MarketOrder>> activeOrders(String matcher, MarketOrder.Kind kind, int limit);

    CompletionStage<List<MarketOrder>> ownOrders(UUID buyerId, int limit);
}
