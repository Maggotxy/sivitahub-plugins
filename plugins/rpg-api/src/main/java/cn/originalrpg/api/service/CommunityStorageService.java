package cn.originalrpg.api.service;

import cn.originalrpg.api.model.CommunityStorageResult;
import cn.originalrpg.api.model.CommunityStorageSnapshot;
import cn.originalrpg.api.model.MarketGoods;
import cn.originalrpg.api.model.MarketListing;
import cn.originalrpg.api.model.MarketOperationResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Core policy for shared construction supplies held by the existing market escrow.
 * Only pristine, allowlisted vanilla materials enter this scope. Player commands cannot
 * supply payloads or matching facts; a trusted Paper adapter captures them on the main thread.
 *
 * <p>Prepare/activate use the same durable inventory-marker and removal-receipt protocol as
 * ordinary listings. Recovery may finish accepted custody while new actions are disabled.
 * Two distinct current governors approve an immutable recipient. Delivery is created in
 * the existing market mailbox, atomically with closure of the original escrow and its audit.</p>
 */
public interface CommunityStorageService {
    boolean enabled();

    CompletionStage<Optional<CommunityStorageSnapshot>> snapshot(UUID playerId);

    CompletionStage<MarketOperationResult> prepare(UUID listingId, UUID donorId, String itemPayload, MarketGoods goods);

    /** Call only after the accepted item was removed and its removal receipt saved to player data. */
    CompletionStage<MarketOperationResult> activate(UUID listingId, UUID donorId);

    /** Missing both the physical marker and removal receipt freezes custody; it never manufactures a refund. */
    CompletionStage<MarketOperationResult> quarantinePrepared(UUID listingId, UUID donorId);

    CompletionStage<List<MarketListing>> preparedDeposits(UUID donorId);

    CompletionStage<CommunityStorageResult> requestWithdrawal(UUID actorId, UUID listingId, UUID recipientId,
                                                             String idempotencyKey);

    CompletionStage<CommunityStorageResult> approveWithdrawal(UUID actorId, UUID requestId);

    CompletionStage<CommunityStorageResult> cancelWithdrawal(UUID actorId, UUID requestId);
}
