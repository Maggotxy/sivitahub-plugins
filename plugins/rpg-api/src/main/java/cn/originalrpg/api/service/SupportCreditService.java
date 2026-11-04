package cn.originalrpg.api.service;

import cn.originalrpg.api.model.SignedSupportReceipt;
import cn.originalrpg.api.model.SupportCosmetic;
import cn.originalrpg.api.model.SupportCreditResult;
import cn.originalrpg.api.model.SupportCreditStatus;
import cn.originalrpg.api.model.SupportPaymentResult;
import cn.originalrpg.api.model.SupportProduct;
import cn.originalrpg.api.model.SupportPurchaseResult;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The physically isolated support-credit authority required by constitution C-012.
 * New issuance requires a trusted bridge's signed order, and every spend creates
 * a reviewed cosmetic entitlement in the same core transaction. No method grants
 * Gold, Merit, Starlight, membership discounts, items or gameplay power.
 */
public interface SupportCreditService {
    CompletionStage<Long> balance(UUID playerId);

    /** Returns configured cosmetic prices, never caller-supplied arbitrary targets. An empty catalog is unavailable. */
    default List<SupportProduct> catalog() { return List.of(); }

    /** Read-only status; implementers must not derive this balance from an earnable-currency account. */
    default CompletionStage<SupportCreditStatus> status(UUID playerId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Verified support credit is unavailable"));
    }

    /** Persistent ownership is authoritative; clients may cache it only for presentation. */
    default CompletionStage<Set<SupportCosmetic>> entitlements(UUID playerId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Support entitlements are unavailable"));
    }

    /**
     * Imports a provider-neutral, Ed25519-signed settled-order receipt. This trusted
     * service entry supports offline recipients and therefore is not a player-session
     * actor mutation. Verification, unique order identity, quota, ledger and credit
     * acceptance are enforced by the core provider, not by a command caller.
     */
    default CompletionStage<SupportPaymentResult> acceptReceipt(SignedSupportReceipt signed) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Signed support receipts are unavailable"));
    }

    /** Reads the authoritative receipt-admission gate; a disabled deployment policy always returns false. */
    default CompletionStage<Boolean> paymentAcceptanceEnabled() {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Trusted operator control for the existing persistent payment mechanism.
     * Disabling waits for already admitted transactions before returning: those
     * transactions may commit, but new imports on any node then observe disabled.
     * This cannot override a disabled deployment policy. The actor is audit text,
     * not an authentication credential; command adapters must restrict operators.
     */
    default CompletionStage<Boolean> setPaymentAcceptanceEnabled(boolean enabled, String actor) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Support payment control is unavailable"));
    }

    /**
     * Atomically debits the configured price and persists one account-bound cosmetic.
     * Expected price protects a stale confirmation; operation identity and ownership
     * both prevent duplicate charging after a lost response. No item payload is accepted.
     */
    default CompletionStage<SupportPurchaseResult> purchaseCosmetic(
            UUID playerId, SupportCosmetic cosmetic, long expectedPrice, UUID operationId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Support purchases are unavailable"));
    }

    /** Legacy unsigned issuance is retained only for binary compatibility and must fail closed. */
    @Deprecated(forRemoval = false)
    CompletionStage<SupportCreditResult> grant(UUID playerId, long amount, String idempotencyKey, String reason);

    /** Legacy arbitrary spending cannot establish an entitlement and must fail closed, including COSMETIC targets. */
    @Deprecated(forRemoval = false)
    CompletionStage<SupportCreditResult> spend(
            UUID playerId, long amount, SupportCreditTarget target, String idempotencyKey, String reason);

    /** Historical classification only; it never authorizes a spend or bypasses the reviewed catalog. */
    @Deprecated(forRemoval = false)
    enum SupportCreditTarget {
        COSMETIC(true), CONVENIENCE(true), EQUIPMENT(false), MATERIAL(false), FORGE_CONSUMABLE(false),
        EXPERIENCE(false), ENERGY(false), DROP_RATE(false), SEASON_TUNING(false), COMBAT_POWER(false);
        private final boolean allowed;
        SupportCreditTarget(boolean allowed) { this.allowed = allowed; }
        public boolean allowedByC012() { return allowed; }
    }
}
