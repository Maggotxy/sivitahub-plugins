package cn.originalrpg.core.persistence;

import cn.originalrpg.api.model.SupportPaymentResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class SupportPaymentGateTest {
    @Test void anotherNodesStaleCacheCannotOverrideTheDatabasePaymentGateInEitherDirection() throws Exception {
        try (var fixture = new SupportCreditFixture()) {
            fixture.clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            var first = new MechanismRegistry(fixture.dataSource); first.initialize();
            var second = new MechanismRegistry(fixture.dataSource); second.initialize();
            var a = new JdbcSupportCreditService(fixture.dataSource, fixture.executor, fixture.policy, first);
            var b = new JdbcSupportCreditService(fixture.dataSource, fixture.executor, fixture.policy, second);
            assertFalse(await(a.paymentAcceptanceEnabled()));
            assertTrue(await(a.setPaymentAcceptanceEnabled(true, "test-console")));
            assertFalse(second.isEnabled(MechanismRegistry.SUPPORT_CREDIT_PAYMENT), "Other node deliberately has a stale disabled cache");
            assertEquals(SupportPaymentResult.Status.SUCCESS, await(b.acceptReceipt(fixture.signed(UUID.randomUUID(), 100, "other-node-enabled"))).status());
            second.refresh();
            assertFalse(await(a.setPaymentAcceptanceEnabled(false, "test-console")));
            assertTrue(second.isEnabled(MechanismRegistry.SUPPORT_CREDIT_PAYMENT), "Other node deliberately has a stale enabled cache");
            assertFalse(await(b.paymentAcceptanceEnabled()));
            assertEquals(SupportPaymentResult.Status.DISABLED, await(b.acceptReceipt(fixture.signed(UUID.randomUUID(), 100, "other-node-disabled"))).status());
            assertEquals(1, fixture.count("rpg_support_payment_orders"));
        }
    }

    @Test void disablingWaitsForTheAlreadyAdmittedCommitAndRejectsLaterImportsAcrossNodes() throws Exception {
        try (var fixture = new SupportCreditFixture()) {
            fixture.clock.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
            var first = new MechanismRegistry(fixture.dataSource); first.initialize();
            first.setEnabled(MechanismRegistry.SUPPORT_CREDIT_PAYMENT, true, "test-console");
            CountDownLatch aboutToCommit = new CountDownLatch(1);
            CountDownLatch releaseCommit = new CountDownLatch(1);
            CountDownLatch disableReachedSql = new CountDownLatch(1);
            var held = SupportCreditFixture.fault(fixture.dataSource, "hold-commit", null, () -> {
                aboutToCommit.countDown();
                try { if (!releaseCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Commit release timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            });
            var firstNode = new JdbcSupportCreditService(held, fixture.executor, fixture.policy, first);
            var secondRegistry = new MechanismRegistry(SupportCreditFixture.fault(fixture.dataSource, "gate-update", disableReachedSql::countDown));
            var secondNode = new JdbcSupportCreditService(fixture.dataSource, fixture.executor, fixture.policy, secondRegistry);
            var admitted = firstNode.acceptReceipt(fixture.signed(UUID.randomUUID(), 100, "admitted-before-disable")).toCompletableFuture();
            assertTrue(aboutToCommit.await(10, TimeUnit.SECONDS));
            var disable = secondNode.setPaymentAcceptanceEnabled(false, "other-node-console").toCompletableFuture();
            try {
                assertTrue(disableReachedSql.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> disable.get(100, TimeUnit.MILLISECONDS),
                        "The gate update cannot overtake the business transaction holding its row lock");
            } finally { releaseCommit.countDown(); }
            assertEquals(SupportPaymentResult.Status.SUCCESS, admitted.get(10, TimeUnit.SECONDS).status());
            assertFalse(disable.get(10, TimeUnit.SECONDS));
            assertEquals(SupportPaymentResult.Status.DISABLED, await(firstNode.acceptReceipt(fixture.signed(UUID.randomUUID(), 100, "after-disable"))).status());
            assertEquals(1, fixture.count("rpg_support_payment_orders"));
        }
    }

    @Test void persistentEnableCannotBypassADisabledDeploymentPolicy() throws Exception {
        try (var fixture = new SupportCreditFixture()) {
            var registry = new MechanismRegistry(fixture.dataSource); registry.initialize();
            var disabled = new JdbcSupportCreditService(fixture.dataSource, fixture.executor, SupportCreditPolicy.disabled(), registry);
            assertFalse(await(disabled.setPaymentAcceptanceEnabled(true, "test-console")));
            assertTrue(registry.isEnabled(MechanismRegistry.SUPPORT_CREDIT_PAYMENT));
            assertFalse(await(disabled.paymentAcceptanceEnabled()));
            assertEquals(SupportPaymentResult.Status.DISABLED, await(disabled.acceptReceipt(fixture.signed(UUID.randomUUID(), 100, "policy-still-disabled"))).status());
            assertEquals(0, fixture.count("rpg_support_payment_orders"));
        }
    }
    private static <T> T await(CompletionStage<T> stage) { return stage.toCompletableFuture().join(); }
}
