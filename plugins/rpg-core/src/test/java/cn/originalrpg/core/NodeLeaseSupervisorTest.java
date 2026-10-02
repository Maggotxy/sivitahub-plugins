package cn.originalrpg.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行为级测试：租约有效与失效两条路径的可观察差异（C-014）。
 * 断言的是「是否失败关闭」这一行为，不是源码字符串。
 */
final class NodeLeaseSupervisorTest {
    private static final Logger LOGGER = Logger.getLogger(NodeLeaseSupervisorTest.class.getName());

    @Test
    void validLeaseKeepsServingAndNeverRequestsShutdown() {
        FakeLease lease = new FakeLease();
        lease.renewResult = true;
        lease.validFor(60);
        List<String> shutdowns = new ArrayList<>();
        NodeLeaseSupervisor supervisor = new NodeLeaseSupervisor(
                lease, Duration.ofSeconds(20), LOGGER,
                (reason, error) -> shutdowns.add(reason));

        assertTrue(supervisor.verifyAcquiredToken());
        assertTrue(supervisor.renewOnce().toCompletableFuture().join());
        assertEquals(1, lease.renewCalls);
        assertTrue(shutdowns.isEmpty());
        assertFalse(supervisor.stopped());
    }

    @Test
    void fencedLeaseFailsClosedExactlyOnce() {
        FakeLease lease = new FakeLease();
        lease.renewResult = false;
        lease.validFor(0);
        List<String> shutdowns = new ArrayList<>();
        NodeLeaseSupervisor supervisor = new NodeLeaseSupervisor(
                lease, Duration.ofSeconds(20), LOGGER,
                (reason, error) -> shutdowns.add(reason));

        assertFalse(supervisor.renewOnce().toCompletableFuture().join());
        assertTrue(supervisor.stopped());
        assertEquals(1, shutdowns.size());
        assertTrue(shutdowns.get(0).contains("fenced"));

        // 失租后不再重复处置，也不再发起续期。
        assertFalse(supervisor.renewOnce().toCompletableFuture().join());
        assertEquals(1, shutdowns.size());
        assertEquals(1, lease.renewCalls);
    }

    @Test
    void databaseOutageWithinRetryBudgetWarnsInsteadOfShuttingDown() {
        FakeLease lease = new FakeLease();
        lease.renewFailure = new IllegalStateException("connection refused");
        lease.validFor(60);
        List<String> shutdowns = new ArrayList<>();
        NodeLeaseSupervisor supervisor = new NodeLeaseSupervisor(
                lease, Duration.ofSeconds(20), LOGGER,
                (reason, error) -> shutdowns.add(reason));

        assertFalse(supervisor.renewOnce().toCompletableFuture().join());
        assertTrue(shutdowns.isEmpty());
        assertFalse(supervisor.stopped());
        // 预算 = heartbeat + 5s = 25s，租约只剩 60s，仍可重试。
        assertTrue(lease.lastBudget.getSeconds() >= 25);
    }

    @Test
    void databaseOutageBeyondRetryBudgetFailsClosed() {
        FakeLease lease = new FakeLease();
        lease.renewFailure = new IllegalStateException("connection refused");
        lease.validFor(5);
        List<Throwable> errors = new ArrayList<>();
        NodeLeaseSupervisor supervisor = new NodeLeaseSupervisor(
                lease, Duration.ofSeconds(20), LOGGER,
                (reason, error) -> errors.add(error));

        assertFalse(supervisor.renewOnce().toCompletableFuture().join());
        assertTrue(supervisor.stopped());
        assertEquals(1, errors.size());
        assertEquals("connection refused", errors.get(0).getMessage());
    }

    @Test
    void leaseNotConfirmedAfterAcquisitionFailsClosed() {
        FakeLease lease = new FakeLease();
        lease.validFor(0);
        List<String> shutdowns = new ArrayList<>();
        NodeLeaseSupervisor supervisor = new NodeLeaseSupervisor(
                lease, Duration.ofSeconds(20), LOGGER,
                (reason, error) -> shutdowns.add(reason));

        assertFalse(supervisor.verifyAcquiredToken());
        assertTrue(supervisor.stopped());
        assertEquals(1, shutdowns.size());
        assertTrue(shutdowns.get(0).contains("not confirmed"));
    }

    @Test
    void retryBudgetIsCappedAndHeartbeatMustBePositive() {
        FakeLease lease = new FakeLease();
        NodeLeaseSupervisor supervisor = new NodeLeaseSupervisor(
                lease, Duration.ofSeconds(120), LOGGER, (reason, error) -> { });
        assertEquals(NodeLeaseSupervisor.MAX_RETRY_BUDGET, supervisor.retryBudget());
        assertEquals(Duration.ofSeconds(120), supervisor.heartbeat());
        assertThrows(IllegalArgumentException.class, () -> new NodeLeaseSupervisor(
                lease, Duration.ZERO, LOGGER, (reason, error) -> { }));
    }

    private static final class FakeLease implements NodeLeaseSupervisor.LeaseRenewal {
        private boolean renewResult;
        private RuntimeException renewFailure;
        private Duration validFor = Duration.ZERO;
        private int renewCalls;
        private Duration lastBudget;

        private void validFor(long seconds) {
            validFor = Duration.ofSeconds(seconds);
        }

        @Override
        public CompletionStage<Boolean> renewAsync() {
            renewCalls++;
            if (renewFailure != null) return CompletableFuture.failedFuture(renewFailure);
            return CompletableFuture.completedFuture(renewResult);
        }

        @Override
        public boolean mayStillBeValid() {
            return validFor.compareTo(Duration.ofSeconds(0)) > 0;
        }

        @Override
        public boolean mayStillBeValidFor(Duration retryBudget) {
            lastBudget = retryBudget;
            return validFor.compareTo(retryBudget) > 0;
        }
    }
}
