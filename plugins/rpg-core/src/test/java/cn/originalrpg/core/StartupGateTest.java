package cn.originalrpg.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StartupGateTest {
    @Test
    void boundsConfiguredTimeoutWithoutChangingTheDocumentedDefault() {
        assertEquals(30_000L, StartupGate.DEFAULT_TIMEOUT_MILLIS);
        assertEquals(5_000L, StartupGate.boundedTimeoutMillis(1L));
        assertEquals(45_000L, StartupGate.boundedTimeoutMillis(45_000L));
        assertEquals(120_000L, StartupGate.boundedTimeoutMillis(Long.MAX_VALUE));
    }

    @Test
    void returnsCompletedValueAndPreservesFailureCause() {
        assertEquals("ready", StartupGate.await(
                CompletableFuture.completedFuture("ready"), Duration.ofSeconds(1), "test gate"));

        IllegalArgumentException cause = new IllegalArgumentException("broken ledger");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> StartupGate.await(
                CompletableFuture.failedFuture(cause), Duration.ofSeconds(1), "test gate"));
        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("test gate failed"));
    }

    @Test
    void timeoutCancelsUnfinishedFuture() {
        CompletableFuture<String> pending = new CompletableFuture<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> StartupGate.await(
                pending, Duration.ofMillis(20), "slow gate"));

        assertTrue(pending.isCancelled());
        assertTrue(failure.getMessage().contains("timed out after 20 ms"));
        assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
    }

    @Test
    void interruptionCancelsFutureAndRestoresInterruptFlag() throws Exception {
        CompletableFuture<String> pending = new CompletableFuture<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                StartupGate.await(pending, Duration.ofSeconds(5), "interrupt gate");
            } catch (Throwable throwable) {
                failure.set(throwable);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        thread.interrupt();
        thread.join(2_000L);

        assertFalse(thread.isAlive());
        assertTrue(pending.isCancelled());
        assertTrue(interrupted.get());
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertInstanceOf(InterruptedException.class, failure.get().getCause());
    }
}
