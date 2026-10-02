package cn.originalrpg.market;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxClaimGateTest {
    @Test
    void onlyOneConcurrentClaimCanOwnAPlayer() throws Exception {
        MailboxClaimGate gate = new MailboxClaimGate();
        UUID playerId = UUID.randomUUID();
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(24);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(24)) {
            for (int index = 0; index < 24; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (gate.begin(playerId) != null) accepted.incrementAndGet();
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, accepted.get());
        assertTrue(gate.active(playerId));
    }

    @Test
    void failureOrDisconnectReleaseAllowsAnIdempotentRetry() {
        MailboxClaimGate gate = new MailboxClaimGate();
        UUID playerId = UUID.randomUUID();

        MailboxClaimGate.Lease first = gate.begin(playerId);
        assertNotNull(first);
        assertNull(gate.begin(playerId));
        assertTrue(gate.finish(first));
        assertNotNull(gate.begin(playerId));
    }

    @Test
    void differentPlayersDoNotBlockEachOther() {
        MailboxClaimGate gate = new MailboxClaimGate();

        assertNotNull(gate.begin(UUID.randomUUID()));
        assertNotNull(gate.begin(UUID.randomUUID()));
    }

    @Test
    void staleLeaseCannotReleaseANewerClaim() {
        MailboxClaimGate gate = new MailboxClaimGate();
        UUID playerId = UUID.randomUUID();
        MailboxClaimGate.Lease old = gate.begin(playerId);
        assertNotNull(old);
        assertTrue(gate.finish(old));

        MailboxClaimGate.Lease current = gate.begin(playerId);
        assertNotNull(current);
        assertFalse(gate.finish(old));
        assertTrue(gate.active(playerId));
        assertTrue(gate.finish(current));
        assertFalse(gate.active(playerId));
    }
}
