package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.CommercePurchaseResult;
import cn.originalrpg.api.model.CommerceResult;
import cn.originalrpg.api.model.CreditResult;
import cn.originalrpg.api.model.RewardClaimResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.*;

class SupportLegacyIsolationTest {
    @Test void historicalMixedMembershipAndAManuallyCopiedExpiryHaveNoLiveAuthority() throws Exception {
        try (var fixture = new SupportCreditFixture()) {
            UUID player = UUID.randomUUID(); var oldExpiry = fixture.clock.instant().plus(29, ChronoUnit.DAYS);
            fixture.execute("INSERT INTO rpg_commerce_accounts (player_uuid, lifetime_paid, credits, member_until, earned_member_until) VALUES ('"
                    + player + "', 100.00, 0, TIMESTAMP '" + Timestamp.from(oldExpiry) + "', TIMESTAMP '" + Timestamp.from(oldExpiry) + "')");
            var commerce = commerce(fixture);
            var status = await(commerce.status(player));
            assertFalse(status.memberActive(fixture.clock.instant())); assertNull(status.memberUntil());
            assertEquals(0, status.lifetimePaid().compareTo(new BigDecimal("100.00")), "Historical payment audit remains intact");
            assertEquals(CommerceResult.Status.LEGACY_DISABLED,
                    await(commerce.recordPayment(player, new BigDecimal("30"), "new-legacy-payment", 30, "console")).status());
            assertEquals(RewardClaimResult.Status.LEGACY_DISABLED,
                    await(commerce.claimReward(player, "first-support", new BigDecimal("30"), "historical-material")).status());
            assertEquals(0, fixture.count("rpg_payment_ledger")); assertEquals(0, fixture.count("rpg_market_deliveries"));
            assertEquals(1, fixture.scalar("SELECT COUNT(*) FROM rpg_commerce_accounts WHERE member_until IS NOT NULL"));
        }
    }

    @Test void newEarnedMembershipStartsFromNowAndRequiresGrantPurchaseAndOriginalStarlightDebit() throws Exception {
        try (var fixture = new SupportCreditFixture()) {
            UUID player = UUID.randomUUID(); var oldExpiry = fixture.clock.instant().plus(29, ChronoUnit.DAYS);
            fixture.execute("INSERT INTO rpg_commerce_accounts (player_uuid, lifetime_paid, credits, member_until) VALUES ('"
                    + player + "', 100.00, 0, TIMESTAMP '" + Timestamp.from(oldExpiry) + "')");
            var commerce = commerce(fixture);
            assertEquals(CreditResult.Status.SUCCESS, await(commerce.adjustCredits(player, 100, "earned-test-funding", "test")).status());
            var purchase = await(commerce.purchaseMembershipWithCredits(player, "member-test", 50, 3, "member-proof", "week-1", 1));
            assertEquals(CommercePurchaseResult.Status.SUCCESS, purchase.status());
            assertEquals(fixture.clock.instant().plus(3, ChronoUnit.DAYS), purchase.account().memberUntil(),
                    "A legacy paid expiry cannot extend the new earned membership");
            assertEquals(1, fixture.count("rpg_earned_membership_grants"));
            assertEquals(CommercePurchaseResult.Status.DUPLICATE,
                    await(commerce.purchaseMembershipWithCredits(player, "member-test", 50, 3, "member-proof", "week-1", 1)).status());
            assertEquals(CommercePurchaseResult.Status.INVALID,
                    await(commerce.purchaseMembershipWithCredits(player, "member-test", 50, 4, "member-proof", "week-1", 1)).status());
            fixture.execute("DELETE FROM rpg_credit_ledger WHERE external_reference='starlight-spend:member-proof'");
            assertNull(await(commerce.status(player)).memberUntil(), "An expiry and grant without the original matching debit fail closed");
            assertEquals(0, fixture.count("rpg_support_credit_ledger"));
        }
    }

    @Test void aGrantCannotBorrowAnotherPlayersRealDebitAsMembershipProof() throws Exception {
        try (var fixture = new SupportCreditFixture()) {
            UUID player = UUID.randomUUID(); var commerce = commerce(fixture);
            await(commerce.adjustCredits(player, 100, "earned-proof-funding", "test"));
            await(commerce.purchaseMembershipWithCredits(player, "member-test", 50, 3, "owner-proof", "week-1", 1));
            assertNotNull(await(commerce.status(player)).memberUntil());
            fixture.execute("UPDATE rpg_credit_ledger SET player_uuid='" + UUID.randomUUID() + "' WHERE external_reference='starlight-spend:owner-proof'");
            assertNull(await(commerce.status(player)).memberUntil());
        }
    }

    private static JdbcCommerceService commerce(SupportCreditFixture fixture) {
        return new JdbcCommerceService(fixture.dataSource, fixture.executor, BigDecimal.ZERO, OperationalStarlightPolicy.testDefault(), fixture.clock);
    }
    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
