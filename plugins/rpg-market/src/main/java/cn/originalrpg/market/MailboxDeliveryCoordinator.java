package cn.originalrpg.market;

import cn.originalrpg.api.model.MarketDelivery;
import cn.originalrpg.api.model.MarketDeliveryAuthorization;
import cn.originalrpg.api.model.MarketDeliveryClaim;
import cn.originalrpg.api.service.MarketService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The actual mailbox callback chain, independent of Bukkit data classes so its
 * crash/reorder behavior can be tested. Every inventory callback executes only
 * through the supplied Paper-thread executor. JDBC remains in MarketService.
 */
final class MailboxDeliveryCoordinator<T> {
    enum Outcome { DELIVERED, INVENTORY_FULL, INVALID_PAYLOAD, BLOCKED, ABANDONED, ERROR }
    enum ReceiptKind { NONE, LEGACY, TOKEN, AMBIGUOUS }
    record Receipt(ReceiptKind kind, UUID token) {
        Receipt { if (kind == ReceiptKind.TOKEN && token == null) throw new IllegalArgumentException("Receipt token is required"); }
        static Receipt none() { return new Receipt(ReceiptKind.NONE, null); }
    }
    record Prepared<T>(T item, Outcome rejection) {
        Prepared { if ((item == null) == (rejection == null)) throw new IllegalArgumentException("Prepared item or rejection is required"); }
    }
    interface Inventory<T> {
        UUID playerId();
        boolean current();
        Receipt receipt(UUID deliveryId);
        Prepared<T> prepare(MarketDelivery delivery, UUID claimToken);
        /** True means the item and both markers were saved; an exception may mean physical state was written. */
        boolean stage(T prepared);
        /** Re-scan and save an existing receipt before asking SQL to acknowledge it. */
        boolean persistReceipt(UUID deliveryId, UUID claimToken);
        /** Re-scan the exact receipt and clear both markers only after durable acknowledgement. */
        boolean thaw(UUID deliveryId, UUID claimToken);
    }

    private final MarketService market;
    private final Executor main;
    MailboxDeliveryCoordinator(MarketService market, Executor main) { this.market = market; this.main = main; }

    CompletionStage<Outcome> deliver(MarketDelivery delivery, Inventory<T> inventory) {
        Flow flow = new Flow(delivery.id(), inventory);
        flow.onMain(() -> flow.start(delivery));
        return flow.result;
    }

    CompletionStage<Outcome> recover(UUID deliveryId, Inventory<T> inventory) {
        Flow flow = new Flow(deliveryId, inventory);
        flow.onMain(() -> {
            if (!inventory.current()) { flow.finish(Outcome.ABANDONED); return; }
            if (!flow.recoverExisting()) flow.finish(Outcome.BLOCKED);
        });
        return flow.result;
    }

    private final class Flow {
        private final UUID deliveryId;
        private final Inventory<T> inventory;
        private final CompletableFuture<Outcome> result = new CompletableFuture<>();
        Flow(UUID deliveryId, Inventory<T> inventory) { this.deliveryId = deliveryId; this.inventory = inventory; }

        void start(MarketDelivery listed) {
            if (!inventory.current()) { finish(Outcome.ABANDONED); return; }
            if (!listed.playerId().equals(inventory.playerId())) { finish(Outcome.BLOCKED); return; }
            if (recoverExisting()) return;
            UUID token = UUID.randomUUID();
            await(() -> market.claimDelivery(deliveryId, inventory.playerId(), token), (claim, failure) -> {
                if (failure != null || claim == null) { finish(Outcome.ERROR); return; }
                if (!inventory.current()) { finish(Outcome.ABANDONED); return; }
                if (claim.status() != MarketDeliveryClaim.Status.CLAIMED
                        || !deliveryId.equals(claim.deliveryId()) || !token.equals(claim.claimToken())
                        || !inventory.playerId().equals(claim.delivery().playerId())) {
                    if (!recoverExisting()) finish(Outcome.BLOCKED);
                    return;
                }
                // Recheck after the asynchronous claim; no cached absence proves
                // that a prior login/recovery callback did not save a receipt.
                if (recoverExisting()) return;
                Prepared<T> prepared = inventory.prepare(claim.delivery(), token);
                if (prepared.rejection() != null) {
                    await(() -> market.releaseDeliveryClaim(deliveryId, inventory.playerId(), token),
                            (released, releaseFailure) -> finish(prepared.rejection()));
                    return;
                }
                await(() -> market.authorizeDelivery(deliveryId, inventory.playerId(), token), (authorization, error) -> {
                    if (error != null) { finish(Outcome.BLOCKED); return; }
                    if (!inventory.current()) { finish(Outcome.ABANDONED); return; }
                    if (authorization != MarketDeliveryAuthorization.AUTHORIZED) {
                        if (!recoverExisting()) finish(Outcome.BLOCKED);
                        return;
                    }
                    // AUTHORIZED is deliberately not replayable. STAGING retains
                    // this token forever until a unique physical receipt proves delivery.
                    Receipt receipt = inventory.receipt(deliveryId);
                    if (receipt.kind() != ReceiptKind.NONE) {
                        if (receipt.kind() == ReceiptKind.TOKEN && token.equals(receipt.token())) acknowledge(token);
                        else finish(Outcome.BLOCKED);
                        return;
                    }
                    if (!inventory.stage(prepared.item())) { finish(Outcome.BLOCKED); return; }
                    acknowledge(token, true);
                });
            });
        }

        boolean recoverExisting() {
            Receipt receipt = inventory.receipt(deliveryId);
            return switch (receipt.kind()) {
                case NONE -> false;
                case AMBIGUOUS -> { finish(Outcome.BLOCKED); yield true; }
                case LEGACY -> { acknowledge(null); yield true; }
                case TOKEN -> { acknowledge(receipt.token()); yield true; }
            };
        }

        void acknowledge(UUID token) {
            acknowledge(token, false);
        }

        void acknowledge(UUID token, boolean alreadySaved) {
            if (!inventory.current()) { finish(Outcome.ABANDONED); return; }
            if (!alreadySaved && !inventory.persistReceipt(deliveryId, token)) { finish(Outcome.BLOCKED); return; }
            await(() -> token == null ? market.markDelivered(deliveryId, inventory.playerId())
                    : market.acknowledgeDelivery(deliveryId, inventory.playerId(), token), (confirmed, error) -> {
                if (error != null || !Boolean.TRUE.equals(confirmed)) { finish(Outcome.BLOCKED); return; }
                if (!inventory.current()) { finish(Outcome.ABANDONED); return; }
                finish(inventory.thaw(deliveryId, token) ? Outcome.DELIVERED : Outcome.BLOCKED);
            });
        }

        <V> void await(Supplier<CompletionStage<V>> operation, BiConsumer<V, Throwable> next) {
            try { operation.get().whenComplete((value, failure) -> onMain(() -> next.accept(value, failure))); }
            catch (RuntimeException failure) { finish(Outcome.ERROR); }
        }

        void onMain(Runnable action) {
            try {
                main.execute(() -> {
                    if (result.isDone()) return;
                    try { action.run(); }
                    catch (RuntimeException failure) { finish(Outcome.ERROR); }
                });
            } catch (RuntimeException unloading) { finish(Outcome.ABANDONED); }
        }
        void finish(Outcome outcome) { result.complete(outcome); }
    }
}
