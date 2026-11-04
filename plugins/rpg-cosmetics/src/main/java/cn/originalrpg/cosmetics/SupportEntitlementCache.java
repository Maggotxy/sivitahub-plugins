package cn.originalrpg.cosmetics;

import cn.originalrpg.api.model.SupportCosmetic;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Expiring rendering hints loaded from the database authority. This cache never
 * persists ownership to player data. Use on the server thread; at most one read
 * is in flight per connection, and a forced refresh coalesces behind that read.
 */
final class SupportEntitlementCache<C> {
    private final Clock clock;
    private final Map<UUID, Session<C>> sessions = new HashMap<>();

    SupportEntitlementCache(Clock clock) { this.clock = clock; }

    void authenticate(UUID playerId, C connection) { sessions.put(playerId, new Session<>(connection)); }

    boolean authenticated(UUID playerId, C connection) {
        Session<C> session = sessions.get(playerId);
        return session != null && session.connection == connection;
    }

    boolean revoke(UUID playerId, C connection) {
        Session<C> session = sessions.get(playerId);
        return session != null && session.connection == connection && sessions.remove(playerId, session);
    }

    boolean owns(UUID playerId, C connection, SupportCosmetic cosmetic) {
        Session<C> session = sessions.get(playerId);
        return session != null && session.connection == connection && clock.millis() < session.expiresAt
                && session.owned.contains(cosmetic);
    }

    Optional<Request<C>> beginRefresh(UUID playerId, C connection, boolean force) {
        Session<C> session = sessions.get(playerId);
        if (session == null || session.connection != connection) return Optional.empty();
        if (session.request != null) {
            if (force) { session.refreshAgain = true; session.owned = Set.of(); }
            return Optional.empty();
        }
        long now = clock.millis();
        if (!force && now < session.retryAt) return Optional.empty();
        session.retryAt = now + 2_000L;
        if (!force && now < session.expiresAt) return Optional.empty();
        session.owned = Set.of();
        Request<C> request = new Request<>(playerId, connection, session.generation, UUID.randomUUID());
        session.request = request;
        return Optional.of(request);
    }

    Completion complete(Request<C> request, Set<SupportCosmetic> owned, boolean success) {
        Session<C> session = sessions.get(request.playerId());
        if (session == null || session.connection != request.connection() || !session.generation.equals(request.generation())
                || !request.equals(session.request)) return new Completion(false, false);
        session.request = null;
        boolean repeat = session.refreshAgain;
        session.refreshAgain = false;
        session.owned = success && !repeat ? Set.copyOf(owned) : Set.of();
        session.expiresAt = success && !repeat ? clock.millis() + 60_000L : 0;
        return new Completion(true, repeat);
    }

    void clear() { sessions.clear(); }

    record Request<C>(UUID playerId, C connection, UUID generation, UUID requestId) { }
    record Completion(boolean current, boolean refreshAgain) { }
    private static final class Session<C> {
        final C connection;
        final UUID generation = UUID.randomUUID();
        Set<SupportCosmetic> owned = Set.of();
        Request<C> request;
        long expiresAt;
        long retryAt;
        boolean refreshAgain;
        Session(C connection) { this.connection = connection; }
    }
}
