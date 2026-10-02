package cn.originalrpg.performance;

import cn.originalrpg.api.model.PersistenceSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PersistenceHealthTest {
    @Test
    void onlyEnabledProviderWithLivePoolIsAvailable() {
        PersistenceSnapshot healthy = new PersistenceSnapshot(0, 6, 6, 0, 0, 0, 256);
        assertTrue(PersistenceHealth.capture(true, () -> healthy).ledgerAvailable());
        assertFalse(PersistenceHealth.capture(false, () -> healthy).ledgerAvailable());
        assertFalse(PersistenceHealth.capture(true, () -> null).ledgerAvailable());
        assertFalse(PersistenceHealth.capture(true,
                () -> new PersistenceSnapshot(0, 0, 0, 0, 0, 0, 256)).ledgerAvailable());
    }

    @Test
    void serviceFailureCannotReuseAnOldHealthySnapshot() {
        PersistenceHealth failed = PersistenceHealth.capture(true, () -> {
            throw new IllegalStateException("provider stopped");
        });

        assertFalse(failed.ledgerAvailable());
        assertEquals(PersistenceSnapshot.unavailable(), failed.snapshot());
    }
}
