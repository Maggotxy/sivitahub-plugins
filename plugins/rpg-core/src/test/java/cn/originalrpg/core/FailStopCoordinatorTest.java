package cn.originalrpg.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FailStopCoordinatorTest {
    @Test
    void schedulesOneShutdownFromWorkerThread() {
        FailStopCoordinator coordinator = new FailStopCoordinator();
        AtomicInteger shutdowns = new AtomicInteger();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();

        assertTrue(coordinator.request(false, shutdowns::incrementAndGet, scheduled::set));
        assertFalse(coordinator.request(false, shutdowns::incrementAndGet, scheduled::set));
        assertEquals(0, shutdowns.get());
        assertNotNull(scheduled.get());
        scheduled.get().run();
        assertEquals(1, shutdowns.get());
    }

    @Test
    void fallsBackToDirectShutdownWhenPrimarySchedulerRejects() {
        FailStopCoordinator coordinator = new FailStopCoordinator();
        AtomicInteger shutdowns = new AtomicInteger();

        assertTrue(coordinator.request(false, shutdowns::incrementAndGet, ignored -> {
            throw new IllegalStateException("scheduler is stopping");
        }));

        assertEquals(1, shutdowns.get());
    }

    @Test
    void primaryThreadStopsImmediately() {
        FailStopCoordinator coordinator = new FailStopCoordinator();
        AtomicInteger shutdowns = new AtomicInteger();

        assertTrue(coordinator.request(true, shutdowns::incrementAndGet, ignored -> {
            throw new AssertionError("primary thread must not schedule");
        }));

        assertEquals(1, shutdowns.get());
    }
}
