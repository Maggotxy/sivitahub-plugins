package cn.originalrpg.commerce;

import cn.originalrpg.api.model.SupportCosmetic;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SupportCheckoutSessionsTest {
    @Test void aPreviewBindsTheExactProductPriceAndOneOperationToAnAuthenticatedConnection() {
        var sessions = new SupportCheckoutSessions<Object>(Clock.systemUTC());
        UUID player = UUID.randomUUID(); Object connection = new Object();
        assertTrue(sessions.preview(player, connection, SupportCosmetic.PRISMATIC_AURA, 120).isEmpty());
        sessions.authenticate(player, connection);
        var preview = sessions.preview(player, connection, SupportCosmetic.PRISMATIC_AURA, 120).orElseThrow();
        assertTrue(sessions.confirm(player, new Object(), preview.id()).isEmpty());
        var checkout = sessions.confirm(player, connection, preview.id()).orElseThrow();
        assertEquals(preview, checkout.preview());
        assertEquals(120, checkout.preview().cost());
        assertTrue(sessions.confirm(player, connection, preview.id()).isEmpty());
        assertTrue(sessions.preview(player, connection, SupportCosmetic.ELYTRA_WINGS, 200).isEmpty());
        assertTrue(sessions.complete(checkout));
        assertFalse(sessions.complete(checkout));
    }

    @Test void reauthenticationOnTheSamePlayerObjectInvalidatesOldConfirmationAndFeedback() {
        var sessions = new SupportCheckoutSessions<Object>(Clock.systemUTC());
        UUID player = UUID.randomUUID(); Object connection = new Object();
        sessions.authenticate(player, connection);
        var oldPreview = sessions.preview(player, connection, SupportCosmetic.PRISMATIC_AURA, 120).orElseThrow();
        sessions.authenticate(player, connection);
        assertTrue(sessions.confirm(player, connection, oldPreview.id()).isEmpty());
        var freshPreview = sessions.preview(player, connection, SupportCosmetic.PRISMATIC_AURA, 120).orElseThrow();
        var submitted = sessions.confirm(player, connection, freshPreview.id()).orElseThrow();
        sessions.authenticate(player, connection);
        assertTrue(sessions.busy(player));
        assertFalse(sessions.complete(submitted), "Old completion may release its lease but cannot address a new auth generation");
        assertFalse(sessions.busy(player));
    }

    @Test void quitDoesNotReleaseAnInFlightTransactionAndOldQuitCannotRevokeTheNewConnection() {
        var sessions = new SupportCheckoutSessions<Object>(Clock.systemUTC());
        UUID player = UUID.randomUUID(); Object old = new Object(); Object fresh = new Object();
        sessions.authenticate(player, old);
        var preview = sessions.preview(player, old, SupportCosmetic.ELYTRA_COMET, 300).orElseThrow();
        var submitted = sessions.confirm(player, old, preview.id()).orElseThrow();
        sessions.revoke(player, old); sessions.authenticate(player, fresh); sessions.revoke(player, old);
        assertTrue(sessions.generation(player, fresh).isPresent());
        assertTrue(sessions.preview(player, fresh, SupportCosmetic.ELYTRA_COMET, 300).isEmpty());
        assertFalse(sessions.complete(submitted));
        assertTrue(sessions.preview(player, fresh, SupportCosmetic.ELYTRA_COMET, 300).isPresent());
    }

    @Test void previewExpiryAndNewPreviewInvalidateOldTokensWithoutPurchasing() {
        var clock = new MutableClock(); var sessions = new SupportCheckoutSessions<Object>(clock);
        UUID player = UUID.randomUUID(); Object connection = new Object(); sessions.authenticate(player, connection);
        var old = sessions.preview(player, connection, SupportCosmetic.PRISMATIC_AURA, 120).orElseThrow();
        var latest = sessions.preview(player, connection, SupportCosmetic.ELYTRA_WINGS, 200).orElseThrow();
        assertTrue(sessions.confirm(player, connection, old.id()).isEmpty());
        clock.now = clock.now.plusSeconds(60);
        assertTrue(sessions.confirm(player, connection, latest.id()).isEmpty());
        assertFalse(sessions.busy(player));
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
