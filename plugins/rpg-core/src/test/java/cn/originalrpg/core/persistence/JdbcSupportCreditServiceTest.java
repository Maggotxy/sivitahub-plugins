package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SignedSupportReceipt;
import cn.originalrpg.api.model.SupportCosmetic;
import cn.originalrpg.api.model.SupportCreditResult;
import cn.originalrpg.api.model.SupportPaymentResult;
import cn.originalrpg.api.model.SupportPurchaseResult;
import cn.originalrpg.api.model.SupportReceipt;
import cn.originalrpg.api.service.SupportCreditService;
import cn.originalrpg.api.support.SupportReceiptCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

/** Real transactions, production DDL, cryptographic authorization, contention and commit-response failures. */
final class JdbcSupportCreditServiceTest {
    private SupportCreditFixture fixture;
    private JdbcSupportCreditService service;
    @BeforeEach void setUp() throws Exception { fixture = new SupportCreditFixture(); service = fixture.service; }
    @AfterEach void tearDown() throws Exception { fixture.close(); }

    @Test void grantThenSpendMovesBalanceAndWritesOneAuditRowPerMovement() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals(SupportPaymentResult.Status.SUCCESS, await(service.acceptReceipt(fixture.signed(player, 500, "order-1"))).status());
        UUID operation = UUID.randomUUID();
        var bought = await(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation));
        assertEquals(SupportPurchaseResult.Status.SUCCESS, bought.status());
        assertEquals(120, bought.charged());
        assertEquals(380, await(service.balance(player)));
        assertEquals(Set.of(SupportCosmetic.PRISMATIC_AURA), await(service.entitlements(player)));
        assertEquals(1, fixture.count("rpg_support_payment_orders"));
        assertEquals(1, fixture.count("rpg_support_cosmetic_purchases"));
        assertEquals(1, fixture.count("rpg_support_cosmetic_entitlements"));
        assertEquals(2, fixture.count("rpg_support_credit_ledger"));
        for (String table : new String[]{"rpg_accounts", "rpg_ledger", "rpg_merit_ledger", "rpg_credit_ledger",
                "rpg_commerce_accounts", "rpg_payment_ledger", "rpg_earned_membership_grants", "rpg_profiles", "rpg_market_deliveries"}) {
            assertEquals(0, fixture.count(table), "Support flow must not write " + table);
        }
    }

    @Test void replayingTheSameIdempotencyKeyNeverCreditsTwice() throws Exception {
        UUID player = UUID.randomUUID();
        SignedSupportReceipt signed = fixture.signed(player, 300, "replay-order");
        assertEquals(SupportPaymentResult.Status.SUCCESS, await(service.acceptReceipt(signed)).status());
        fixture.clock.set(fixture.clock.instant().plus(Duration.ofDays(10)));
        var result = await(fixture.service(fixture.dataSource).acceptReceipt(signed));
        assertEquals(SupportPaymentResult.Status.DUPLICATE, result.status(), "Accepted orders remain replayable after receipt expiry");
        assertEquals(0, result.credited());
        assertEquals(0, result.playerWeekIssued(), "Replay cannot consume the new UTC week's quota");
        assertEquals(300, await(service.balance(player)));
        assertEquals(1, fixture.count("rpg_support_credit_ledger"));
    }

    @Test void forbiddenTargetIsRejectedBeforeAnyLedgerWrite() throws Exception {
        UUID player = UUID.randomUUID();
        for (var target : SupportCreditService.SupportCreditTarget.values()) {
            var result = await(service.spend(player, 10, target, "arbitrary-target", "not-a-catalog-purchase"));
            assertEquals(target.allowedByC012() ? SupportCreditResult.Status.LEGACY_DISABLED
                    : SupportCreditResult.Status.CROSS_CURRENCY_FORBIDDEN, result.status());
        }
        assertEquals(SupportCreditResult.Status.LEGACY_DISABLED, await(service.grant(player, 900, "unsigned-grant", "ops")).status());
        assertEquals(0, fixture.count("rpg_support_credit_accounts"));
        assertEquals(0, fixture.count("rpg_support_credit_ledger"));
    }

    @Test void unsignedOrMisconfiguredConstructionCannotIssueCredit() throws Exception {
        UUID player = UUID.randomUUID();
        var disabled = new JdbcSupportCreditService(fixture.dataSource, fixture.executor);
        assertEquals(SupportPaymentResult.Status.DISABLED, await(disabled.acceptReceipt(fixture.signed(player, 100, "disabled"))).status());
        assertTrue(disabled.catalog().isEmpty());
        assertEquals(SupportPurchaseResult.Status.DISABLED,
                await(disabled.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, UUID.randomUUID())).status());
        assertEquals(0, fixture.count("rpg_support_payment_orders"));
    }

    @Test void tamperingAnyBeneficiaryOrAmountDoesNotTouchTheLedger() throws Exception {
        UUID player = UUID.randomUUID();
        var signed = fixture.signed(player, 500, "signed-order");
        SupportReceipt altered = fixture.receipt(UUID.randomUUID(), 900, "signed-order");
        var tampered = new SignedSupportReceipt(SupportReceiptCodec.encode(altered), signed.signature());
        assertEquals(SupportPaymentResult.Status.INVALID_SIGNATURE, await(service.acceptReceipt(tampered)).status());
        byte[] signature = Base64.getUrlDecoder().decode(signed.signature()); signature[8] ^= 1;
        assertEquals(SupportPaymentResult.Status.INVALID_SIGNATURE, await(service.acceptReceipt(new SignedSupportReceipt(
                signed.payload(), Base64.getUrlEncoder().withoutPadding().encodeToString(signature)))).status());
        assertEquals(0, fixture.count("rpg_support_credit_ledger"));
        assertEquals(0, fixture.count("rpg_support_credit_limits"));
    }

    @Test void wrongAudienceAndUnknownKeyCannotBeImportedEvenWithAValidSignature() throws Exception {
        var base = fixture.receipt(UUID.randomUUID(), 100, "bad-context");
        assertEquals(SupportPaymentResult.Status.WRONG_AUDIENCE, await(service.acceptReceipt(fixture.sign(new SupportReceipt(
                1, base.keyId(), "different-live-world", base.orderId(), base.playerId(), base.creditUnits(),
                base.issuedAt(), base.expiresAt(), base.currency())))).status());
        assertEquals(SupportPaymentResult.Status.UNKNOWN_KEY, await(service.acceptReceipt(fixture.sign(new SupportReceipt(
                1, "removed-key", base.audience(), base.orderId(), base.playerId(), base.creditUnits(),
                base.issuedAt(), base.expiresAt(), base.currency())))).status());
        assertEquals(0, fixture.count("rpg_support_payment_orders"));
    }

    @Test void timeBoundsRejectExpiredFutureAndOverlongNewOrders() throws Exception {
        UUID player = UUID.randomUUID(); Instant now = fixture.clock.instant();
        assertEquals(SupportPaymentResult.Status.EXPIRED, await(service.acceptReceipt(fixture.sign(new SupportReceipt(
                1, "test-key", SupportCreditFixture.AUDIENCE, "expired", player, 100, now.minusSeconds(60), now, SupportReceipt.CURRENCY)))).status());
        assertEquals(SupportPaymentResult.Status.NOT_YET_VALID, await(service.acceptReceipt(fixture.sign(new SupportReceipt(
                1, "test-key", SupportCreditFixture.AUDIENCE, "future", player, 100, now.plusSeconds(31), now.plusSeconds(300), SupportReceipt.CURRENCY)))).status());
        assertEquals(SupportPaymentResult.Status.INVALID, await(service.acceptReceipt(fixture.sign(new SupportReceipt(
                1, "test-key", SupportCreditFixture.AUDIENCE, "long", player, 100, now, now.plusSeconds(3601), SupportReceipt.CURRENCY)))).status());
        assertEquals(0, fixture.count("rpg_support_credit_accounts"));
    }

    @Test void sameOrderWithDifferentSignedOwnerOrAmountIsAConflict() throws Exception {
        UUID player = UUID.randomUUID();
        await(service.acceptReceipt(fixture.signed(player, 200, "conflict-order")));
        assertEquals(SupportPaymentResult.Status.CONFLICT, await(service.acceptReceipt(fixture.signed(player, 201, "conflict-order"))).status());
        assertEquals(SupportPaymentResult.Status.CONFLICT, await(service.acceptReceipt(fixture.signed(UUID.randomUUID(), 200, "conflict-order"))).status());
        assertEquals(200, await(service.balance(player)));
        assertEquals(1, fixture.count("rpg_support_payment_orders"));
    }

    @Test void concurrentExactImportsProduceOneGrantAndDurableDuplicateResults() throws Exception {
        UUID player = UUID.randomUUID(); var signed = fixture.signed(player, 500, "concurrent-order");
        var futures = new ArrayList<CompletableFuture<SupportPaymentResult>>();
        for (int i = 0; i < 12; i++) futures.add(service.acceptReceipt(signed).toCompletableFuture());
        long successes = futures.stream().map(CompletableFuture::join).filter(result -> result.status() == SupportPaymentResult.Status.SUCCESS).count();
        assertEquals(1, successes);
        for (var future : futures) assertTrue(Set.of(SupportPaymentResult.Status.SUCCESS, SupportPaymentResult.Status.DUPLICATE).contains(future.join().status()));
        assertEquals(500, await(service.balance(player)));
        assertEquals(1, fixture.count("rpg_support_credit_ledger"));
        assertEquals(500, fixture.scalar("SELECT issued_units FROM rpg_support_credit_limits WHERE scope_id='global'"));
    }

    @Test void perOrderAndPlayerUtcWeekCapsRejectWithoutMintingOrConsumingExtraQuota() throws Exception {
        UUID player = UUID.randomUUID();
        assertEquals(SupportPaymentResult.Status.PER_ORDER_LIMIT, await(service.acceptReceipt(fixture.signed(player, 1001, "too-large"))).status());
        await(service.acceptReceipt(fixture.signed(player, 1000, "week-a")));
        await(service.acceptReceipt(fixture.signed(player, 1000, "week-b")));
        assertEquals(SupportPaymentResult.Status.PLAYER_WEEK_LIMIT, await(service.acceptReceipt(fixture.signed(player, 1, "week-over"))).status());
        assertEquals(2000, await(service.status(player)).weekIssued());
        assertEquals(2, fixture.count("rpg_support_payment_orders"));
        fixture.clock.set(Instant.parse("2026-11-09T00:00:00Z"));
        assertEquals(SupportPaymentResult.Status.SUCCESS, await(service.acceptReceipt(fixture.signed(player, 1000, "next-monday"))).status());
        assertEquals(1000, await(service.status(player)).weekIssued());
        assertEquals(3000, await(service.balance(player)));
    }

    @Test void globalQuotaSerializesDistinctBeneficiaries() throws Exception {
        var futures = new ArrayList<CompletableFuture<SupportPaymentResult>>();
        for (int i = 0; i < 6; i++) futures.add(service.acceptReceipt(fixture.signed(UUID.randomUUID(), 1000, "global-" + i)).toCompletableFuture());
        assertEquals(4, futures.stream().map(CompletableFuture::join).filter(result -> result.status() == SupportPaymentResult.Status.SUCCESS).count());
        assertEquals(2, futures.stream().map(CompletableFuture::join).filter(result -> result.status() == SupportPaymentResult.Status.GLOBAL_WEEK_LIMIT).count());
        assertEquals(4000, fixture.scalar("SELECT SUM(balance) FROM rpg_support_credit_accounts"));
        assertEquals(4000, fixture.scalar("SELECT issued_units FROM rpg_support_credit_limits WHERE scope_id='global'"));
        assertEquals(4, fixture.count("rpg_support_payment_orders"));
    }

    @Test void balanceLimitRejectsWithoutOverflowOrQuotaConsumption() throws Exception {
        UUID player = UUID.randomUUID();
        fixture.execute("INSERT INTO rpg_support_credit_accounts (player_uuid,balance) VALUES ('" + player + "'," + SupportCreditPolicy.MAX_BALANCE + ")");
        assertEquals(SupportPaymentResult.Status.BALANCE_LIMIT, await(service.acceptReceipt(fixture.signed(player, 1, "full-account"))).status());
        assertEquals(SupportCreditPolicy.MAX_BALANCE, await(service.balance(player)));
        assertEquals(0, fixture.count("rpg_support_payment_orders"));
        assertEquals(0, fixture.scalar("SELECT SUM(issued_units) FROM rpg_support_credit_limits"));
    }

    @Test void disablingWhileAnAcceptedRequestWaitsForExecutionPreventsIssuance() throws Exception {
        var queue = new ArrayList<Runnable>();
        var queued = new JdbcSupportCreditService(fixture.dataSource, queue::add, fixture.policy, fixture.enabled::get, fixture.clock);
        var future = queued.acceptReceipt(fixture.signed(UUID.randomUUID(), 100, "queued"));
        assertEquals(1, queue.size()); fixture.enabled.set(false); queue.removeFirst().run();
        assertEquals(SupportPaymentResult.Status.DISABLED, await(future).status());
        assertEquals(0, fixture.count("rpg_support_payment_orders"));
    }

    @Test void expiryAfterTheFinalAccountLockCannotIssueAStaleReceipt() throws Exception {
        var signed = fixture.signed(UUID.randomUUID(), 100, "wait-expired");
        var blocked = fixture.service(SupportCreditFixture.fault(fixture.dataSource, "clock", () -> fixture.clock.set(fixture.clock.instant().plusSeconds(600))));
        assertEquals(SupportPaymentResult.Status.EXPIRED, await(blocked.acceptReceipt(signed)).status());
        assertEquals(0, fixture.count("rpg_support_credit_ledger"));
    }

    @Test void crossingUtcMondayDuringLockWaitRequiresNewAdmissionWithoutUsingOldQuota() throws Exception {
        fixture.clock.set(Instant.parse("2026-11-08T23:59:59Z"));
        var signed = fixture.signed(UUID.randomUUID(), 100, "week-boundary");
        var blocked = fixture.service(SupportCreditFixture.fault(fixture.dataSource, "clock", () -> fixture.clock.set(fixture.clock.instant().plusSeconds(2))));
        assertEquals(SupportPaymentResult.Status.RETRY_REQUIRED, await(blocked.acceptReceipt(signed)).status());
        assertEquals(0, fixture.count("rpg_support_payment_orders"));
        assertEquals(SupportPaymentResult.Status.SUCCESS, await(service.acceptReceipt(signed)).status());
        assertEquals(100, fixture.scalar("SELECT issued_units FROM rpg_support_credit_limits WHERE scope_id='global' AND week_key='2026-11-09'"));
        assertEquals(0, fixture.scalar("SELECT issued_units FROM rpg_support_credit_limits WHERE scope_id='global' AND week_key='2026-11-02'"));
    }

    @Test void paymentAuditFailureRollsBackOrderBalanceAndBothQuotas() throws Exception {
        UUID player = UUID.randomUUID(); var signed = fixture.signed(player, 250, "ledger-fault");
        var failing = fixture.service(SupportCreditFixture.fault(fixture.dataSource, "ledger", null));
        assertThrows(CompletionException.class, () -> await(failing.acceptReceipt(signed)));
        assertEquals(0, fixture.count("rpg_support_payment_orders"));
        assertEquals(0, fixture.count("rpg_support_credit_limits"));
        assertEquals(0, fixture.count("rpg_support_credit_accounts"));
        assertEquals(SupportPaymentResult.Status.SUCCESS, await(service.acceptReceipt(signed)).status());
    }

    @Test void beforeCommitFailureRollsBackTheWholeGrant() throws Exception {
        var signed = fixture.signed(UUID.randomUUID(), 200, "before-commit");
        var failing = fixture.service(SupportCreditFixture.fault(fixture.dataSource, "before-commit", null));
        assertThrows(CompletionException.class, () -> await(failing.acceptReceipt(signed)));
        assertEquals(0, fixture.count("rpg_support_credit_ledger"));
        assertEquals(0, fixture.count("rpg_support_payment_orders"));
        assertEquals(0, fixture.count("rpg_support_credit_limits"));
    }

    @Test void lostGrantCommitResponseReplaysFromDurableOrderWithoutGrantingTwice() throws Exception {
        UUID player = UUID.randomUUID(); var signed = fixture.signed(player, 200, "lost-response");
        var failing = fixture.service(SupportCreditFixture.fault(fixture.dataSource, "after-commit", null));
        assertThrows(CompletionException.class, () -> await(failing.acceptReceipt(signed)));
        assertEquals(SupportPaymentResult.Status.DUPLICATE, await(fixture.service(fixture.dataSource).acceptReceipt(signed)).status());
        assertEquals(200, await(service.balance(player)));
        assertEquals(1, fixture.count("rpg_support_credit_ledger"));
    }

    @Test void priceChangesInsufficientFundsAndInvalidCosmeticsNeverCreateAnEntitlement() throws Exception {
        UUID player = UUID.randomUUID(); await(service.acceptReceipt(fixture.signed(player, 50, "small")));
        assertEquals(SupportPurchaseResult.Status.PRICE_CHANGED, await(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 1, UUID.randomUUID())).status());
        assertEquals(SupportPurchaseResult.Status.INSUFFICIENT_FUNDS, await(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, UUID.randomUUID())).status());
        assertEquals(SupportPurchaseResult.Status.INVALID, await(service.purchaseCosmetic(player, null, 120, UUID.randomUUID())).status());
        assertEquals(50, await(service.balance(player)));
        assertEquals(0, fixture.count("rpg_support_cosmetic_purchases"));
        assertEquals(0, fixture.count("rpg_support_cosmetic_entitlements"));
    }

    @Test void replayAndConcurrentIndependentCheckoutsChargeForOwnershipOnlyOnce() throws Exception {
        UUID player = UUID.randomUUID(); await(service.acceptReceipt(fixture.signed(player, 1000, "purchase-funds")));
        UUID operation = UUID.randomUUID();
        var futures = new ArrayList<CompletableFuture<SupportPurchaseResult>>();
        futures.add(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation).toCompletableFuture());
        futures.add(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation).toCompletableFuture());
        for (int i = 0; i < 5; i++) futures.add(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, UUID.randomUUID()).toCompletableFuture());
        assertEquals(1, futures.stream().map(CompletableFuture::join).filter(result -> result.status() == SupportPurchaseResult.Status.SUCCESS).count());
        for (var future : futures) assertTrue(Set.of(SupportPurchaseResult.Status.SUCCESS, SupportPurchaseResult.Status.DUPLICATE,
                SupportPurchaseResult.Status.ALREADY_OWNED).contains(future.join().status()));
        assertEquals(880, await(service.balance(player)));
        assertEquals(1, fixture.count("rpg_support_cosmetic_entitlements"));
        assertEquals(2, fixture.count("rpg_support_credit_ledger"));
    }

    @Test void aPurchaseOperationCannotBeReboundToAnotherOwnerOrCosmetic() throws Exception {
        UUID player = UUID.randomUUID(); await(service.acceptReceipt(fixture.signed(player, 500, "binding-funds")));
        UUID operation = UUID.randomUUID(); await(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation));
        assertEquals(SupportPurchaseResult.Status.CONFLICT, await(service.purchaseCosmetic(player, SupportCosmetic.ELYTRA_WINGS, 200, operation)).status());
        assertEquals(SupportPurchaseResult.Status.CONFLICT, await(service.purchaseCosmetic(UUID.randomUUID(), SupportCosmetic.PRISMATIC_AURA, 120, operation)).status());
        assertEquals(380, await(service.balance(player)));
    }

    @Test void failedEntitlementWriteRollsBackItsDebitAndPurchaseReceipt() throws Exception {
        UUID player = UUID.randomUUID(); await(service.acceptReceipt(fixture.signed(player, 500, "entitlement-funds")));
        UUID operation = UUID.randomUUID();
        var failing = fixture.service(SupportCreditFixture.fault(fixture.dataSource, "entitlement", null));
        assertThrows(CompletionException.class, () -> await(failing.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation)));
        assertEquals(500, await(service.balance(player)));
        assertEquals(0, fixture.count("rpg_support_cosmetic_purchases"));
        assertEquals(0, fixture.count("rpg_support_cosmetic_entitlements"));
        assertEquals(1, fixture.count("rpg_support_credit_ledger"));
        assertEquals(SupportPurchaseResult.Status.SUCCESS, await(service.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation)).status());
    }

    @Test void lostPurchaseCommitResponseReplaysOneDebitAndOneEntitlement() throws Exception {
        UUID player = UUID.randomUUID(); await(service.acceptReceipt(fixture.signed(player, 500, "lost-purchase-funds")));
        UUID operation = UUID.randomUUID();
        var failing = fixture.service(SupportCreditFixture.fault(fixture.dataSource, "after-commit", null));
        assertThrows(CompletionException.class, () -> await(failing.purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation)));
        assertEquals(SupportPurchaseResult.Status.DUPLICATE, await(fixture.service(fixture.dataSource)
                .purchaseCosmetic(player, SupportCosmetic.PRISMATIC_AURA, 120, operation)).status());
        assertEquals(380, await(service.balance(player)));
        assertEquals(1, fixture.count("rpg_support_cosmetic_entitlements"));
        assertEquals(2, fixture.count("rpg_support_credit_ledger"));
    }

    @Test void disablingOrRetiringAVerificationKeyDoesNotSilentlyAcceptAnOldOrder() throws Exception {
        UUID player = UUID.randomUUID(); var signed = fixture.signed(player, 100, "retired");
        await(service.acceptReceipt(signed)); fixture.enabled.set(false);
        assertEquals(SupportPaymentResult.Status.DISABLED, await(service.acceptReceipt(signed)).status());
        fixture.enabled.set(true);
        var nextKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var nextPolicy = new SupportCreditPolicy(true, SupportCreditFixture.AUDIENCE, Map.of("next-key", nextKey.getPublic()),
                1000, 2000, 4000, Duration.ofHours(1), Duration.ofSeconds(30), fixture.policy.cosmeticPrices());
        var rotated = new JdbcSupportCreditService(fixture.dataSource, fixture.executor, nextPolicy, fixture.enabled::get, fixture.clock);
        assertEquals(SupportPaymentResult.Status.UNKNOWN_KEY, await(rotated.acceptReceipt(signed)).status());
        assertEquals(SupportPaymentResult.Status.DUPLICATE, await(service.acceptReceipt(signed)).status());
        assertEquals(100, await(service.balance(player)));
    }

    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
