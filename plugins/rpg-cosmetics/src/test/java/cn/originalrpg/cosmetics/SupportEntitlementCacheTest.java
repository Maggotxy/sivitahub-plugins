package cn.originalrpg.cosmetics;

import cn.originalrpg.api.model.SupportCosmetic;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SupportEntitlementCacheTest {
    @Test void onlyACompletedCurrentDatabaseReadCanAuthorizeRenderingAndItExpires() {
        var clock = new MutableClock(); var cache = new SupportEntitlementCache<Object>(clock);
        UUID player = UUID.randomUUID(); Object connection = new Object();
        cache.authenticate(player, connection);
        assertFalse(cache.owns(player, connection, SupportCosmetic.PRISMATIC_AURA));
        var request = cache.beginRefresh(player, connection, false).orElseThrow();
        assertTrue(cache.beginRefresh(player, connection, false).isEmpty());
        assertTrue(cache.complete(request, Set.of(SupportCosmetic.PRISMATIC_AURA), true).current());
        assertTrue(cache.owns(player, connection, SupportCosmetic.PRISMATIC_AURA));
        clock.now = clock.now.plusSeconds(60);
        assertFalse(cache.owns(player, connection, SupportCosmetic.PRISMATIC_AURA));
        assertTrue(cache.beginRefresh(player, connection, false).isPresent());
    }

    @Test void aRevokedOrReauthenticatedConnectionCannotPopulateTheNewSessionsOwnership() {
        var cache = new SupportEntitlementCache<Object>(Clock.systemUTC());
        UUID player = UUID.randomUUID(); Object connection = new Object();
        cache.authenticate(player, connection);
        var old = cache.beginRefresh(player, connection, true).orElseThrow();
        cache.revoke(player, connection); cache.authenticate(player, connection);
        assertFalse(cache.complete(old, Set.of(SupportCosmetic.ELYTRA_WINGS), true).current());
        assertFalse(cache.owns(player, connection, SupportCosmetic.ELYTRA_WINGS));
        Object next = new Object(); cache.authenticate(player, next);
        assertFalse(cache.revoke(player, connection));
        assertTrue(cache.authenticated(player, next));
    }

    @Test void aPurchaseRefreshCoalescesBehindAnOlderReadAndRejectsItsStaleSnapshot() {
        var cache = new SupportEntitlementCache<Object>(Clock.systemUTC());
        UUID player = UUID.randomUUID(); Object connection = new Object(); cache.authenticate(player, connection);
        var old = cache.beginRefresh(player, connection, true).orElseThrow();
        for (int i = 0; i < 100; i++) assertTrue(cache.beginRefresh(player, connection, true).isEmpty());
        assertTrue(cache.complete(old, Set.of(), true).refreshAgain());
        var latest = cache.beginRefresh(player, connection, true).orElseThrow();
        assertFalse(cache.complete(old, Set.of(SupportCosmetic.PRISMATIC_AURA), true).current());
        assertTrue(cache.complete(latest, Set.of(SupportCosmetic.ELYTRA_COMET), true).current());
        assertTrue(cache.owns(player, connection, SupportCosmetic.ELYTRA_COMET));
        assertFalse(cache.owns(player, connection, SupportCosmetic.PRISMATIC_AURA));
    }

    @Test void failedRefreshClearsPaidPermissionAndBacksOffRepeatedReads() {
        var clock = new MutableClock(); var cache = new SupportEntitlementCache<Object>(clock);
        UUID player = UUID.randomUUID(); Object connection = new Object(); cache.authenticate(player, connection);
        var first = cache.beginRefresh(player, connection, true).orElseThrow();
        cache.complete(first, Set.of(SupportCosmetic.ELYTRA_COMET), true);
        var failed = cache.beginRefresh(player, connection, true).orElseThrow();
        assertFalse(cache.owns(player, connection, SupportCosmetic.ELYTRA_COMET));
        cache.complete(failed, Set.of(), false);
        assertFalse(cache.owns(player, connection, SupportCosmetic.ELYTRA_COMET));
        assertTrue(cache.beginRefresh(player, connection, false).isEmpty());
        clock.now = clock.now.plusSeconds(2);
        assertTrue(cache.beginRefresh(player, connection, false).isPresent());
        cache.clear(); assertFalse(cache.authenticated(player, connection));
    }

    @Test void everyPaidCatalogEntryNamesAnExistingCosmeticRenderer() {
        for (SupportCosmetic cosmetic : SupportCosmetic.values()) {
            assertTrue(java.util.Arrays.stream(CosmeticCatalog.values()).anyMatch(legacy ->
                    legacy.kind().name().equalsIgnoreCase(cosmetic.category()) && legacy.cosmeticId().equals(cosmetic.rendererId())));
            if (cosmetic.category().equals("aura")) assertTrue(AuraType.parse(cosmetic.rendererId()).isPresent());
            else assertTrue(TrailStyle.parse(cosmetic.rendererId()).isPresent());
        }
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
