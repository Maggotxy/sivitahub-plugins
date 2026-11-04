package cn.originalrpg.integrations;

import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudReadinessPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofSeconds(30);

    @Test
    void acceptingDownloadingAndFailedReloadsNeverExposeBitmapGlyphs() {
        assertFalse(ready(true, null, NOW, true, NOW));
        for (Status status : Status.values()) {
            if (status == Status.SUCCESSFULLY_LOADED) continue;
            assertFalse(ready(true, status, NOW, true, NOW), status.name());
        }
        assertTrue(ready(true, Status.SUCCESSFULLY_LOADED, NOW, true, NOW));
    }

    @Test
    void disconnectAndEitherMissingPlayerDatasetHidePreviouslyLoadedHud() {
        assertFalse(ready(false, Status.SUCCESSFULLY_LOADED, NOW, true, NOW));
        assertFalse(ready(true, Status.SUCCESSFULLY_LOADED, null, true, NOW));
        assertFalse(ready(true, Status.SUCCESSFULLY_LOADED, NOW, false, NOW));
        assertTrue(ready(true, Status.SUCCESSFULLY_LOADED, NOW, true, NOW));
    }

    @Test
    void staleProfileDisappearsAndFreshCacheRestoresWithoutAnotherPackDownload() {
        Instant refreshed = NOW.minus(MAX_AGE);
        assertTrue(ready(true, Status.SUCCESSFULLY_LOADED, refreshed, true, NOW));
        assertFalse(ready(true, Status.SUCCESSFULLY_LOADED, refreshed, true, NOW.plusMillis(1)));
        assertTrue(ready(true, Status.SUCCESSFULLY_LOADED, NOW, true, NOW.plusMillis(1)));
    }

    private static boolean ready(boolean online, Status pack, Instant refreshed, boolean skills, Instant now) {
        return HudReadinessPolicy.ready(online, pack, refreshed, skills, MAX_AGE, now);
    }
}
