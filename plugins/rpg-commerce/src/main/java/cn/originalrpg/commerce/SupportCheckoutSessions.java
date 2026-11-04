package cn.originalrpg.commerce;

import cn.originalrpg.api.model.SupportCosmetic;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Connection-owned checkout previews, not an ownership or balance authority.
 * All methods run on the server thread. A submitted transaction keeps its
 * single-flight slot across reconnects until its exact completion releases it.
 */
final class SupportCheckoutSessions<C> {
    private final Clock clock;
    private final Map<UUID, Session<C>> sessions = new HashMap<>();
    private final Map<UUID, Checkout<C>> submitted = new HashMap<>();

    SupportCheckoutSessions(Clock clock) { this.clock = clock; }

    UUID authenticate(UUID playerId, C connection) {
        UUID generation = UUID.randomUUID();
        sessions.put(playerId, new Session<>(connection, generation, null));
        return generation;
    }

    void revoke(UUID playerId, C connection) {
        Session<C> session = sessions.get(playerId);
        if (session != null && session.connection() == connection) sessions.remove(playerId);
    }

    Optional<UUID> generation(UUID playerId, C connection) {
        Session<C> session = sessions.get(playerId);
        return session != null && session.connection() == connection ? Optional.of(session.generation()) : Optional.empty();
    }

    boolean current(UUID playerId, C connection, UUID generation) {
        return generation(playerId, connection).filter(generation::equals).isPresent();
    }

    boolean busy(UUID playerId) { return submitted.containsKey(playerId); }

    Optional<Preview> preview(UUID playerId, C connection, SupportCosmetic cosmetic, long cost) {
        Session<C> session = sessions.get(playerId);
        if (session == null || session.connection() != connection || busy(playerId)) return Optional.empty();
        Preview preview = new Preview(UUID.randomUUID(), cosmetic, cost, clock.instant().plusSeconds(60));
        sessions.put(playerId, new Session<>(connection, session.generation(), preview));
        return Optional.of(preview);
    }

    Optional<Checkout<C>> confirm(UUID playerId, C connection, UUID previewId) {
        Session<C> session = sessions.get(playerId);
        Preview preview = session == null ? null : session.preview();
        if (session == null || session.connection() != connection || preview == null || busy(playerId)
                || !preview.id().equals(previewId) || !clock.instant().isBefore(preview.expiresAt())) return Optional.empty();
        Checkout<C> checkout = new Checkout<>(playerId, connection, session.generation(), preview);
        submitted.put(playerId, checkout);
        sessions.put(playerId, new Session<>(connection, session.generation(), null));
        return Optional.of(checkout);
    }

    boolean complete(Checkout<C> checkout) {
        if (!submitted.remove(checkout.playerId(), checkout)) return false;
        return current(checkout.playerId(), checkout.connection(), checkout.generation());
    }

    void close() { sessions.clear(); submitted.clear(); }

    record Preview(UUID id, SupportCosmetic cosmetic, long cost, Instant expiresAt) { }
    record Checkout<C>(UUID playerId, C connection, UUID generation, Preview preview) { }
    private record Session<C>(C connection, UUID generation, Preview preview) { }
}
